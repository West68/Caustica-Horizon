package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves the texture backing an entity {@link RenderType} to a Vulkan image-view handle, for the
 * bindless entity-texture array. Entities use per-type texture files (zombie.png, …), not the block
 * atlas, so each distinct texture maps to its own bindless slot. Slots are keyed by the resolved image
 * view rather than the {@link RenderType}: some render types are rebuilt every frame with a fresh
 * identity (the charged-creeper energy swirl scrolls its texture transform, so {@code energySwirl()}
 * allocates a new RenderType per frame), and keying by RenderType there would leak a slot per frame
 * until the array exhausts and everything falls back to slot 0.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}): the
 * primary sampler is {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are the overlay/lightmap
 * the prepared list prepends). Resolution is cached per {@code RenderType} (they are stable singletons),
 * so the prepare() cost is paid once per distinct texture.
 */
public final class RtEntityTextures {
    /** Bindless array capacity (slot 0 reserved as a fallback texture). {@code -Dcaustica.rt.maxEntityTextures}. */
    public static int maxTextures() {
        // Older configs stored 256, which a server with custom skins and heads runs through.
        return Math.max(CausticaConfig.Rt.EntityTextures.MAX_TEXTURES.value(), 4096);
    }

    /** Enable pack-compiled entity materials; disabled entities use the canonical neutral header. */
    public static boolean entityPbr() {
        return CausticaConfig.Rt.EntityTextures.PBR.value();
    }

    public static final RtEntityTextures INSTANCE = new RtEntityTextures();

    // RenderType identity → resolved primary image-view handle. WEAK: some render types are rebuilt
    // every frame with a fresh identity (e.g. the charged-creeper energy-swirl layer, whose scrolling
    // texture transform makes RenderTypes.energySwirl() allocate a new RenderType each frame). A weak map
    // lets those dead identities be collected instead of accumulating; stable singletons (zombie.png, …)
    // stay cached and skip the costly RenderType.prepare().
    // Cached as the view object, not just its handle: a mod may close a texture and register a new one under
    // the same Identifier (Better Player Model reloading YSM models; the RenderType is memoized per id). A
    // closed view's handle is a destroyed VkImageView — sampling it, or a new view that reuses the same handle
    // value hitting the stale slot, crashed the driver with no report. Closed views are re-resolved.
    private final Map<RenderType, GpuTextureView> viewCache = new WeakHashMap<>();
    // Lazily uploaded textures (e.g. YSM models) have no view on first use: retry instead of caching 0 forever.
    private final Map<RenderType, Long> unresolved = new WeakHashMap<>();
    // When each cached view was last confirmed against the render type's live binding. A mod can register a
    // different texture under the same id without closing the old one (Better Player Model swaps the model's
    // texture in after first use on joining a world); a cached view would then keep the placeholder forever.
    private final Map<RenderType, Long> confirmedAt = new WeakHashMap<>();
    private static final long RECONFIRM_FRAMES = 120;
    // Slots of closed views, reusable once every frame that could still sample them has retired.
    private final java.util.ArrayDeque<long[]> retiredSlots = new java.util.ArrayDeque<>();
    private static final long SLOT_REUSE_FRAMES = 8;
    private final Map<RenderType, Identifier> locationCache = new WeakHashMap<>();
    // Resolved image-view handle → bindless slot. The slot identifies a *texture*, not a RenderType, so
    // many render types that differ only by a texture transform we don't replicate (the swirl's scroll)
    // collapse to ONE slot — instead of leaking a slot per frame until the array exhausts and everything
    // falls back to slot 0 (the block atlas). Append-only: a handle's slot never changes once assigned,
    // so update-after-bind writes for new slots never disturb in-flight frames.
    private final Map<Long, Integer> viewSlotCache = new HashMap<>();
    // Atlas-location → bindless slot, for items/blocks (which texture from an atlas, not a per-type
    // file). Seeded with the block atlas = slot 0 (also the fallback). Items use a separate item atlas.
    private final Map<Identifier, Integer> atlasSlotCache = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>(); // albedo slots awaiting descriptor upload
    // Descriptor array capacity of the currently alive world pipeline. A higher config value applies after
    // reset/recreate; a lower value stops allocating new slots immediately without invalidating old ones.
    private int capacity = maxTextures();
    private boolean exhaustionLogged;
    private int nextSlot = 1;
    private boolean loggedFailure;
    private boolean loggedMaterialFailure;
    // 1x1 solid-white DynamicTexture for untextured geometry (leash/line ribbons); see whiteSlot().
    private static final Identifier WHITE_LOCATION = Identifier.fromNamespaceAndPath("caustica", "rt_white");
    private boolean whiteRegistered;

    // Cached RenderSetup.TextureBinding#location() (the class is package-private, the method public).
    private Method locationMethod;

    private record Pending(int slot, long view) {
    }

    private RtEntityTextures() {
    }

    /**
     * The bindless slot for {@code renderType}'s texture. Keyed by the resolved image view, not the
     * RenderType, so per-frame-allocated render types sharing one texture reuse a single slot. Returns 0
     * (the fallback slot = block atlas) if the texture can't be resolved or the array is full.
     */
    public int slotFor(RenderType renderType) {
        if (renderType == null) {
            return 0;
        }
        // Slot 0 is the block atlas: entity UVs on it show as a scramble of unrelated block textures (servers with
        // many skins, heads and pack textures filled the array). An unresolved or unplaceable texture draws plain.
        long view = resolveView(renderType);
        if (view == 0L) {
            return whiteSlot();
        }
        int slot = slotForView(view);
        if (slot == 0 && !viewSlotCache.containsKey(view)) {
            if (!exhaustionLogged) {
                exhaustionLogged = true;
                CausticaMod.LOGGER.warn("Entity texture array is full ({} slots)", slotLimit());
            }
            return whiteSlot();
        }
        return slot;
    }

    /** Canonical material ID for a full entity texture, or the neutral runtime-texture fallback. */
    public int materialIdFor(RenderType renderType, boolean stochasticAlpha) {
        var texture=textureLocation(renderType);
        int id=entityPbr() || dev.comfyfluffy.caustica.rt.material.PortalMaterials.flags(texture==null?null:texture.toString())!=0
            ?RtMaterialRegistry.INSTANCE.resolveEntityTexture(textureLocation(renderType), stochasticAlpha)
            :RtMaterialRegistry.INSTANCE.entityFallbackId(stochasticAlpha);
        Object setup=((RenderTypeAccessor)renderType).caustica$state();
        var pipeline=((RenderSetupAccessor)setup).caustica$pipeline();
        String pass=pipeline.getLocation().toString();String name=texture==null?"":texture.toString();
        boolean brightOnly=name.contains("/blaze")||name.contains("magmacube")||name.contains("magma_cube");
        boolean additive=pass.contains("eyes")||pass.contains("energy_swirl")||pass.contains("glint");
        boolean crystal=name.contains("end_crystal")&&!name.contains("beam");
        // Verified against 26.2 renderer getBlockLightLevel overrides, not scene lightmap brightness.
        boolean fullBright=name.contains("/allay/")||name.contains("/illager/vex")
                ||name.contains("dragon_fireball")||name.contains("shulker/spark")||name.contains("experience_orb")
                ||name.contains("end_crystal_beam")||pass.contains("beacon_beam")||pass.contains("lightning");
        if(CausticaConfig.Rt.Visuals.VANILLA_ENTITY_EMISSION.value() && (crystal || fullBright || brightOnly || additive || pass.contains("emissive") || pass.contains("dragon_rays") || name.contains("dragon_exploding") || name.contains("glow_squid")))
            id=RtMaterialRegistry.INSTANCE.withEntityEmission(id,brightOnly,additive,crystal);
        else if(additive)id=RtMaterialRegistry.INSTANCE.withAdditiveEntityAlpha(id);
        if(pass.contains("glint"))id=RtMaterialRegistry.INSTANCE.withSubtleGlint(id);
        return id;
    }

    /**
     * The bindless slot for a texture atlas (block/item atlas used by item + block-model quads), cached
     * per atlas location. The block atlas is pre-seeded to slot 0; other atlases (the item atlas) get
     * their own slot. Returns 0 (fallback) if unresolvable or the array is full.
     */
    public int slotForAtlas(Identifier atlasLocation) {
        if (atlasLocation == null) {
            return 0;
        }
        Integer cached = atlasSlotCache.get(atlasLocation);
        if (cached != null && cached == 0) {
            return 0; // block atlas
        }
        long view = 0L;
        try {
            GpuTextureView v = Minecraft.getInstance().getTextureManager().getTexture(atlasLocation).getTextureView();
            view = vkImageView(v);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT atlas texture resolution failed for {}", atlasLocation, t);
            }
        }
        // Resolve the live view every call: an atlas that was not ready yet (first frames, mid resource
        // reload) used to cache slot 0 forever, so particles sampled the BLOCK atlas with particle UVs
        // (bright stray block-texture shapes at torches/campfires), and a reload left a dead view's slot.
        return slotForView(view);
    }

    /**
     * Bindless slot of a solid-white 1x1 texture, for untextured geometry (leashes, custom line
     * ribbons) whose colour comes entirely from the per-prim tint. Slot 0 (the block atlas) is NOT a
     * substitute: the hit shader multiplies albedo by the sampled texel, and UV (0,0) lands on
     * whatever sprite is stitched at the atlas origin. The texture is registered with the
     * TextureManager on first use and then resolved/cached by the ordinary atlas-slot path, so it
     * survives {@link #reset} like any other atlas. Falls back to slot 0 if registration fails.
     */
    public int whiteSlot() {
        if (!whiteRegistered) {
            whiteRegistered = true; // one attempt; on failure slotForAtlas caches the slot-0 fallback
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager()
                    .register(WHITE_LOCATION, new DynamicTexture(() -> "caustica RT white", image));
        }
        return slotForAtlas(WHITE_LOCATION);
    }

    /**
     * Map a resolved image-view handle to a stable bindless slot, allocating one on first sight (queued
     * for upload via {@link #uploadPending}). Returns 0 (fallback) when the view is unresolved or the
     * array is full. Deduping by handle is what bounds slot use: distinct render types backed by the same
     * texture share a slot instead of each consuming one.
     */
    private final Map<String, Pending> externalSlots = new HashMap<>();

    /** Stable named slots for optional renderer-owned views; caller drains old readers before replacement. */
    public int externalSlot(String name,long view) {
        if(view==0)return 0;
        Pending previous=externalSlots.get(name);
        if(previous!=null && previous.view()==view)return previous.slot();
        int slot;
        if(previous!=null)slot=previous.slot();
        else {
            // Dedicated tail slots do not consume the user's entity/material texture budget.
            if(capacity<maxTextures()+2 || externalSlots.size()>=2)return 0;
            slot=capacity-1-externalSlots.size();
        }
        Pending update=new Pending(slot,view);externalSlots.put(name,update);pending.add(update);
        return slot;
    }

    private int slotForView(long view) {
        if (view == 0L) {
            return 0;
        }
        Integer cached = viewSlotCache.get(view);
        if (cached != null) {
            return cached;
        }
        int slot;
        long[] retired = retiredSlots.peekFirst();
        if (retired != null && dev.comfyfluffy.caustica.rt.RtComposite.frameCounter() - retired[1] >= SLOT_REUSE_FRAMES) {
            retiredSlots.pollFirst();
            slot = (int) retired[0];
        } else if (nextSlot < slotLimit()) {
            slot = nextSlot++;
        } else {
            return 0;
        }
        viewSlotCache.put(view, slot);
        pending.add(new Pending(slot, view));
        return slot;
    }

    /** Write any newly-registered entity textures into the pipeline's bindless set (before the trace). */
    public void uploadPending(RtPipeline pipeline, long sampler) {
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : pending) {
            pipeline.setEntityAlbedoTexture(p.slot(), p.view(), sampler);
        }
        pending.clear();
    }

    /** Drop the registry (call when the world pipeline / bindless set is recreated, or textures reload). */
    public void reset() {
        reset(maxTextures());
    }

    /** Drop the registry for a pipeline whose bindless descriptor arrays have this capacity. */
    public void reset(int descriptorCapacity) {
        capacity = Math.max(1, descriptorCapacity);
        viewCache.clear();
        unresolved.clear();
        retiredSlots.clear();
        locationCache.clear();
        viewSlotCache.clear();
        externalSlots.clear();
        atlasSlotCache.clear();
        atlasSlotCache.put(TextureAtlas.LOCATION_BLOCKS, 0); // block atlas = the slot-0 fallback
        pending.clear();
        nextSlot = 1;
    }

    public static int descriptorCapacity() {
        return maxTextures()+(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")?2:0);
    }

    private int slotLimit() {
        return Math.min(capacity, maxTextures());
    }

    /** Recover the resource Identifier of {@code renderType}'s primary texture (Sampler0), or null. The
     *  {@code RenderSetup.TextureBinding} class is package-private, so {@code location()} is reflective. */
    Identifier textureLocation(RenderType renderType) {
        if (renderType == null) return null;
        if (locationCache.containsKey(renderType)) return locationCache.get(renderType);
        Identifier result = null;
        try {
            // RenderSetup is final, so the accessor cast must go through Object (the interface is only
            // mixed in at runtime); RenderType is non-final so its cast is fine directly.
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            Map<String, ?> textures = ((RenderSetupAccessor) setup).caustica$textures();
            Object binding = textures.get("Sampler0");
            if (binding == null) {
                locationCache.put(renderType, null);
                return null;
            }
            if (locationMethod == null) {
                locationMethod = binding.getClass().getMethod("location");
                locationMethod.setAccessible(true);
            }
            result = (Identifier) locationMethod.invoke(binding);
        } catch (Throwable t) {
            warnMaterialOnce("RT entity texture Identifier resolution failed for " + renderType, t);
        }
        locationCache.put(renderType, result);
        return result;
    }

    private void warnMaterialOnce(String msg, Throwable t) {
        if (!loggedMaterialFailure) {
            loggedMaterialFailure = true;
            CausticaMod.LOGGER.warn(msg, t);
        }
    }

    /** The Vulkan image-view handle of {@code renderType}'s primary texture, or 0 if it can't be resolved. */
    public long resolveView(RenderType renderType) {
        if (renderType == null) {
            return 0L;
        }
        GpuTextureView cached = viewCache.get(renderType);
        if (cached != null) {
            long frame = dev.comfyfluffy.caustica.rt.RtComposite.frameCounter();
            Long confirmed = confirmedAt.get(renderType);
            boolean fresh = confirmed != null && frame - confirmed < RECONFIRM_FRAMES;
            GpuTextureView live = cached;
            if (!fresh) {
                try { live = liveView(renderType); } catch (Throwable ignored) { live = cached; }
            }
            if (!cached.isClosed() && !cached.texture().isClosed() && (live == null || live == cached)) {
                if (!fresh) confirmedAt.put(renderType, frame);
                return vkImageView(cached);
            }
            viewCache.remove(renderType);
            // The handle may be reused by the replacement view; never let it find the stale slot.
            Integer stale = viewSlotCache.remove(vkImageView(cached));
            if (stale != null) retiredSlots.addLast(new long[]{stale, dev.comfyfluffy.caustica.rt.RtComposite.frameCounter()});
        } else {
            Long failedAt = unresolved.get(renderType);
            if (failedAt != null && dev.comfyfluffy.caustica.rt.RtComposite.frameCounter() - failedAt < 30) return 0L;
        }
        long handle = 0L;
        try {
            GpuTextureView chosen = liveView(renderType);
            if (chosen != null && !chosen.isClosed() && !chosen.texture().isClosed()) {
                handle = vkImageView(chosen);
                if (handle != 0L) { viewCache.put(renderType, chosen); confirmedAt.put(renderType, dev.comfyfluffy.caustica.rt.RtComposite.frameCounter()); }
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        if (handle == 0L) unresolved.put(renderType, dev.comfyfluffy.caustica.rt.RtComposite.frameCounter());
        else unresolved.remove(renderType);
        return handle;
    }

    /** The albedo view the render type binds right now (Sampler1 for portal layers), or null. */
    private GpuTextureView liveView(RenderType renderType) {
        PreparedRenderType prepared = renderType.prepare();
        GpuTextureView firstNonAux = null;
        Identifier resource=textureLocation(renderType);
        boolean portal=dev.comfyfluffy.caustica.rt.material.PortalMaterials.flags(resource==null?null:resource.toString())==32;
        for (PreparedRenderType.Texture t : prepared.textures()) {
            String name = t.name();
            if ((portal ? "Sampler1" : "Sampler0").equals(name)) return t.textureView();
            if (firstNonAux == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) firstNonAux = t.textureView();
        }
        return firstNonAux;
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
