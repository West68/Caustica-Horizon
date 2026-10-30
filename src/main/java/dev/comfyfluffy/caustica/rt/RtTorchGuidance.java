package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Render-thread-owned immutable generations; changes retire behind the graphics timeline. */
final class RtTorchGuidance {
    private RtTerrain owner;
    private long revision = Long.MIN_VALUE;
    private int originX, originY, originZ;
    private float[] centers = new float[0];
    private RtBuffer buffer;

    int originX() { return originX; }
    int originY() { return originY; }
    int originZ() { return originZ; }

    long update(RtContext ctx, RtTerrain terrain, double camX, double camY, double camZ) {
        int x = (int)Math.floor(camX / 16) * 16 - 32;
        int y = (int)Math.floor(camY / 16) * 16 - 32;
        int z = (int)Math.floor(camZ / 16) * 16 - 32;
        long nextRevision = terrain.staticInstanceRevision();
        if (owner == terrain && revision == nextRevision && x == originX && y == originY && z == originZ)
            return address();
        FloatArrayList found = new FloatArrayList();
        terrain.visitTorches((tx, ty, tz) -> {
            double rx = tx - x, ry = ty - y, rz = tz - z;
            double high = TorchGuidanceGrid.SIZE * TorchGuidanceGrid.CELL_SIZE + TorchGuidanceGrid.REACH;
            if (rx < -TorchGuidanceGrid.REACH || ry < -TorchGuidanceGrid.REACH || rz < -TorchGuidanceGrid.REACH
                    || rx > high || ry > high || rz > high) return;
            found.add((float)rx); found.add((float)ry); found.add((float)rz);
        });
        float[] next = found.toFloatArray();
        // Chunk publications/rebases without changes to nearby torches need no grid work or upload.
        if (x != originX || y != originY || z != originZ || !Arrays.equals(centers, next)) {
            RtBuffer replacement = null;
            if (next.length != 0) {
                float[] data = TorchGuidanceGrid.build(next);
                replacement = ctx.createBuffer((long)data.length * Float.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        true, "torch continuation guidance");
                try {
                    MemoryUtil.memFloatBuffer(replacement.mapped, data.length).put(data);
                    replacement.flush();
                } catch (Throwable t) { replacement.destroy(); throw t; }
            }
            RtBuffer previous = buffer;
            buffer = replacement;
            if (previous != null) ctx.gpuExecutor().enqueueDestroyAfterGraphics(
                    ctx.gpuExecutor().latestGraphicsUseValue(), previous::destroy);
            centers = next;
        }
        originX = x; originY = y; originZ = z;
        owner = terrain; revision = nextRevision;
        return address();
    }
    private long address() { return buffer == null ? 0L : buffer.deviceAddress; }

    /** Caller has drained the device (reload/device teardown), matching the other world resources. */
    void destroy() {
        if (buffer != null) buffer.destroy();
        buffer = null; owner = null; revision = Long.MIN_VALUE; centers = new float[0];
    }
}
