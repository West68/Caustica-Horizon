package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * Per DH texture tile: the resource pack's physical material for the block that tile came from, so far LODs reflect
 * and glow like the same block up close. One uint per tile (DH caps tiles at 65536, 256 KiB), filled incrementally
 * from DH's own block-state registry as DH registers new blocks; never per frame, never a full rescan.
 *
 * <p>Layout: bits 0-7 roughness, 8-15 metalness, 16-19 light emission (0-15), bit 31 = known. Zero = unknown, and the
 * shader then keeps its material-class fallback. Entries only ever go from zero to a value until the next reset,
 * so the GPU reading a frame-old table sees, at worst, a block still using the fallback.
 */
public final class DhLodMaterials {
    public static final DhLodMaterials INSTANCE = new DhLodMaterials();
    private static final int TILES = 65536;
    private static final long BYTES = TILES * 4L;
    /** New block states mapped per call: a large first import spreads over frames instead of one long stall. */
    private static final int STATES_PER_FRAME = 128;

    private RtBuffer table;
    private boolean unavailable;
    private Object registry;
    private Map<?, ?> setIds;
    private Method faceTileIds, wrappedState;
    private final java.util.BitSet mappedSets = new java.util.BitSet();
    private int lastRegistered = -1;
    private long materialEpoch = -1;

    /** Device address of the table for the DH geometry entries; 0 while DH's registry is unreachable. */
    public long address(RtContext ctx) {
        if (unavailable) return 0L;
        try {
            if (table == null) {
                table = ctx.createBuffer(BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "DH LOD material table");
                MemoryUtil.memSet(table.mapped, 0, BYTES);
                table.flush(0, BYTES);
            }
            update();
            return table.deviceAddress;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            unavailable = true;
            CausticaMod.LOGGER.warn("DH LOD material table unavailable; far LODs keep their material-class fallback", e);
            return 0L;
        }
    }

    private void update() throws ReflectiveOperationException {
        if (registry == null) {
            Class<?> c = Class.forName("com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry");
            registry = c.getField("INSTANCE").get(null);
            Field f = c.getDeclaredField("setIdByBlockStateWrapper");
            f.setAccessible(true);
            setIds = (Map<?, ?>) f.get(registry);
            faceTileIds = c.getMethod("getFaceTileIds", int.class);
            wrappedState = Class.forName("com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper")
                    .getMethod("getWrappedMcObject");
        }
        var materials = RtMaterialRegistry.INSTANCE;
        if (!materials.isReady()) return;
        int registered = setIds.size();
        // A resource reload rebuilds both registries; DH's tile ids then mean different blocks.
        if (materials.epoch() != materialEpoch || registered < lastRegistered) {
            materialEpoch = materials.epoch();
            mappedSets.clear();
            MemoryUtil.memSet(table.mapped, 0, BYTES);
            table.flush(0, BYTES);
            lastRegistered = -1;
        }
        if (registered == lastRegistered) return;
        var snapshot = materials.requireSnapshot();
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        int mapped = 0;
        boolean complete = true;
        for (var entry : setIds.entrySet()) {
            int setId = ((Short) entry.getValue()) & 0xffff;
            if (mappedSets.get(setId)) continue;
            if (mapped++ == STATES_PER_FRAME) { complete = false; break; }
            mappedSets.set(setId);
            try {
                if (!(wrappedState.invoke(entry.getKey()) instanceof BlockState state)) continue;
                var sprite = models.get(state).particleMaterial().sprite();
                var desc = snapshot.material(snapshot.resolve(sprite, state, false));
                int packed = 0x80000000
                        | Math.round(Math.clamp(desc.roughness(), 0f, 1f) * 255f)
                        | Math.round(Math.clamp(desc.metalness(), 0f, 1f) * 255f) << 8
                        | Math.clamp(state.getLightEmission(), 0, 15) << 16;
                if (!(faceTileIds.invoke(registry, setId) instanceof short[] tiles)) continue;
                for (short tile : tiles) {
                    int t = tile & 0xffff;
                    // Shared tiles (identical ratio pixels) keep the first block that claimed them.
                    if (t != 0 && MemoryUtil.memGetInt(table.mapped + t * 4L) == 0) MemoryUtil.memPutInt(table.mapped + t * 4L, packed);
                }
            } catch (ReflectiveOperationException | RuntimeException notReady) {
                // DH may publish a set id before its face tiles; retry this block on a later frame.
                mappedSets.clear(setId);
                complete = false;
            }
        }
        table.flush(0, BYTES);
        if (complete) lastRegistered = registered;
    }

    /** Caller has quiesced the context (RtDhLods.shutdown). */
    public void shutdown() {
        if (table != null) { table.destroy(); table = null; }
        mappedSets.clear();
        lastRegistered = -1;
        materialEpoch = -1;
    }

    private DhLodMaterials() {}
}
