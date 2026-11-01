package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.ParticleEngineAccessor;
import dev.comfyfluffy.caustica.mixin.ParticleGroupAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUseWaiter;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Dynamic entities as real ray-traced {@code ModelPart} geometry. Each frame, every model entity is
 * re-posed and captured ({@link RtEntityCollector} + {@link RtEntityCapture}) into a mesh in terrain's
 * vertex layout, uploaded, and given a per-entity BLAS built inline in the composite's frame command
 * buffer. One TLAS instance per entity places entity-local geometry at {@code anchor - rebase} and carries
 * the {@link #ENTITY_BIT} custom-index flag so {@code world.rchit} takes the
 * entity path. A per-frame entity geometry table ({@code {primAddr, idxAddr, uvAddr, disp, triBase[]}})
 * gives the hit shader each entity's per-triangle data, motion-vector displacement, and the base needed
 * to map each BLAS geometry's local primitive index into the packed index/primitive arrays.
 * Non-model entities (items/arrows …geometry via submitItem/submitBlockModel, which the collector
 * ignores) are skipped.
 *
 * <p>Per-frame cost is real (per-entity capture + buffer uploads + a BLAS build); capped by {@code
 * -Dcaustica.rt.maxEntities}. Changed-entity geometry and refit scratch reuse the existing per-entity
 * graphics-timeline-guarded ring; motion uploads suballocate from a guarded per-frame-slot arena. A generic
 * size-bucketed recycling free-list was tried and measured slower per-call than trusting VMA's own allocator.
 */
public final class RtEntities {
    public static final RtEntities INSTANCE = new RtEntities();
    public static boolean enabled() {
        return CausticaConfig.Rt.Entities.ENABLED.value();
    }

    /** Custom-index flag bit (bit 23 of the 24-bit instanceCustomIndex) marking an entity instance. */
    public static final int ENTITY_BIT = 0x800000;
    /** Custom-index flag (bit 22) marking a particle billboard instance (shares the entity geom table). */
    public static final int PARTICLE_BIT = 0x400000;
    // TLAS visibility-mask bits, ANDed against the per-ray cull mask in world.rgen. Bit 0 = secondary rays
    // (shadows / GI / reflections, CULL_SECONDARY); bit 1 = the primary camera ray (CULL_PRIMARY).
    private static final int MASK_SECONDARY = 0x01;
    private static final int MASK_PRIMARY = 0x02;
    /** Default mask: visible to every ray (terrain and ordinary entities use this). */
    private static final int MASK_ALL = 0xFF;
    /** Particles are primary-ray-only: visible/lit by the camera path, invisible to shadows/GI/reflections. */
    // 0x10: also visible to specular continuations (mirrors, glass and water) …never to the dominant
    // diffuse/shadow rays, which keep skipping particles (world.rgen CULL_SPECULAR_PARTICLES).
    private static final int PARTICLE_MASK = MASK_PRIMARY | 0x10;
    public static boolean particlesEnabled() {
        return CausticaConfig.Rt.Entities.PARTICLES_ENABLED.value();
    }
    public static boolean glowEnabled() {
        return CausticaConfig.Rt.Entities.GLOW_ENABLED.value();
    }
    public static boolean nameTagsEnabled() {
        return CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED.value();
    }

    private static int maxEntities() {
        return CausticaConfig.Rt.Entities.maxEntities();
    }

    private static int maxOrdinaryEntities() {
        return CausticaConfig.Rt.Entities.MAX_ORDINARY_ENTITIES.value();
    }

    private static int maxBlockEntities() {
        return CausticaConfig.Rt.Entities.MAX_BLOCK_ENTITIES.value();
    }

    private static int maxParticles() {
        return CausticaConfig.Rt.Entities.MAX_PARTICLES.value();
    }

    private static int entityListCapacity() {
        return CausticaConfig.Rt.Entities.entityListCapacity();
    }

    private static int entityMapCapacity() {
        return CausticaConfig.Rt.Entities.entityMapCapacity();
    }

    // Chunk radius around the player to scan for block entities (chests/signs/… each frame.
    private static int beViewChunks() {
        return CausticaConfig.Rt.Entities.BE_VIEW_CHUNKS.value();
    }

    // Block entities keep a cached mesh + BLAS keyed by BlockPos. Each frame the BE is re-meshed (cheap)
    // and its mesh hashed; the expensive BLAS is rebuilt ONLY when the mesh actually changed …so static
    // BEs cost no GPU work while animating ones (chest lid, spawner, … rebuild every frame. New/changed
    // rebuilds are capped per frame so a burst of newly loaded chunks can't stall (over-budget BEs keep
    // their last geometry / pop in over later frames, like terrain's worker dispatch budget).
    private static int beBuildsPerFrame() {
        return CausticaConfig.Rt.Entities.BE_BUILDS_PER_FRAME.value();
    }

    // EntityGeom: four addresses + rigid displacement + three geometry triangle bases + padding = 64 B.
    private static final int TABLE_ENTRY_BYTES = 64;
    // Fixed-size geometry-table ring. Timeline completion guards host writes; ring depth avoids routine waits.
    private static final int TABLE_RING = 6;
    // Stale-cache eviction horizon and default reusable-resource ring depth.
    // Graphics timeline completion guards GPU reuse and destruction.
    private static final int KEEP_FRAMES = 4;
    private static final int FRAME_LIST_RING = KEEP_FRAMES;
    // Refit (UPDATE-mode) BLAS: persistent per-entity AS, refit in place each frame (cheap) while
    // topology is stable, instead of a full BUILD. Block entities always use the pooled-BUILD path.
    //
    // Rigid reuse: when this frame's capture is a rigid transform (translation and/or yaw) of the mesh the
    // entity's AS was last built from, reference that AS with the fitted TLAS instance transform and skip
    // the mesh upload + refit entirely …still mobs, item frames/armor stands, and spinning/bobbing
    // dropped items become table-entry + instance writes only.

    // Max per-axis residual (blocks) for a capture to count as a rigid transform of the reference mesh.
    // Well below a texel (1/16 block) and DLSS-RR jitter; float pose math noise is ~1e-5.
    private static final float RIGID_FIT_EPS = 2.0e-3f;

    // Each per-entity ring slot owns one persistent AS. Timeline completion guards cursor reuse,
    // mapped writes, refits, rebuilds, and destruction.
    private static final int REFIT_RING = KEEP_FRAMES;
    // Force a periodic full rebuild of a slot's AS to bound BVH-quality degradation from repeated refits
    // (an entity that deforms a lot would otherwise refit the same BVH topology forever). Per-slot count.
    private static final int REFIT_REBUILD_INTERVAL = 120;

    // Treat per-vertex displacements as rigid when every vertex agrees within this tolerance, avoiding a
    // transient disp buffer for plain whole-entity translation.
    private static final float RIGID_DISP_EPS = 1.0e-5f;
    private static final long MOTION_PAGE_BYTES = 1L << 20;
    private static final long MOTION_ALIGNMENT = 16L;
    private static final int MOTION_UNUSED_RETIRE_CYCLES = 16;
    private static final int TRANSIENT_BUFFER_LIST_CAPACITY = 8;
    // Identity 3x4 row-major. Particles are already captured in rebased space; dynamic entities use a
    // translate/yaw instance transform because their geometry is captured around the entity anchor.
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final Motion NO_MOTION = new Motion(0L, 0f, 0f, 0f);

    // Reusable capture pipeline (single-threaded on the render thread).
    private final RtEntityCollector collector = new RtEntityCollector();
    private final RtEntityCapture capture = new RtEntityCapture();
    private final PoseStack entityPoseStack = new PoseStack();
    private final PoseStack blockEntityPoseStack = new PoseStack();
    private CameraRenderState cameraState;
    // Particle capture: a VertexConsumer adapter that funnels MC's billboard quads into `capture` (the
    // shared entity mesh). We extract each live particle into `particleScratch`, accumulate per-vertex
    // motion-vector displacements in `particleDisp`, and key the previous-frame center off particle
    // identity in `particlePrev` (rebuilt each frame →?prunes dead particles).
    private int billboardParticlesCaptured;
    private int modelParticleFailures;
    private final RtParticleCapture particleCapture = new RtParticleCapture(capture);
    private final QuadParticleRenderState particleScratch = new QuadParticleRenderState();
    private final FloatArrayList particleDisp = new FloatArrayList();
    private final FloatArrayList beDispScratch = new FloatArrayList();
    private IdentityHashMap<Particle, ParticlePrev> particlePrev = new IdentityHashMap<>();
    private IdentityHashMap<Particle, ParticlePrev> particleCur = new IdentityHashMap<>();
    private final float[] particleCenterScratch = new float[3];

    /** Previous frame's particle center (rebase-space) + that frame's rebase origin, for the MV diff. */
    private static final class ParticlePrev {
        float cx, cy, cz;
        int rbx, rby, rbz;

        void set(float cx, float cy, float cz, int rbx, int rby, int rbz) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.rbx = rbx;
            this.rby = rby;
            this.rbz = rbz;
        }
    }

    private static final int DH_COVERAGE_BYTES=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")?131072:0;
    private TableSlot[] tableRing;
    private int tableCapacity;
    private int tableSlot;

    private final FrameLists[] frameLists = new FrameLists[FRAME_LIST_RING];

    // Previous frame's captured entity-local vertex positions + its interpolated world anchor, keyed by
    // entity id. Maps are swapped/reused each frame: entries not seen this frame fall out, while visible
    // entities keep their float[] backing to avoid steady-state allocation churn.
    private Int2ObjectOpenHashMap<EntityPrev> prevVerts = new Int2ObjectOpenHashMap<>(entityMapCapacity());
    private Int2ObjectOpenHashMap<EntityPrev> curVerts = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // This frame's glowing entities (see GlowEntity) + the camera-relative offset (camera pos - rebase
    // origin) their positions are captured against, for RtGlowOutlineFeature's raster pass. Rebuilt every frame.
    private final List<GlowEntity> glowBatches = new ArrayList<>();
    private float glowCamOffsetX, glowCamOffsetY, glowCamOffsetZ;

    /** This frame's glowing entities, or an empty list if none (or glow is disabled). */
    public List<GlowEntity> glowBatches() {
        return glowBatches;
    }

    public float glowCamOffsetX() {
        return glowCamOffsetX;
    }

    public float glowCamOffsetY() {
        return glowCamOffsetY;
    }

    public float glowCamOffsetZ() {
        return glowCamOffsetZ;
    }

    // This frame's visible name tags (see NameTagEntity), captured off the SAME EntityRenderState vanilla's
    // own EntityRenderer.extractNameTags already populates (shouldShowName/crosshair-look/distance rules,
    // computed as a side effect of the dispatcher.extractEntity call captureEntities already makes) …no
    // reimplementation of that logic. Positions are rebase-space (same convention as glowBatches); consumed
    // by RtNameTagFeature's raster pass, which reuses glowCamOffset{X,Y,Z} (same camera, same frame).
    private final List<NameTagEntity> nameTagBatches = new ArrayList<>();

    /** This frame's visible name tags, or an empty list if none (or name tags are disabled). */
    public List<NameTagEntity> nameTagBatches() {
        return nameTagBatches;
    }

    /** This frame's camera orientation (view-to-world rotation) …the billboard rotation name tags face. */
    public Quaternionf cameraOrientation() {
        return cameraState.orientation;
    }

    /** Last frame's posed mesh for one entity: local vertex positions + its interpolated world anchor. */
    private static final class EntityPrev {
        float[] verts = new float[0];
        int size;
        double anchorX, anchorY, anchorZ;
    }

    private long retainedGeometryBytes;

    // Persistent per-entity acceleration structures, keyed by entity id, for refit.
    private final Int2ObjectOpenHashMap<EntityAccel> entityAccels = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // Persistent per-block-entity geometry, keyed by BlockPos.asLong(). Built once and reused every frame.
    private final Map<Long, BeEntry> beCache = new HashMap<>();
    private final List<BeCandidate> beCandidates = new ArrayList<>();
    private final ArrayDeque<BeCandidate> beCandidatePool = new ArrayDeque<>();
    // (Re)builds recorded so far this frame, reset each beginFrame; gates new BE builds to BE_BUILDS_PER_FRAME.
    private int beBuildsThisFrame;

    private RtEntities() {
        for (int i = 0; i < frameLists.length; i++) {
            frameLists[i] = new FrameLists();
        }
    }

    /**
     * Cached block-entity geometry. The mesh is captured in <b>block-local</b> space (identity submit pose),
     * so it is rebase-independent …only the per-frame TLAS instance transform ({@code blockPos −rebase})
     * changes, exactly like a terrain section. The BLAS + mesh buffers are this entry's own VMA allocations
     * and persist until the BE is evicted (out of window / unloaded) or rebuilt (its mesh changed);
     * {@code idx/uv/prim} are read by the hit shader every frame via the geometry table, so they must stay
     * alive while traced.
     */
    private static final class BeEntry {
        RtAccel accel;
        RtBuffer backing;                        // this entry's own AS backing
        RtBuffer geometry;                       // packed positions / indices / UVs / primitive data
        long indexAddr, uvAddr, primAddr;
        int[] bucketTris;
        int bx, by, bz;                          // block position (drives the per-frame instance transform)
        long meshHash;                           // hash of the captured mesh …rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window …for eviction
        float[] prevVerts;                       // block-local verts at this build, for the per-vertex MV diff
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();
    }

    /** One persistent updatable AS in an entity's ring: its own backing buffer + the topology it
     *  was built for (refit requires identical indices as well as counts) + refit bookkeeping. */
    private static final class EntitySlot {
        EntityAccel owner;
        RtAccel accel;
        RtBuffer backing;
        RtBuffer geometry;
        RtBuffer refitScratch;
        boolean updatable;
        int vertCount = -1;
        int triCount = -1;
        int[] bucketTris;
        int[] indices;
        int indexCount;
        long updateScratchSize;
        int updatesSinceBuild;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();
    }

    /** A per-entity ring of {@link EntitySlot}s, cycled one-per-frame so a refit never writes an AS still
     *  in flight, plus the last frame the entity was captured (drives eviction). Also holds the rigid-reuse
     *  reference: the entity-local mesh contents of the most recently written AS and the
     *  cache-owned shading buffers the geometry table points at on reuse frames. */
    private static final class EntityAccel {
        final EntitySlot[] ring = new EntitySlot[REFIT_RING];
        int cursor;
        long lastSeen;
        // Rigid-reuse reference (refAccel == null →?no reusable build yet). refVerts are the exact
        // positions the AS was last built/refit from; a frame whose capture is a rigid transform of them
        // reuses the AS through the TLAS instance transform. Reuse frames only READ the AS, so referencing
        // the last-written ring slot while it is in flight is safe.
        RtAccel refAccel;
        EntitySlot refSlot;
        float[] refVerts;
        int refVertCount = -1;
        int refIdxCount = -1;
        long refShadeHash;                      // rotation-invariant uv+prim hash (catches tint/sprite swaps)
        long refIndexAddr, refUvAddr, refPrimAddr;
        int[] refBucketTris;
        long retryYawFitAfter;
        float[] lastLocal;                      // local transform of the last emitted instance (throttled re-emit)
    }

    /** This frame's terrain and dynamic instance segments, entity BLAS builds, and geometry-table address. */
    public record FrameEntities(List<RtAccel.Instance> baseInstances, List<RtAccel.Instance> dynamicInstances,
                                List<RtAccel.PreparedBlas> blas, long geomTableAddr, FrameUse use) {
    }

    private record FrameUse(FrameLists lists, TableSlot table) {
    }

    private static final class TableSlot {
        final RtBuffer buffer;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        TableSlot(RtBuffer buffer) {
            this.buffer = buffer;
        }
    }

    /** One glowing entity's body mesh (rebased-space positions, copied out of {@link #capture} before the
     *  next entity resets it) plus its vanilla outline colour, for {@code RtGlowOutlineFeature}'s full-res raster
     *  mask pass. Captured as a side effect of the normal RT capture …no extra posing/animation work. */
    public record GlowEntity(float[] verts, int[] idx, int color) {
    }

    /** One visible name tag: display text + the attachment point's world position (rebase-space, same
     *  convention as entity capture …see {@link RtEntities#glowCamOffsetX()} for the camera-relative
     *  offset needed to finish the transform to camera-relative space). */
    public record NameTagEntity(Component text, float x, float y, float z) {
    }

    private record Motion(long dispAddr, float rigidX, float rigidY, float rigidZ) {
    }

    private static final class MotionSlice {
        long mapped;
        long deviceAddress;

        MotionSlice set(RtBuffer buffer, long offset) {
            this.mapped = buffer.mapped + offset;
            this.deviceAddress = buffer.deviceAddress + offset;
            return this;
        }
    }

    /** Host-visible storage pages owned by one frame-list slot and reused after its graphics token completes. */
    private static final class MotionArena {
        private final ArrayList<RtBuffer> pages = new ArrayList<>();
        private final IntArrayList lastUsedCycles = new IntArrayList();
        private final LongArrayList dirtyEnds = new LongArrayList();
        private final MotionSlice slice = new MotionSlice();
        private int pageIndex;
        private long offset;
        private int cycle;

        void reset() {
            cycle++;
            for (int i = pages.size() - 1; i >= 0; i--) {
                if (cycle - lastUsedCycles.getInt(i) >= MOTION_UNUSED_RETIRE_CYCLES) {
                    pages.remove(i).destroy();
                    lastUsedCycles.removeInt(i);
                    dirtyEnds.removeLong(i);
                }
            }
            pageIndex = 0;
            offset = 0L;
            for (int i = 0; i < dirtyEnds.size(); i++) {
                dirtyEnds.set(i, 0L);
            }
        }

        MotionSlice allocate(RtContext ctx, long bytes) {
            long size = Math.max(bytes, MOTION_ALIGNMENT);
            while (true) {
                if (pageIndex == pages.size()) {
                    long capacity = Math.max(MOTION_PAGE_BYTES, size);
                    pages.add(ctx.createBuffer(capacity,
                            org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                            "entity motion arena"));
                    lastUsedCycles.add(cycle);
                    dirtyEnds.add(0L);
                    RtFrameStats.FRAME.count("vmaBufferCreates", 1);
                    RtFrameStats.FRAME.count("entityVmaBufferCreates", 1);
                }
                RtBuffer page = pages.get(pageIndex);
                long aligned = alignUp(offset, MOTION_ALIGNMENT);
                if (size <= page.size - aligned) {
                    lastUsedCycles.set(pageIndex, cycle);
                    offset = Math.addExact(aligned, size);
                    dirtyEnds.set(pageIndex, Math.max(dirtyEnds.getLong(pageIndex), offset));
                    return slice.set(page, aligned);
                }
                pageIndex++;
                offset = 0L;
            }
        }

        /** Publish all sequential displacement writes with one VMA flush per used page. */
        void flushWrites() {
            for (int i = 0; i < dirtyEnds.size(); i++) {
                long bytes = dirtyEnds.getLong(i);
                if (bytes == 0L) {
                    continue;
                }
                pages.get(i).flush(0L, bytes);
                RtFrameStats.FRAME.count("entityMotionFlushes", 1);
            }
        }

        void destroy() {
            for (RtBuffer page : pages) {
                page.destroy();
            }
            pages.clear();
            lastUsedCycles.clear();
            dirtyEnds.clear();
        }
    }

    private static long alignUp(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    private record EntityGeometryLayout(long positionOffset, long indexOffset,
                                        long uvOffset, long primOffset,
                                        long logicalBytes, long totalBytes) {
        private static final long REGION_ALIGNMENT = 16L;

        static EntityGeometryLayout create(int positionFloats, int indexInts, int uvFloats, int primFloats) {
            long positionBytes = Math.multiplyExact((long) positionFloats, Float.BYTES);
            long indexBytes = Math.multiplyExact((long) indexInts, Integer.BYTES);
            long uvBytes = Math.multiplyExact((long) uvFloats, Float.BYTES);
            long primBytes = Math.multiplyExact((long) primFloats, Float.BYTES);
            long indexOffset = alignUp(positionBytes);
            long uvOffset = alignUp(Math.addExact(indexOffset, indexBytes));
            long primOffset = alignUp(Math.addExact(uvOffset, uvBytes));
            long totalBytes = alignUp(Math.addExact(primOffset, primBytes));
            long logicalBytes = Math.addExact(Math.addExact(positionBytes, indexBytes),
                    Math.addExact(uvBytes, primBytes));
            return new EntityGeometryLayout(0L, indexOffset, uvOffset, primOffset,
                    logicalBytes, totalBytes);
        }

        private static long alignUp(long value) {
            return Math.addExact(value, REGION_ALIGNMENT - 1L) & -REGION_ALIGNMENT;
        }

        EntityGeometryLayout shifted(long baseOffset) {
            if (baseOffset < 0L || baseOffset >= REGION_ALIGNMENT) {
                throw new IllegalArgumentException("Invalid entity geometry base offset: " + baseOffset);
            }
            return new EntityGeometryLayout(
                    Math.addExact(positionOffset, baseOffset),
                    Math.addExact(indexOffset, baseOffset), Math.addExact(uvOffset, baseOffset),
                    Math.addExact(primOffset, baseOffset), logicalBytes, Math.addExact(totalBytes, baseOffset));
        }
    }

    private static final class BeCandidate {
        BlockEntity be;
        double dist2;
        long posKey;

        void set(BlockEntity be, double dist2, long posKey) {
            this.be = be;
            this.dist2 = dist2;
            this.posKey = posKey;
        }
    }

    private static final Comparator<BeCandidate> BE_CANDIDATE_ORDER = (a, b) -> {
        int byDistance = Double.compare(a.dist2, b.dist2);
        return byDistance != 0 ? byDistance : Long.compare(a.posKey, b.posKey);
    };

    /** Reused per-frame lists; one slot is retired before it can be selected again. */
    private static final class FrameLists {
        final ArrayList<RtAccel.Instance> instances = new ArrayList<>(entityListCapacity());
        final ArrayList<RtAccel.PreparedBlas> blas = new ArrayList<>(entityListCapacity());
        final ArrayList<RtAccel.PreparedBlas> pooledBlas = new ArrayList<>(entityListCapacity());
        final ArrayList<RtBuffer> refitScratch = new ArrayList<>(entityListCapacity());
        final ArrayList<RtBuffer> buffers = new ArrayList<>(TRANSIENT_BUFFER_LIST_CAPACITY);
        final MotionArena motion = new MotionArena();
        final ArrayList<EntitySlot> usedEntitySlots = new ArrayList<>(entityListCapacity());
        final ArrayList<BeEntry> usedBlockEntities = new ArrayList<>();
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        void reset() {
            instances.clear();
            blas.clear();
            pooledBlas.clear();
            refitScratch.clear();
            buffers.clear();
            usedEntitySlots.clear();
            usedBlockEntities.clear();
            motion.reset();
        }

        void releaseDeferred() {
            for (RtAccel.PreparedBlas b : pooledBlas) {
                RtAccel.releaseEntityBlas(b);
            }
            for (RtBuffer s : refitScratch) {
                s.destroy();
            }
            for (RtBuffer buf : buffers) {
                buf.destroy();
            }
            instances.clear();
            blas.clear();
            pooledBlas.clear();
            refitScratch.clear();
            buffers.clear();
            usedEntitySlots.clear();
            usedBlockEntities.clear();
        }

        void destroyPersistent() {
            motion.destroy();
        }
    }

    /** Mutable per-frame build state shared by the entity + block-entity capture passes. */
    private final class FrameBuild {
        final List<RtAccel.Instance> base;
        FrameLists lists;
        List<RtAccel.Instance> instances;
        List<RtAccel.PreparedBlas> blas;        // all BLAS ops to record this frame (BUILD + refit UPDATE)
        List<RtAccel.PreparedBlas> pooledBlas;  // transient one-shot entity BLAS ops →?releaseEntityBlas
        List<RtBuffer> refitScratch;            // per-frame scratch from refit ops →?destroy() (AS persists)
        List<RtBuffer> buffers;                 // transient motion/particle buffers →?destroy()
        MotionArena motion;                     // suballocated entity/BE/particle displacement uploads
        long tableBase;
        long geomTableAddr;
        TableSlot table;
        int count;        // geometry-table entries / TLAS instances
        int logicalCount; // ordinary entities + block entities + individual particles

        final GraphicsUseWaiter graphicsUseWaiter;

        FrameBuild(List<RtAccel.Instance> base, RtGpuExecutor gpuExecutor) {
            this.base = base;
            this.graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        }

        boolean full() {
            return logicalCount >= maxEntities();
        }
    }

    /**
     * Capture this frame's model entities + block entities into per-object meshes/BLAS and merge them
     * with the terrain static instances. The caller (RtComposite) records the returned BLAS builds
     * before the TLAS build and pushes the geometry-table address. Returns terrain-only (no BLAS, addr 0)
     * when disabled or nothing captured. Dynamic entity coordinates are local and placed by TLAS instances;
     * particles remain captured rebase-relative with an identity instance.
     */
    public FrameEntities beginFrame(RtContext ctx, List<RtAccel.Instance> base, int rbx, int rby, int rbz,
                                    double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        // Frame-list/table/refit rings and deferred frees assume a slot is off the GPU after KEEP_FRAMES.
        ctx.gpuExecutor().awaitFramesInFlight(KEEP_FRAMES);
        boolean captureEntities=enabled();
        if (!captureEntities && dev.comfyfluffy.caustica.client.DhLodCapture.sectionCount()==0) {
            return new FrameEntities(base, List.of(), List.of(), 0L, null);
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return new FrameEntities(base, List.of(), List.of(), 0L, null);
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        setCamera(camX, camY, camZ, projection, viewRotation);

        FrameBuild build = new FrameBuild(base, ctx.gpuExecutor());
        if(captureEntities)dev.comfyfluffy.caustica.client.BetterPlayerModelCompat.beginWorldFrame(partial);
        if(captureEntities)RtEmfReplayScope.suspendLodForCapture();
        if(captureEntities)captureFirstPerson(ctx,build,mc,rbx,rby,rbz,camX,camY,camZ,projection,viewRotation);
        try {
            if(captureEntities) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.capture")) {
                captureEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blockEntities")) {
                captureBlockEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.particles")) {
                captureParticles(ctx, build, mc, partial, rbx, rby, rbz, projection, viewRotation);
                captureModelParticles(ctx,build,mc,partial,rbx,rby,rbz,projection,viewRotation);
            }
            }
        } catch (RuntimeException t) {
            // A partially recorded frame may already have installed unbuilt BLAS into persistent slots.
            // Quiesce old frames and drop the entity cache, then continue with terrain only for this frame.
            // This used to rethrow: RtComposite latched failed=true on any Throwable, so one item-pickup
            // particle whose render state threw switched path tracing off for the rest of the session.
            // Losing one frame of entities is recoverable; losing RT until restart is not.
            ctx.waitIdle();
            shutdown();
            CausticaMod.LOGGER.warn("Entity capture failed; continuing with terrain-only path tracing", t);
            return new FrameEntities(base, List.of(), List.of(), 0L, null);
        } catch (Error fatal) {
            ctx.waitIdle();
            shutdown();
            throw fatal;
        } finally {
            RtEmfReplayScope.restoreLodAfterCapture();
            dev.comfyfluffy.caustica.client.BetterPlayerModelCompat.endWorldFrame();
        }
        var dhLods=RtDhLods.INSTANCE.prepare(ctx,camX,camZ);
        RtDhLods.INSTANCE.recordPending(op->{beginBuildIfNeeded(ctx,build);build.blas.add(op);});
        long dhCoverageAddress=0;
        if(!dhLods.isEmpty()){
            beginBuildIfNeeded(ctx,build);var coverage=RtDhLods.INSTANCE.coverage();long offset=(long)tableCapacity*TABLE_ENTRY_BYTES;
            if(coverage.bytes()<=DH_COVERAGE_BYTES){coverage.write(MemoryUtil.memByteBuffer(build.tableBase+offset,coverage.bytes()),rbx,rby,rbz);build.table.buffer.flush(offset,coverage.bytes());dhCoverageAddress=build.geomTableAddr+offset;}
        }
        long dhMaterialAddress=dhLods.isEmpty()?0L:DhLodMaterials.INSTANCE.address(ctx);
        for(var lod:dhLods) {
            beginBuildIfNeeded(ctx,build);
            int n=lod.snapshot.quads().size();long a=lod.geometry.deviceAddress;
            writeTableEntry(build,a,0,0,RtDhLods.INSTANCE.counterAddress(ctx),0,0,0,new int[]{0,0},MASK_ALL);
            MemoryUtil.memPutLong(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+32,dhCoverageAddress);
            MemoryUtil.memPutLong(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+16,dhMaterialAddress); // uvAddr: unused by DH
            MemoryUtil.memPutInt(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+56,dev.comfyfluffy.caustica.client.DhLodTextures.slot());
            MemoryUtil.memPutInt(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+60,dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry.INSTANCE.requireSnapshot().waterId());
            build.instances.add(new RtAccel.Instance(translationTransform((float)((double)lod.snapshot.minBlockX()-rbx),lod.snapshot.minY()-rby,(float)((double)lod.snapshot.minBlockZ()-rbz)),lod.build.accel().deviceAddress,ENTITY_BIT|build.count,MASK_ALL,RtAccel.SBT_DH_OFFSET));
            build.count++;
        }
        evictStaleAccels(ctx);
        evictStaleBes(ctx);
        RtFrameStats.FRAME.count("entityRetainedGeometryBytes", retainedGeometryBytes);

        if (build.instances == null) {
            return new FrameEntities(base, List.of(), List.of(), 0L, null);
        }
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.uploadFlush")) {
            build.motion.flushWrites();
            if (build.count > 0) {
                build.table.buffer.flush(0L, (long) build.count * TABLE_ENTRY_BYTES);
                RtFrameStats.FRAME.count("entityTableFlushes", 1);
            }
        }
        return new FrameEntities(base, build.instances, build.blas, build.geomTableAddr,
                new FrameUse(build.lists, build.table));
    }

    /** Associate every resource returned for a successfully enqueued frame with its graphics completion. */
    public void markGraphicsUse(FrameEntities frame, GraphicsUse graphicsUse) {
        if (frame == null || frame.use == null) {
            return;
        }
        FrameLists lists = frame.use.lists;
        lists.graphicsUse.mark(graphicsUse);
        frame.use.table.graphicsUse.mark(graphicsUse);
        for (EntitySlot slot : lists.usedEntitySlots) {
            slot.graphicsUse.mark(graphicsUse);
        }
        for (BeEntry entry : lists.usedBlockEntities) {
            entry.graphicsUse.mark(graphicsUse);
        }
    }

    /** Capture animated entities (mobs, items, falling blocks) with per-object motion-vector displacement. */
    // One stable positive key reuses the original BLAS ring/refit; reject any real-entity collision.
    private final net.minecraft.client.renderer.state.level.WeatherRenderState weatherCapture=new net.minecraft.client.renderer.state.level.WeatherRenderState();
    private static final int HAND_ENTITY_KEY=Integer.MAX_VALUE;
    // Hand capture -> RT scene pipeline counters (RtFirstPerson.probe() only).
    public static long probeHandAttempts,probeHandDisabled,probeHandNull,probeHandEmpty,probeHandBuildFull,probeHandKeyCollide,probeHandTris,probeHandReused,probeHandFallback;
    /** Nanoseconds spent in the hand capture + upload path, and the frames it ran on. */
    public static long probeHandNanos,probeHandTimedFrames;
    public static void resetHandProbeCounters(){probeHandAttempts=0;probeHandDisabled=0;probeHandNull=0;probeHandEmpty=0;probeHandBuildFull=0;probeHandKeyCollide=0;probeHandTris=0;probeHandReused=0;probeHandFallback=0;probeHandNanos=0;probeHandTimedFrames=0;}
    private EntityPrev previousHand;
    private final Matrix4f handPrevViewRotation=new Matrix4f();
    private double handPrevCamX,handPrevCamY,handPrevCamZ;

    /** Displacement of {@link #capture}'s vertices as if fixed to the camera since the last hand frame. */
    private Motion cameraLockedHandMotion(RtContext ctx,FrameBuild build,float ex,float ey,float ez,
                                          double camX,double camY,double camZ,Matrix4f viewRotation){
        beginBuildIfNeeded(ctx,build);
        Matrix4f previousToWorld=new Matrix4f(handPrevViewRotation).invert();
        float mx=(float)(camX-handPrevCamX),my=(float)(camY-handPrevCamY),mz=(float)(camZ-handPrevCamZ);
        float[] verts=capture.verts.elements();
        int vc=capture.verts.size()/3;
        long bytes=(long)vc*4L*Float.BYTES;
        MotionSlice disp=build.motion.allocate(ctx,bytes);
        long out=disp.mapped;
        var v=new org.joml.Vector3f();
        for(int i=0;i<vc;i++) {
            float rx=verts[i*3]-ex,ry=verts[i*3+1]-ey,rz=verts[i*3+2]-ez;
            v.set(rx,ry,rz);
            viewRotation.transformDirection(v);
            previousToWorld.transformDirection(v);
            MemoryUtil.memPutFloat(out,rx+mx-v.x);
            MemoryUtil.memPutFloat(out+4,ry+my-v.y);
            MemoryUtil.memPutFloat(out+8,rz+mz-v.z);
            MemoryUtil.memPutFloat(out+12,0f);
            out+=16;
        }
        return new Motion(disp.deviceAddress,0f,0f,0f);
    }

    private void captureFirstPerson(RtContext ctx,FrameBuild build,Minecraft mc,int rbx,int rby,int rbz,
                                    double camX,double camY,double camZ,Matrix4f projection,Matrix4f viewRotation){
        long handProbeStart=System.nanoTime();
        if(RtFirstPerson.probe())probeHandAttempts++;
        if(!RtFirstPerson.enabled()){if(RtFirstPerson.probe())probeHandDisabled++;previousHand=null;return;}
        RtEntityCapture hand=RtFirstPerson.capture();
        if(hand==null || hand.isEmpty() || build.full() || mc.level.getEntity(HAND_ENTITY_KEY)!=null){
            if(RtFirstPerson.probe()){if(hand==null)probeHandNull++;else if(hand.isEmpty())probeHandEmpty++;else if(build.full())probeHandBuildFull++;else probeHandKeyCollide++;}
            previousHand=null;return;
        }
        capture.reset(hand.verts.size()/3);capture.verts.addAll(hand.verts);capture.idx.addAll(hand.idx);
        capture.uvList.addAll(hand.uvList);capture.prim.addAll(hand.prim);capture.alphaBuckets.addAll(hand.alphaBuckets);
        float ex=(float)(camX-rbx),ey=(float)(camY-rby),ez=(float)(camZ-rbz);
        var v=new org.joml.Vector3f();
        float nearest=Float.POSITIVE_INFINITY;
        // YSM can animate part of the empty hand behind the eye. A quad straddling w=0 would turn inside
        // out in the projection remap, so cut the hand at the eye plane first, exactly like raster does.
        HandProjection.clipNear(capture,viewRotation);
        if(capture.idx.isEmpty()){previousHand=null;return;}
        for(int i=0;i<capture.verts.size();i+=3) {
            v.set(capture.verts.getFloat(i),capture.verts.getFloat(i+1),capture.verts.getFloat(i+2));
            viewRotation.transformDirection(v);
            nearest=Math.min(nearest,-v.z);
        }
        float nearPush=HandProjection.nearPlanePush(nearest);
        if(nearPush>0f) {
            var correction=new org.joml.Vector3f(0f,0f,-nearPush);
            new Matrix4f(viewRotation).invert().transformDirection(correction);
            for(int i=0;i<capture.verts.size();i+=3) {
                capture.verts.set(i,capture.verts.getFloat(i)+correction.x);
                capture.verts.set(i+1,capture.verts.getFloat(i+1)+correction.y);
                capture.verts.set(i+2,capture.verts.getFloat(i+2)+correction.z);
            }
            nearest+=nearPush;
        }
        float depthScale=HandProjection.safeDepthScale(CausticaConfig.Rt.Visuals.HAND_DEPTH.value(),nearest);
        var handProjection=RtFirstPerson.projection();
        Matrix4f transform=handProjection!=null
            ?HandProjection.toWorld(projection,handProjection,viewRotation,ex,ey,ez)
            :HandProjection.toWorld(projection,viewRotation,mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.hudFov,
                (float)mc.getWindow().getWidth()/mc.getWindow().getHeight(),ex,ey,ez);
        transform.scale(depthScale);
        // A general projective map transforms planes, not just normal directions. Use a point on
        // each original primitive to supply d, then inverse-transpose the complete plane equation.
        var planeMatrix=new Matrix4f(transform).invert().transpose();
        var plane=new org.joml.Vector4f();
        for(int i=0;i<capture.prim.size();i+=12) {
            int vertex=capture.idx.getInt((i/12)*3)*3;
            float nx=capture.prim.getFloat(i),ny=capture.prim.getFloat(i+1),nz=capture.prim.getFloat(i+2);
            plane.set(nx,ny,nz,-(nx*capture.verts.getFloat(vertex)+ny*capture.verts.getFloat(vertex+1)+nz*capture.verts.getFloat(vertex+2)));
            planeMatrix.transform(plane);
            v.set(plane.x,plane.y,plane.z);
            if(v.lengthSquared()>1e-12f) v.normalize();
            capture.prim.set(i,v.x);capture.prim.set(i+1,v.y);capture.prim.set(i+2,v.z);
        }
        for(int i=0;i<capture.verts.size();i+=3) {
            transform.transformProject(capture.verts.getFloat(i),capture.verts.getFloat(i+1),capture.verts.getFloat(i+2),v);
            capture.verts.set(i,v.x);capture.verts.set(i+1,v.y);capture.verts.set(i+2,v.z);
        }
        // Near-plane protection alone can expand a Punchy pose into a wall. Retract the WHOLE
        // final animated group against real block collision boxes; never draw it above world depth.
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(int i=0;i<capture.verts.size();i+=3) {
            double x=capture.verts.getFloat(i)-ex,y=capture.verts.getFloat(i+1)-ey,z=capture.verts.getFloat(i+2)-ez;
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        var handBounds=new HandCollision.Bounds(minX,minY,minZ,maxX,maxY,maxZ);
        double retract=handBounds.reachScale(2.0); // bounded query, including extreme near-plane poses
        if(retract>0) {
            var query=new net.minecraft.world.phys.AABB(camX+Math.min(0,minX*retract),
                    camY+Math.min(0,minY*retract),camZ+Math.min(0,minZ*retract),
                    camX+Math.max(0,maxX*retract),camY+Math.max(0,maxY*retract),camZ+Math.max(0,maxZ*retract)).inflate(.015);
            for(var shape:mc.level.getBlockCollisions(mc.player,query)) for(var box:shape.toAabbs()) {
                var expanded=box.inflate(.015);
                retract=HandCollision.firstContact(handBounds,new HandCollision.Bounds(
                        expanded.minX-camX,expanded.minY-camY,expanded.minZ-camZ,
                        expanded.maxX-camX,expanded.maxY-camY,expanded.maxZ-camZ),retract);
            }
        }
        if(retract<=1e-4) {previousHand=null;return;} // camera inside solid: no feasible visible hand space
        if(retract<1) for(int i=0;i<capture.verts.size();i+=3) {
            capture.verts.set(i,ex+(float)((capture.verts.getFloat(i)-ex)*retract));
            capture.verts.set(i+1,ey+(float)((capture.verts.getFloat(i+1)-ey)*retract));
            capture.verts.set(i+2,ez+(float)((capture.verts.getFloat(i+2)-ez)*retract));
        }
        // Clipping at the eye plane changes the vertex count as YSM arms animate, leaving no per-vertex history. The
        // hand then still rides the camera: without this RR reprojected it as world geometry and it smeared on turns.
        Motion motion=previousHand!=null && previousHand.size!=capture.verts.size()
                ?cameraLockedHandMotion(ctx,build,ex,ey,ez,camX,camY,camZ,viewRotation)
                :uploadVertexMotion(ctx,build,capture.verts,previousHand,rbx,rby,rbz);
        handPrevViewRotation.set(viewRotation);handPrevCamX=camX;handPrevCamY=camY;handPrevCamZ=camZ;
        previousHand=storeEntityPrev(previousHand,capture.verts,rbx,rby,rbz);
        if(RtFirstPerson.probe()){probeHandTris+=capture.idx.size()/3;probeHandTimedFrames++;probeHandNanos+=System.nanoTime()-handProbeStart;}
        boolean handReused=appendRigidReuse(ctx,build,motion,HAND_ENTITY_KEY,MASK_PRIMARY,0f,0f,0f);
        if(RtFirstPerson.probe()){if(handReused)probeHandReused++;else probeHandFallback++;}
        if(!handReused)
            appendCapture(ctx,build,motion,HAND_ENTITY_KEY,ENTITY_BIT,MASK_PRIMARY,translationTransform(0f,0f,0f));
        build.logicalCount++;
    }

    private void captureEntities(RtContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Entity cameraEntity = mc.getCameraEntity();
        // In first person the camera owner's own body must not block the primary camera ray, but it should
        // still appear in reflections / shadows / GI (so the player sees themselves in water as others would).
        // In F5 third person it renders fully, like any other entity.
        boolean firstPerson = dev.comfyfluffy.caustica.client.BetterPlayerModelCompat.realFirstPerson();
        curVerts.clear();
        glowBatches.clear();
        nameTagBatches.clear();
        boolean glow = glowEnabled();
        boolean nameTags = nameTagsEnabled();
        glowCamOffsetX = (float) (cameraState.pos.x - rbx);
        glowCamOffsetY = (float) (cameraState.pos.y - rby);
        glowCamOffsetZ = (float) (cameraState.pos.z - rbz);
        resetPoseStack(entityPoseStack);
        org.joml.Vector3fc camForward = mc.gameRenderer.mainCamera().forwardVector();
        int capturedThisFrame = 0;
        for (Entity entity : level.entitiesForRendering()) {
            // Over budget stops GEOMETRY, not name tags: a server board can easily sit past the cap, and
            // vanilla would still draw its text.
            boolean overBudget = build.full() || capturedThisFrame >= maxOrdinaryEntities();
            // Invisible entities are not "nothing to draw": vanilla still draws their name tag, and an
            // invisible armor stand still renders what it wears (server holograms: text-only stands, or
            // book/head/flags wired into a display). Only the body itself is hidden, which the vanilla
            // renderer already does from the render state, so it must reach submit() untouched.
            boolean invisible = entity.isInvisible();
            boolean meshWanted = true;
            if (invisible) {
                meshWanted = invisibleArmorStandWearsSomething(entity);
            }
            // A spectator camera has no body (vanilla still poses its head); filmed and free-cam shots must not
            // carry a shadow of the camera itself.
            if (entity == cameraEntity && entity.isSpectator()) {
                continue;
            }
            boolean firstPersonSelf = entity == cameraEntity && firstPerson;
            int mask = LightOwnerMask.receiver(firstPersonSelf ? MASK_SECONDARY : MASK_ALL, entity == mc.player);
            double ix;
            double iy;
            double iz;
            int id = entity.getId();
            EntityPrev prev = prevVerts.get(id);
            if (!firstPersonSelf && entity != mc.player && throttledThisFrame(entity, id, partial, camForward)
                    && reemitLast(ctx, build, entity, id, prev, mask, partial, rbx, rby, rbz)) {
                build.logicalCount++;
                RtFrameStats.FRAME.count("entityThrottled", 1);
                capturedThisFrame++;
                continue;
            }
            capture.reset(prev != null ? prev.size / 3 : 0);
            try (var mobRenderScope=RtMobRenderScope.open(entity)) {
                EntityRenderState state;
                long extractStart = RtFrameStats.FRAME.startStage();
                try {
                    state = dispatcher.extractEntity(entity, partial);
                } finally {
                    RtFrameStats.FRAME.endStage("entity.capture.extract", extractStart);
                }
                // Derive placement from the extracted state so the submitted pose and TLAS anchor use the
                // same interpolation result.
                ix = state.x;
                iy = state.y;
                iz = state.z;
                // extractEntity already ran EntityRenderer.extractNameTags (shouldShowName, crosshair-look,
                // distance cutoff, the attachment point) as a normal part of building the render state …no
                // need to reimplement any of that here, just read the result. Name tags billboard to face
                // the camera every frame (see RtNameTagFeature), so …unlike glow, whose mesh is captured
                // straight into the SAME rigid entity mesh used for the BLAS …they are never mixed into
                // `capture`: doing so would make every frame's mesh a non-rigid transform of the last
                // whenever the camera turns, defeating rigid-reuse/motion-vector fitting for every
                // name-tagged entity. RtWorldOverlay renders them in a completely separate raster pass.
                if (nameTags && !firstPersonSelf && state.nameTag != null) {
                    captureNameTag(level, state, ix, iy, iz, rbx, rby, rbz);
                }
                // No geometry this frame: a body-only invisible entity (or a full budget). Its name tag was
                // already gathered above, and it never takes part in motion history because it never meshes.
                if (!meshWanted || overBudget) {
                    continue;
                }
                collector.begin(capture, true);
                dev.comfyfluffy.caustica.rt.RtHeldLights.beginPlayerCapture(entity == mc.player);
                resetPoseStack(entityPoseStack);
                // Capture around the entity anchor. Per-frame placement moves into the TLAS instance,
                // so ordinary world translation no longer changes the mesh or its float precision.
                long submitStart = RtFrameStats.FRAME.startStage();
                try {
                    dispatcher.submit(state, cameraState, 0.0, 0.0, 0.0, entityPoseStack, collector);
                } finally {
                    RtFrameStats.FRAME.endStage("entity.capture.submit", submitStart);
                }
            } catch (Throwable t) {
                // Fail loud instead of skip-and-limp: a capture throw here is almost always our bug, and
                // swallowing it leaves the entity invisible every frame plus a per-frame MC CrashReport.
                // Propagate to composite(), which logs the full trace, disables RT, and reverts to vanilla.
                throw new RuntimeException("RT entity capture failed", t);
            } finally {
                collector.begin(null, false);
                dev.comfyfluffy.caustica.rt.RtHeldLights.beginPlayerCapture(false);
                resetPoseStack(entityPoseStack);
            }
            if (capture.isEmpty()) {
                continue; // non-model entity (arrow/etc.) …no body geometry captured
            }
            if (glow && !firstPersonSelf) {
                // Vanilla never draws the local player's own body in first person (no model to outline …                // only the held-item hand), so it never shows the Glowing outline on yourself either. Our
                // capture still meshes the first-person self (for reflections/shadows/GI), so the glow mask
                // must explicitly skip it to match …otherwise it'd show an outline vanilla never would.
                int glowColor = collector.outlineColor();
                if (glowColor != 0) {
                    glowBatches.add(new GlowEntity(copyTranslatedVertices(capture.verts,
                            (float) (ix - rbx), (float) (iy - rby), (float) (iz - rbz)), capture.idx.toIntArray(), glowColor));
                }
            }
            // Motion vs last frame's posed mesh. New/topology-changed entities get one frame of camera-only
            // MV; rigid translation is packed into the table, deformation gets a disp buffer.
            Motion motion;
            long motionStart = RtFrameStats.FRAME.startStage();
            try {
                motion = uploadVertexMotion(ctx, build, capture.verts, prev, ix, iy, iz);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.motion", motionStart);
            }
            if (TRACE_ENTITY != null && entity.getType().toShortString().equals(TRACE_ENTITY)) traceEntity(entity, prev, ix, iy, iz, partial);
            curVerts.put(id, storeEntityPrev(prev, capture.verts, ix, iy, iz));
            // Rigid reuse first: a pose that is a rigid transform of the entity's last-built mesh
            // re-references that AS through the instance transform …no upload, no refit.
            boolean reused;
            long reuseStart = RtFrameStats.FRAME.startStage();
            try {
                reused = appendRigidReuse(ctx, build, motion, id, mask, (float) (ix - rbx), (float) (iy - rby), (float) (iz - rbz));
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.rigidReuse", reuseStart);
            }
            if (TRACE_ENTITY != null && entity.getType().toShortString().equals(TRACE_ENTITY)) {
                float[] tv = capture.verts.elements(); float maxY = -1e9f;
                for (int i = 1; i < capture.verts.size(); i += 3) maxY = Math.max(maxY, tv[i]);
                // Mesh heading: principal axis of the xz footprint (mod 180) vs the interpolated body yaw.
                double sx = 0, sz = 0, sxx = 0, szz = 0, sxz = 0; int vc = capture.verts.size() / 3;
                for (int i = 0; i < vc; i++) { double x = tv[i * 3], z = tv[i * 3 + 2]; sx += x; sz += z; }
                sx /= Math.max(1, vc); sz /= Math.max(1, vc);
                for (int i = 0; i < vc; i++) { double x = tv[i * 3] - sx, z = tv[i * 3 + 2] - sz; sxx += x * x; szz += z * z; sxz += x * z; }
                double meshYaw = Math.toDegrees(0.5 * Math.atan2(2 * sxz, sxx - szz));
                float bodyYaw = entity instanceof net.minecraft.world.entity.LivingEntity le ? net.minecraft.util.Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot) : entity.getYRot();
                CausticaMod.LOGGER.info("[ENTITY-REUSE] f={} id={} reused={} rigid={} maxY={} partial={} meshYaw={} bodyYaw={} capturesThisFrame={}", RtComposite.frameCounter(), id, reused, motion.dispAddr == 0L, maxY, partial,
                        String.format("%.1f", meshYaw), String.format("%.1f", bodyYaw), traceSeen.merge(id * 31L + RtComposite.frameCounter(), 1, Integer::sum));
            }
            if (!reused) {
                appendCapture(ctx, build, motion, id, ENTITY_BIT, mask,
                        translationTransform((float) (ix - rbx), (float) (iy - rby), (float) (iz - rbz)));
            }
            build.logicalCount++;
            RtFrameStats.FRAME.count("entitiesCaptured", 1);
            capturedThisFrame++;
        }
        Int2ObjectOpenHashMap<EntityPrev> oldPrev = prevVerts;
        prevVerts = curVerts;
        curVerts = oldPrev;
    }

    private static void resetPoseStack(PoseStack poseStack) {
        while (!poseStack.isEmpty()) {
            poseStack.popPose();
        }
        poseStack.setIdentity();
    }

    private static float[] copyTranslatedVertices(FloatArrayList local, float tx, float ty, float tz) {
        float[] placed = new float[local.size()];
        float[] src = local.elements();
        for (int i = 0; i < local.size(); i += 3) {
            placed[i] = src[i] + tx;
            placed[i + 1] = src[i + 1] + ty;
            placed[i + 2] = src[i + 2] + tz;
        }
        return placed;
    }

    /**
     * Gather one entity's name tag (world position + text) into {@link #nameTagBatches}, unless a block is
     * in the way. {@code state.nameTagAttachment} is only non-null when {@code state.nameTag} is (both set
     * together in {@code EntityRenderer.extractNameTags}). Positions are world-space (unrebased) until the
     * very end, matching {@code level.clip}'s coordinate space; the rebase subtraction happens last.
     *
     * <p>Vanilla draws name tags in the {@code seeThroughNameTags} phase, i.e. through walls; this matches
     * that (no occlusion cull, no separate ghost pass needed because the raster overlay never depth-tests
     * tags either).
     */
    /**
     * An invisible non-armor-stand entity contributes nothing but its name tag, so it never needs a mesh.
     * An invisible armor stand does: vanilla keeps rendering everything in its six equipment slots while
     * hiding the pose body. Everything empty is the server-hologram case (hundreds of them on a board),
     * and costs one extract for the tag instead of a mesh build, a BLAS record and a TLAS slot.
     */
    private static boolean invisibleArmorStandWearsSomething(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) {
            return false;
        }
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (!stand.getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void captureNameTag(ClientLevel level, EntityRenderState state, double ix, double iy, double iz,
                                 int rbx, int rby, int rbz) {
        Vec3 attach = state.nameTagAttachment;
        if (attach == null) {
            return;
        }
        double wx = ix + attach.x;
        double wy = iy + attach.y + 0.5;
        double wz = iz + attach.z;
        // The full-resolution overlay has no world depth attachment. Cull block-occluded tags here;
        // a short endpoint tolerance preserves tags mounted flush against their supporting block.
        {
            var obstruction = level.clip(new net.minecraft.world.level.ClipContext(cameraState.pos,
                    new Vec3(wx, wy, wz), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, Minecraft.getInstance().player));
            if (obstruction.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && obstruction.getLocation().distanceToSqr(new Vec3(wx, wy, wz)) > 0.01) return;
        }
        nameTagBatches.add(new NameTagEntity(state.nameTag,
                (float) (wx - rbx), (float) (wy - rby), (float) (wz - rbz)));
    }

    // Re-posing every entity every frame (EMF/Fresh Animations replay) is the largest CPU cost of a frame. Near,
    // outlined or name-tagged entities are never throttled; see throttledThisFrame for the rest.
    private int traceDumps;
    private final java.util.Map<Long, Integer> traceSeen = new java.util.HashMap<>();
    /** Test-only: lowest vertex x/z (a foot) as a leg-swing phase probe. */
    private static final java.util.Map<Integer, Integer> LEG_INDEX = new java.util.HashMap<>();
    private static String legProbe(int id, float[] v, int n) {
        Integer best = LEG_INDEX.get(id);
        if (best == null || best + 2 >= n) {
            int b = -1; float minY = Float.MAX_VALUE;
            for (int i = 1; i < n; i += 3) if (v[i] < minY - 1e-4f) { minY = v[i]; b = i - 1; }
            if (b < 0) return "";
            LEG_INDEX.put(id, b); best = b;
        }
        return String.format("%.4f/%.4f/%.4f", v[best], v[best + 1], v[best + 2]);
    }
    private static final String TRACE_ENTITY = System.getProperty("caustica.test.traceEntity");
    /** Test-only: per-frame pose change of one entity type (local verts vs last frame) to expose half-rate poses. */
    private void traceEntity(Entity entity, EntityPrev prev, double ix, double iy, double iz, float partial) {
        float[] v = capture.verts.elements();
        int n = capture.verts.size();
        double maxD = -1, cy = 0;
        for (int i = 1; i < n; i += 3) cy += v[i];
        cy /= Math.max(1, n / 3);
        if (prev != null && prev.size == n) {
            maxD = 0;
            for (int i = 0; i < n; i++) maxD = Math.max(maxD, Math.abs(v[i] - prev.verts[i]));
        }
        if (entity instanceof net.minecraft.world.entity.LivingEntity le) CausticaMod.LOGGER.info("[ENTITY-ROT] f={} id={} partial={} body={}/{} head={}/{} tickCount={} walkPos={} walkSpeed={} legY={}",
                RtComposite.frameCounter(), entity.getId(), partial, le.yBodyRotO, le.yBodyRot, le.yHeadRotO, le.yHeadRot, entity.tickCount,
                le.walkAnimation.position(partial), le.walkAnimation.speed(partial), legProbe(entity.getId(), capture.verts.elements(), capture.verts.size()));
        if (traceDumps < 12 && RtComposite.frameCounter() > 900 && Minecraft.getInstance().player != null && entity.distanceToSqr(Minecraft.getInstance().player) < 36) {
            traceDumps++;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(String.format("%.4f", v[i])).append(i + 1 < n ? "," : "");
            CausticaMod.LOGGER.info("[ENTITY-DUMP] f={} id={} v={}", RtComposite.frameCounter(), entity.getId(), sb);
        }
        CausticaMod.LOGGER.info("[ENTITY-TRACE] f={} id={} anchor={},{},{} centroidY={} poseDelta={} verts={} nanos={}",
                RtComposite.frameCounter(), entity.getId(), String.format("%.4f", ix), String.format("%.4f", iy),
                String.format("%.4f", iz), String.format("%.4f", cy), String.format("%.5f", maxD), n / 3, System.nanoTime());
    }

    private boolean throttledThisFrame(Entity entity, int id, float partial, org.joml.Vector3fc forward) {
        if (entity.isCurrentlyGlowing() || entity.shouldShowName() || entity.isVehicle() || entity.isPassenger()) return false;
        net.minecraft.world.phys.Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
        net.minecraft.world.phys.AABB box = entity.getBoundingBox();
        double dx = (box.minX + box.maxX) * 0.5 - eye.x, dy = (box.minY + box.maxY) * 0.5 - eye.y, dz = (box.minZ + box.maxZ) * 0.5 - eye.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        // Behind the view plane by more than the entity's own radius: never inside any FOV < 180 degrees.
        double radius = box.getSize() * 0.5 + 1.0;
        boolean behind = dx * forward.x() + dy * forward.y() + dz * forward.z() < -radius;
        // Pose refresh interval: a far mob is a few pixels, an off-screen one only reaches GI and reflections.
        // Its position stays exact every frame (reemitLast); only limb poses refresh less often. Scaled by the
        // frame rate, so a slow frame rate never drops the pose rate further; staggered by id to spread the load.
        double refreshMs;
        if (behind) {
            if (distSq < 6.0 * 6.0) return false;
            refreshMs = distSq < 24.0 * 24.0 ? 50.0 : 66.0;
        } else {
            if (distSq < 16.0 * 16.0) return false;
            refreshMs = distSq < 40.0 * 40.0 ? 33.0 : distSq < 80.0 * 80.0 ? 50.0 : 66.0;
        }
        int period = Math.clamp(Math.round(Minecraft.getInstance().getFps() * refreshMs / 1000.0), 1, 4);
        return Math.floorMod(RtComposite.frameCounter() + id, (long) period) != 0L;
    }

    /** Re-emit the entity's last built mesh at its current anchor (rigid motion only); false if none exists. */
    private boolean reemitLast(RtContext ctx, FrameBuild build, Entity entity, int id, EntityPrev prev, int mask,
                               float partial, int rbx, int rby, int rbz) {
        EntityAccel ea = entityAccels.get(id);
        if (ea == null || ea.refAccel == null || ea.lastLocal == null || prev == null || build.full()) return false;
        double ix = net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX());
        double iy = net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY());
        double iz = net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ());
        beginBuildIfNeeded(ctx, build);
        ea.lastSeen = RtComposite.frameCounter();
        if (ea.refSlot != null) build.lists.usedEntitySlots.add(ea.refSlot);
        writeTableEntry(build, ea.refPrimAddr, ea.refIndexAddr, ea.refUvAddr, 0L,
                (float) (ix - prev.anchorX), (float) (iy - prev.anchorY), (float) (iz - prev.anchorZ), ea.refBucketTris, mask);
        build.instances.add(new RtAccel.Instance(placeTransform(ea.lastLocal, (float) (ix - rbx), (float) (iy - rby), (float) (iz - rbz)),
                ea.refAccel.deviceAddress, ENTITY_BIT | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));
        build.count++;
        // Keep the posed vertices; only the anchor advances, so the next real capture diffs pose, not travel.
        prev.anchorX = ix;
        prev.anchorY = iy;
        prev.anchorZ = iz;
        curVerts.put(id, prev);
        return true;
    }

    /**
     * Upload this entity's world-space motion-vector displacement. Captures are entity-local, so the delta
     * is {@code (anchorCur + vertexCur) - (anchorPrev + vertexPrev)}. If every vertex agrees,
     * store it as a rigid vector in the geometry-table entry; otherwise write a per-vertex {@code vec4}
     * buffer directly, avoiding the old intermediate {@code float[]}.
     */
    private Motion uploadVertexMotion(RtContext ctx, FrameBuild build, FloatArrayList cur,
                                      EntityPrev prev, double anchorX, double anchorY, double anchorZ) {
        if (prev == null || prev.size != cur.size()) {
            return NO_MOTION;
        }
        float[] curVerts = cur.elements();
        float[] prevVerts = prev.verts;
        float sx = (float) (anchorX - prev.anchorX);
        float sy = (float) (anchorY - prev.anchorY);
        float sz = (float) (anchorZ - prev.anchorZ);
        int vc = cur.size() / 3;
        if (vc == 0) {
            return NO_MOTION;
        }

        float dx0 = (curVerts[0] - prevVerts[0]) + sx;
        float dy0 = (curVerts[1] - prevVerts[1]) + sy;
        float dz0 = (curVerts[2] - prevVerts[2]) + sz;
        boolean rigid = true;
        for (int i = 1; i < vc; i++) {
            float dx = (curVerts[i * 3]     - prevVerts[i * 3])     + sx;
            float dy = (curVerts[i * 3 + 1] - prevVerts[i * 3 + 1]) + sy;
            float dz = (curVerts[i * 3 + 2] - prevVerts[i * 3 + 2]) + sz;
            if (Math.abs(dx - dx0) > RIGID_DISP_EPS
                    || Math.abs(dy - dy0) > RIGID_DISP_EPS
                    || Math.abs(dz - dz0) > RIGID_DISP_EPS) {
                rigid = false;
                break;
            }
        }
        if (rigid) {
            return new Motion(0L, dx0, dy0, dz0);
        }

        beginBuildIfNeeded(ctx, build);
        long bytes = (long) vc * 4L * Float.BYTES;
        MotionSlice disp = build.motion.allocate(ctx, bytes);
        long out = disp.mapped;
        for (int i = 0; i < vc; i++) {
            MemoryUtil.memPutFloat(out, (curVerts[i * 3] - prevVerts[i * 3]) + sx);
            MemoryUtil.memPutFloat(out + 4, (curVerts[i * 3 + 1] - prevVerts[i * 3 + 1]) + sy);
            MemoryUtil.memPutFloat(out + 8, (curVerts[i * 3 + 2] - prevVerts[i * 3 + 2]) + sz);
            MemoryUtil.memPutFloat(out + 12, 0f);
            out += 16;
        }
        RtFrameStats.FRAME.count("entityMotionUploadBytes", bytes);
        return new Motion(disp.deviceAddress, 0f, 0f, 0f);
    }

    private static EntityPrev storeEntityPrev(EntityPrev prev, FloatArrayList cur,
                                              double anchorX, double anchorY, double anchorZ) {
        EntityPrev out = prev != null ? prev : new EntityPrev();
        int size = cur.size();
        if (out.verts.length < size) {
            out.verts = new float[size];
        }
        System.arraycopy(cur.elements(), 0, out.verts, 0, size);
        out.size = size;
        out.anchorX = anchorX;
        out.anchorY = anchorY;
        out.anchorZ = anchorZ;
        return out;
    }

    /**
     * Capture this frame's billboard particles as ONE combined mesh + BLAS (cutout, camera-only receiver),
     * with per-particle motion vectors. We iterate the LIVE {@code Particle} objects (via accessor mixins)
     * rather than the public packed render state, because only the live objects carry stable identity …     * needed to diff each particle's center against last frame for the MV. Each particle is extracted into
     * {@link #particleScratch} (its billboard quad), funneled through {@link #particleCapture} into the
     * shared {@code capture}, and its quad center cached by identity in {@link #particlePrev}. Per-layer
     * texture slot comes from the layer's atlas (block/item/particle) via the bindless registry. One
     * {@code PARTICLE_BIT} instance with mask {@link #PARTICLE_MASK} (primary-ray only).
     */
    private void captureParticles(RtContext ctx, FrameBuild build, Minecraft mc, float partial,
                                  int rbx, int rby, int rbz, Matrix4f projection, Matrix4f viewRotation) {
        billboardParticlesCaptured=0;
        int particleLimit = maxParticles();
        if (build.full()) {
            particlePrev.clear();
            particleCur.clear();
            return;
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) {
            return;
        }
        Map<ParticleRenderType, ParticleGroup<?>> groups =
                particlesEnabled() && particleLimit>0 ? ((ParticleEngineAccessor) mc.particleEngine).caustica$getParticleGroups() : java.util.Map.of();
        if (groups == null) groups = java.util.Map.of();
        capture.reset();
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_ANY_HIT;
        particleDisp.clear();
        // extract() emits camera-relative positions; shift them into rebased space (identity instance).
        Vec3 camPos = cam.position();
        particleCapture.setOffset((float) (camPos.x - rbx), (float) (camPos.y - rby), (float) (camPos.z - rbz));
        // Reject particles whose world-space bounds are wholly outside before paying extract/build-layer
        // cost. The center test after extraction retains the existing exact inclusion behavior for bounds
        // which intersect the frustum.
        Frustum frustum = new Frustum(viewRotation, projection);
        frustum.prepare(camPos.x, camPos.y, camPos.z);
        IdentityHashMap<Particle, ParticlePrev> cur = particleCur;
        cur.clear();
        int particlesCaptured = 0;
        try {
            particleGroups:
            for (ParticleGroup<?> group : groups.values()) {
                Queue<? extends Particle> queue = ((ParticleGroupAccessor) group).caustica$getParticles();
                for (Particle p : queue) {
                    if (build.full() || particlesCaptured >= particleLimit) {
                        break particleGroups;
                    }
                    if (!(p instanceof SingleQuadParticle sq)) {
                        continue; // item-pickup / elder-guardian particles aren't billboard quads (skip)
                    }
                    if (!frustum.isVisible(p.getBoundingBox())) {
                        continue;
                    }
                    int vb = capture.verts.size(), ib = capture.idx.size();
                    int ub = capture.uvList.size(), prb = capture.prim.size(), abb = capture.alphaBuckets.size();
                    int vertBefore = vb / 3;
                    particleCapture.rainStyle(p instanceof net.minecraft.client.particle.WaterDropParticle);
                    particleScratch.clear();
                    sq.extract(particleScratch, cam, partial);
                    for (SingleQuadParticle.Layer layer : particleScratch.layers()) {
                        capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(layer.textureAtlasLocation());
                        particleScratch.buildLayer(layer, particleCapture);
                        particleCapture.flush();
                    }
                    int vertAfter = capture.verts.size() / 3;
                    if (vertAfter == vertBefore) {
                        continue; // nothing captured for this particle
                    }
                    particleCenter(vertBefore, vertAfter, particleCenterScratch);
                    // pointInFrustum wants the world position: rebased center + rebase origin.
                    if (!frustum.pointInFrustum(particleCenterScratch[0] + rbx, particleCenterScratch[1] + rby, particleCenterScratch[2] + rbz)) {
                        capture.verts.size(vb); // off-screen →?truncate this particle back out (clean quad boundary)
                        capture.idx.size(ib);
                        capture.uvList.size(ub);
                        capture.prim.size(prb);
                        capture.alphaBuckets.size(abb);
                        continue;
                    }
                    appendParticleMv(p, particleCenterScratch, vertBefore, vertAfter, rbx, rby, rbz, cur);
                    build.logicalCount++;
                    particlesCaptured++;
                }
            }
        } catch (Throwable t) {
            capture.reset();
            particleDisp.clear();
            throw new RuntimeException("RT particle capture failed", t); // propagate to composite() (see entity path)
        }
        // LevelRenderer cancellation also skipped precipitation, which is not a ParticleEngine group.
        // Reuse vanilla biome/roof/snow selection and animated column UVs in the existing particle mesh.
        if(mc.level.getRainLevel(partial)>0 && !build.full()) {
            var renderer=mc.levelRenderer.weatherEffectRenderer();
            weatherCapture.reset();renderer.extractRenderState(mc.level,partial,camPos,weatherCapture);
            int start=capture.verts.size()/3;
            var invoker=(dev.comfyfluffy.caustica.mixin.WeatherEffectRendererInvoker)(Object)renderer;
            capture.currentAlphaBucket=RtAccel.ENTITY_BUCKET_ANY_HIT;capture.clearUvRemap();capture.currentOrder=0;
            capture.currentTexSlot=RtEntityTextures.INSTANCE.slotForAtlas(net.minecraft.resources.Identifier.withDefaultNamespace("textures/environment/rain.png"));
            particleCapture.rainStyle(true);
            particleCapture.weatherStyle(true,camPos.x,camPos.y,camPos.z,net.minecraft.util.Util.getMillis()/1000.0);
            invoker.caustica$renderInstances(particleCapture,weatherCapture.rainColumns,camPos,1f,weatherCapture.radius,weatherCapture.intensity);
            particleCapture.flush();particleCapture.rainStyle(false);
            particleCapture.weatherStyle(false,0,0,0,0);
            capture.currentTexSlot=RtEntityTextures.INSTANCE.slotForAtlas(net.minecraft.resources.Identifier.withDefaultNamespace("textures/environment/snow.png"));
            invoker.caustica$renderInstances(particleCapture,weatherCapture.snowColumns,camPos,.8f,weatherCapture.radius,weatherCapture.intensity);
            particleCapture.flush();
            for(int i=start;i<capture.verts.size()/3;i++) {particleDisp.add(0);particleDisp.add(0);particleDisp.add(0);particleDisp.add(0);}
            RtFrameStats.FRAME.count("weatherColumnsCaptured",weatherCapture.rainColumns.size()+weatherCapture.snowColumns.size());
        }
        particleCapture.rainStyle(false);
        billboardParticlesCaptured=particlesCaptured;
        RtFrameStats.FRAME.count("particlesCaptured", particlesCaptured);
        IdentityHashMap<Particle, ParticlePrev> oldPrev = particlePrev;
        particlePrev = cur;
        particleCur = oldPrev;
        if (capture.isEmpty()) {
            return;
        }
        long dispAddr = uploadDisp(ctx, build, particleDisp);
        appendCapture(ctx, build, new Motion(dispAddr, 0f, 0f, 0f),
                -1, PARTICLE_BIT, PARTICLE_MASK, IDENTITY); // one combined mesh, per-particle MV
    }

    /** Vanilla has two model-particle groups in addition to billboard quads: pickup and elder guardian.
     * Reuse their own extracted render states so vanilla interpolation, pose and texture selection survive.
     * NoRenderParticleGroup is intentionally invisible. Group extraction stays within the particle budget.
     */
    private void captureModelParticles(RtContext ctx,FrameBuild build,Minecraft mc,float partial,
            int rbx,int rby,int rbz,Matrix4f projection,Matrix4f viewRotation) {
        if(!particlesEnabled() || build.full())return;
        var cam=mc.gameRenderer.mainCamera();if(cam==null)return;
        var groups=((ParticleEngineAccessor)mc.particleEngine).caustica$getParticleGroups();
        if(groups==null)return;
        Frustum frustum=new Frustum(viewRotation,projection);var cp=cam.position();frustum.prepare(cp.x,cp.y,cp.z);
        int remaining=maxParticles()-billboardParticlesCaptured;
        for(var group:groups.values()) {
            if(!(group instanceof net.minecraft.client.particle.ItemPickupParticleGroup)
                    && !(group instanceof net.minecraft.client.particle.ElderGuardianParticleGroup))continue;
            if(group.isEmpty() || group.size()>remaining || build.full())continue;
            try {
                var state=group.extractRenderState(frustum,cam,partial);
                if(state==null)continue;
                capture.reset();collector.begin(capture,false);
                try{state.submit(collector,cameraState);}
                finally{collector.begin(null,false);}
                if(capture.isEmpty())continue;
                // A pickup animation can scale a quad to zero or produce a non-finite pose. Such geometry must
                // never reach a BLAS: a NaN vertex poisons the BVH and every later trace sees garbage.
                if(!hasFiniteGeometry())continue;
                var placement=translationTransform((float)(cp.x-rbx),(float)(cp.y-rby),(float)(cp.z-rbz));
                appendCapture(ctx,build,new Motion(0L,0f,0f,0f),-1,ENTITY_BIT,PARTICLE_MASK,placement);
                remaining-=group.size();build.logicalCount+=group.size();
                RtFrameStats.FRAME.count("modelParticlesCaptured",group.size());
            } catch(RuntimeException t) {
                // One malformed group must not discard the other groups or the whole entity frame.
                logModelParticleFailure(t);
            }
        }
    }

    /** Log the first few model-particle failures without flooding: one bad group can repeat every frame. */
    private void logModelParticleFailure(RuntimeException failure) {
        if (modelParticleFailures < 3) {
            CausticaMod.LOGGER.warn("Model particle capture skipped ({} of 3 logged): {}",
                    modelParticleFailures + 1, failure.toString());
        } else if (modelParticleFailures == 3) {
            CausticaMod.LOGGER.warn("Further model particle capture failures suppressed");
        }
        modelParticleFailures++;
    }

    /**
     * Reject geometry that cannot build a valid acceleration structure: no triangles, or any non-finite
     * position/UV. Dropping one particle for a frame is invisible; a NaN vertex corrupts the BLAS and the
     * whole world trace afterwards, which is exactly what a broken pickup animation can produce.
     */
    private boolean hasFiniteGeometry() {
        return finiteGeometry(capture.idx.size(), capture.verts.elements(), capture.verts.size(),
                capture.uvList.elements(), capture.uvList.size());
    }

    /** Package-visible so the rejection rule can be tested without a GPU or a live capture. */
    static boolean finiteGeometry(int indexCount, float[] verts, int vertexFloats,
            float[] uvs, int uvFloats) {
        if (indexCount < 3) {
            return false;
        }
        for (int i = 0; i < vertexFloats; i++) {
            if (!Float.isFinite(verts[i])) {
                return false;
            }
        }
        for (int i = 0; i < uvFloats; i++) {
            if (!Float.isFinite(uvs[i])) {
                return false;
            }
        }
        return true;
    }

    /** Average (rebase-space) position of a captured particle's verts …approximates the particle center. */
    private void particleCenter(int vertBefore, int vertAfter, float[] out) {
        float[] v = capture.verts.elements();
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = vertBefore; i < vertAfter; i++) {
            cx += v[i * 3];
            cy += v[i * 3 + 1];
            cz += v[i * 3 + 2];
        }
        int vc = vertAfter - vertBefore;
        out[0] = cx / vc;
        out[1] = cy / vc;
        out[2] = cz / vc;
    }

    /**
     * Compute one particle's motion-vector displacement (its quad center vs. last frame's, keyed by
     * identity) and write it for each of the particle's vertices into {@link #particleDisp}. All four
     * billboard verts share the center displacement (per-particle-rigid MV).
     */
    private void appendParticleMv(Particle p, float[] center, int vertBefore, int vertAfter,
                                  int rbx, int rby, int rbz, IdentityHashMap<Particle, ParticlePrev> cur) {
        ParticlePrev prev = particlePrev.remove(p);
        // World displacement = (curCenter −prevCenter) + (rebaseCur −rebasePrev). New particle ???0 (no MV).
        float dx = prev == null ? 0f : (center[0] - prev.cx) + (rbx - prev.rbx);
        float dy = prev == null ? 0f : (center[1] - prev.cy) + (rby - prev.rby);
        float dz = prev == null ? 0f : (center[2] - prev.cz) + (rbz - prev.rbz);
        for (int i = vertBefore; i < vertAfter; i++) {
            particleDisp.add(dx);
            particleDisp.add(dy);
            particleDisp.add(dz);
            particleDisp.add(0f);
        }
        if (prev == null) {
            prev = new ParticlePrev();
        }
        prev.set(center[0], center[1], center[2], rbx, rby, rbz);
        cur.put(p, prev);
    }

    /**
     * Capture block entities (chests, signs, …. Each BE keeps a cached mesh + BLAS keyed by BlockPos.
     * Every frame the BE is re-meshed (cheap) and its mesh hashed; the expensive BLAS is rebuilt only when
     * the mesh actually changed …so static BEs cost no GPU work while animating ones (chest lid, spawner,
     * … rebuild every frame and stay smooth. New/changed rebuilds are capped at {@link
     * #BE_BUILDS_PER_FRAME} per frame so a burst of newly loaded chunks can't stall; over-budget BEs keep
     * their last geometry / pop in over later frames. Captured block-local →?placed by a translate-only
     * instance transform; static, so the MV is 0.
     */
    private void captureBlockEntities(RtContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        beBuildsThisFrame = 0;
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        beDispatcher.prepare(cameraState.pos); // sets the camera for shouldRender / extract
        long now = RtComposite.frameCounter();
        int pcx = rbx >> 4, pcz = rbz >> 4;
        Vec3 cam = cameraState.pos;
        List<BeCandidate> candidates = beCandidates;
        for (int i = 0; i < candidates.size(); i++) {
            BeCandidate candidate = candidates.get(i);
            candidate.be = null;
            beCandidatePool.addLast(candidate);
        }
        candidates.clear();
        int viewChunks = beViewChunks();
        for (int cx = pcx - viewChunks; cx <= pcx + viewChunks; cx++) {
            for (int cz = pcz - viewChunks; cz <= pcz + viewChunks; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz) || !(level.getChunk(cx, cz) instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    double dx = p.getX() + 0.5 - cam.x;
                    double dy = p.getY() + 0.5 - cam.y;
                    double dz = p.getZ() + 0.5 - cam.z;
                    BeCandidate candidate = beCandidatePool.pollFirst();
                    if (candidate == null) {
                        candidate = new BeCandidate();
                    }
                    candidate.set(be, dx * dx + dy * dy + dz * dz, p.asLong());
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.size() > 1) {
            candidates.sort(BE_CANDIDATE_ORDER);
        }
        int firstBlockEntity = build.count;
        for (BeCandidate candidate : candidates) {
            if (build.full() || build.count - firstBlockEntity >= maxBlockEntities()) {
                break;
            }
            updateBlockEntity(ctx, build, beDispatcher, candidate.be, partial, now, rbx, rby, rbz);
        }
    }

    /** Re-mesh one block entity; rebuild its cached BLAS only if the mesh changed (budgeted); then emit it. */
    private void updateBlockEntity(RtContext ctx, FrameBuild build, BlockEntityRenderDispatcher beDispatcher,
                                   BlockEntity be, float partial, long now, int rbx, int rby, int rbz) {
        capture.reset();
        try {
            BlockEntityRenderState state = RtPortalCapture.extract(beDispatcher, be, partial, cameraState.pos);
            if (state == null) {
                return; // off-screen-only (beacon/end-gateway), distance-culled, or no renderer
            }
            collector.begin(capture, false);
            // Identity pose ???block-local mesh; world placement is the per-frame instance transform in emitBe.
            resetPoseStack(blockEntityPoseStack);
            beDispatcher.submit(state, blockEntityPoseStack, collector, cameraState);
        } catch (Throwable t) {
            throw new RuntimeException("RT block-entity capture failed", t); // propagate to composite() (see entity path)
        } finally {
            resetPoseStack(blockEntityPoseStack);
            collector.begin(null, false);
        }
        if (capture.isEmpty()) {
            return;
        }
        long key = be.getBlockPos().asLong();
        BeEntry entry = beCache.get(key);
        if (entry != null) {
            entry.lastSeen = now;
        }
        long hash = meshHash();
        FloatArrayList disp = null; // static unless the mesh changed this frame (chest lid / spawner animation)
        if (entry == null || entry.meshHash != hash) {
            // Geometry changed (or new BE) →?rebuild, but only within this frame's budget. Over budget: keep
            // showing the previous geometry; a brand-new BE simply pops in over the next frames.
            if (beBuildsThisFrame >= beBuildsPerFrame()) {
                if (entry != null) {
                    emitBe(ctx, build, entry, null, rbx, rby, rbz); // over budget: keep last geometry, no MV
                }
                return;
            }
            // Per-vertex MV from the previous build's block-local mesh (same vertex count ???pairable).
            // The BE itself doesn't move, so the world displacement is the pure local delta.
            if (entry != null && entry.prevVerts != null && entry.prevVerts.length == capture.verts.size()) {
                disp = VertexDisplacements.fill(capture.verts.elements(), capture.verts.size(), entry.prevVerts, beDispScratch);
            }
            BeEntry rebuilt = buildBe(ctx, build, be, hash, entry == null ? null : entry.prevVerts);
            rebuilt.lastSeen = now;
            if (entry != null) {
                retireBe(ctx, entry);
            }
            beCache.put(key, rebuilt);
            entry = rebuilt;
        }
        emitBe(ctx, build, entry, disp, rbx, rby, rbz);
    }

    /** Upload the already-captured BE mesh to fresh buffers and build its BLAS (block-local). */
    private BeEntry buildBe(RtContext ctx, FrameBuild build, BlockEntity be, long hash, float[] previousStorage) {
        beginBuildIfNeeded(ctx, build);
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        int idxCount = packed.indices().size();
        EntityGeometryLayout layout = EntityGeometryLayout.create(capture.verts.size(), idxCount,
                capture.uvList.size(), packed.primitives().size());
        BlockPos p = be.getBlockPos();
        String label = "block entity " + p.getX() + "," + p.getY() + "," + p.getZ();
        long required = Math.addExact(layout.totalBytes, EntityGeometryLayout.REGION_ALIGNMENT - 1L);
        RtBuffer geometry = allocBuffer(ctx, required, asInput | storage, true, label + " geometry");
        layout = layout.shifted((-geometry.deviceAddress) & (EntityGeometryLayout.REGION_ALIGNMENT - 1L));
        MemoryUtil.memFloatBuffer(geometry.mapped + layout.positionOffset, capture.verts.size())
                .put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(geometry.mapped + layout.indexOffset, idxCount)
                .put(packed.indices().elements(), 0, idxCount);
        MemoryUtil.memFloatBuffer(geometry.mapped + layout.uvOffset, capture.uvList.size())
                .put(capture.uvList.elements(), 0, capture.uvList.size());
        MemoryUtil.memFloatBuffer(geometry.mapped + layout.primOffset, packed.primitives().size())
                .put(packed.primitives().elements(), 0, packed.primitives().size());
        geometry.flush(layout.positionOffset, layout.totalBytes - layout.positionOffset);

        long positionAddr = Math.addExact(geometry.deviceAddress, layout.positionOffset);
        long indexAddr = Math.addExact(geometry.deviceAddress, layout.indexOffset);
        long uvAddr = Math.addExact(geometry.deviceAddress, layout.uvOffset);
        long primAddr = Math.addExact(geometry.deviceAddress, layout.primOffset);
        // The cached mesh is replaced rather than updated in place, so build without ALLOW_UPDATE.
        RtAccel.PersistentBuild pb = RtAccel.preparePersistentEntityBlasBuild(ctx, positionAddr, vertCount,
                indexAddr, packed.bucketTris(), label + " BLAS");
        build.blas.add(pb.op());
        build.refitScratch.add(pb.scratch());
        beBuildsThisFrame++;

        BeEntry e = new BeEntry();
        e.accel = pb.accel();
        e.backing = pb.backing();
        e.geometry = geometry;
        e.indexAddr = indexAddr;
        e.uvAddr = uvAddr;
        e.primAddr = primAddr;
        e.bucketTris = packed.copyBucketTris();
        e.bx = p.getX();
        e.by = p.getY();
        e.bz = p.getZ();
        e.meshHash = hash;
        // Retain this build's block-local verts so the next rebuild can diff against them for the MV.
        int vertexFloats = capture.verts.size();
        e.prevVerts = previousStorage != null && previousStorage.length == vertexFloats
            ? previousStorage : new float[vertexFloats];
        System.arraycopy(capture.verts.elements(),0,e.prevVerts,0,vertexFloats);
        return e;
    }

    /** FNV-1a hash of the currently captured mesh (positions + indices + per-prim data) for rebuild detection. */
    private long meshHash() {
        long h = 1469598103934665603L;
        float[] v = capture.verts.elements();
        int vn = capture.verts.size();
        for (int i = 0; i < vn; i++) {
            h = (h ^ (Float.floatToRawIntBits(v[i]) & 0xffffffffL)) * 1099511628211L;
        }
        int[] x = capture.idx.elements();
        int xn = capture.idx.size();
        for (int i = 0; i < xn; i++) {
            h = (h ^ (x[i] & 0xffffffffL)) * 1099511628211L;
        }
        // Scrolling textures animate even when the geometry remains completely stationary.
        {
            float[] uv = capture.uvList.elements();
            for (int i = 0; i < capture.uvList.size(); i++) {
                h = (h ^ (Float.floatToRawIntBits(uv[i]) & 0xffffffffL)) * 1099511628211L;
            }
        }
        float[] pr = capture.prim.elements();
        int pn = capture.prim.size();
        for (int i = 0; i < pn; i++) {
            h = (h ^ (Float.floatToRawIntBits(pr[i]) & 0xffffffffL)) * 1099511628211L;
        }
        int[] buckets = capture.alphaBuckets.elements();
        for (int i = 0; i < capture.alphaBuckets.size(); i++) {
            h = (h ^ (buckets[i] & 0xffffffffL)) * 1099511628211L;
        }
        return h;
    }

    /** Emit a cached block entity into this frame: its geometry-table entry + a TLAS instance (no GPU build). */
    private void emitBe(RtContext ctx, FrameBuild build, BeEntry e, FloatArrayList disp, int rbx, int rby, int rbz) {
        if (build.full()) {
            return;
        }
        beginBuildIfNeeded(ctx, build);
        // disp is non-null only for a BE whose mesh changed this frame (chest lid / spawner); a static BE
        // passes null ???dispAddr 0 ???no MV. The disp buffer is a per-frame transient, so a BE that stops
        // animating reverts to MV 0 next frame.
        long dispAddr = uploadDisp(ctx, build, disp);
        writeTableEntry(build, e.primAddr, e.indexAddr, e.uvAddr, dispAddr, 0f, 0f, 0f, e.bucketTris, MASK_ALL);
        // Block-local mesh placed by a translate-only instance transform (blockPos −rebase), like terrain.
        float[] xform = {1, 0, 0, e.bx - rbx, 0, 1, 0, e.by - rby, 0, 0, 1, e.bz - rbz};
        build.instances.add(new RtAccel.Instance(xform, e.accel.deviceAddress,
                ENTITY_BIT | (build.count & 0x7FFFFF), 0xFF, RtAccel.SBT_ENTITY_OFFSET));
        build.count++;
        build.lists.usedBlockEntities.add(e);
        build.logicalCount++;
        RtFrameStats.FRAME.count("blockEntitiesCaptured", 1);
    }

    /** Retire a cached block entity's persistent AS + mesh buffers once its exact last graphics use completes. */
    private static void retireBe(RtContext ctx, BeEntry e) {
        RtAccel accel = e.accel;
        RtBuffer backing = e.backing;
        RtBuffer geometry = e.geometry;
        ctx.gpuExecutor().retireAfterGraphics(e.graphicsUse, () -> {
            RtAccel.destroyEntityAccel(accel, backing);
            geometry.destroy();
        });
        RtFrameStats.FRAME.count("entityBlockEntityRetirements", 1);
    }

    /** Drop cached block entities not seen (in window) within the last KEEP_FRAMES frames …unloaded/out of view. */
    private void evictStaleBes(RtContext ctx) {
        if (beCache.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        Iterator<Map.Entry<Long, BeEntry>> it = beCache.entrySet().iterator();
        while (it.hasNext()) {
            BeEntry e = it.next().getValue();
            if (now - e.lastSeen < KEEP_FRAMES) {
                continue;
            }
            retireBe(ctx, e);
            it.remove();
        }
    }

    // Vulkan requires buffer size > 0; a few zero-length captures (empty entity mesh, etc.) can otherwise
    // reach allocBuffer() with minSize == 0.
    private static final long MIN_BUFFER_SIZE = 256;

    /** Allocate one of this frame's ~6-per-entity VMA buffers (mesh/BLAS scratch), counted for RtFrameStats. */
    private RtBuffer allocBuffer(RtContext ctx, long minSize, int usage, boolean hostVisible, String label) {
        RtFrameStats.FRAME.count("vmaBufferCreates", 1);
        return ctx.createBuffer(Math.max(minSize, MIN_BUFFER_SIZE), usage, hostVisible, label);
    }

    private RtBuffer allocAlignedBuffer(RtContext ctx, long minSize, int usage, boolean hostVisible,
                                        String label, long addressAlignment) {
        RtFrameStats.FRAME.count("vmaBufferCreates", 1);
        return ctx.createAlignedBuffer(Math.max(minSize, MIN_BUFFER_SIZE), usage, hostVisible, label,
                addressAlignment);
    }

    /** Lazily initialise this frame's build (instance list seeded with terrain, fresh free-lists, table ring slot). */
    private void beginBuildIfNeeded(RtContext ctx, FrameBuild build) {
        if (build.instances != null) {
            return;
        }
        FrameLists lists = frameLists[(int) (RtComposite.frameCounter() % frameLists.length)];
        awaitGraphicsUse(build, lists.graphicsUse, "entityFrameListsWaits");
        lists.releaseDeferred();
        lists.reset();
        build.lists = lists;
        build.instances = lists.instances;
        build.blas = lists.blas;
        build.pooledBlas = lists.pooledBlas;
        build.refitScratch = lists.refitScratch;
        build.buffers = lists.buffers;
        build.motion = lists.motion;
        ensureResources(ctx);
        tableSlot = (tableSlot + 1) % TABLE_RING;
        build.table = tableRing[tableSlot];
        awaitGraphicsUse(build, build.table.graphicsUse, "entityTableWaits");
        build.tableBase = build.table.buffer.mapped;
        build.geomTableAddr = build.table.buffer.deviceAddress;
    }

    private static void awaitGraphicsUse(FrameBuild build, TrackedGraphicsUse graphicsUse, String counter) {
        long started = System.nanoTime();
        if (!build.graphicsUseWaiter.await(graphicsUse)) {
            return;
        }
        RtFrameStats.FRAME.count(counter, 1);
        RtFrameStats.FRAME.count("entityGraphicsWaitNanos", System.nanoTime() - started);
    }

    /**
     * Rigid-reuse fast path: if the current local-space {@link #capture} is identical to, or a rigid yaw
     * transform of, the mesh this entity's AS was last built from, emit a geometry-table entry pointing at
     * the cached shading buffers and a TLAS instance carrying the fitted transform over the cached AS …     * skipping the 4 mesh uploads and the BLAS refit. Covers still mobs, item frames, armor stands, and
     * spinning/bobbing dropped items. The hit shader rotates prim normals / TBN by the instance transform,
     * so rotated instances shade correctly. Motion vectors are untouched: {@code motion} was already
     * computed against last frame's capture (a rotating pose gets its per-vertex disp buffer as usual).
     * Returns false (caller takes the full path) when there is no reusable AS, the topology changed, the
     * pose is non-rigid (animation), or the shading data changed under identical topology.
     */
    private boolean appendRigidReuse(RtContext ctx, FrameBuild build, Motion motion, int entityId, int mask,
                                     float placeX, float placeY, float placeZ) {
        EntityAccel ea = entityAccels.get(entityId);
        if (ea == null || ea.refAccel == null
                || ea.refVertCount != capture.verts.size() / 3 || ea.refIdxCount != capture.idx.size()) {
            return false;
        }
        long equalStart = RtFrameStats.FRAME.startStage();
        boolean equal;
        try {
            equal = positionsBitwiseEqual(ea.refVerts, capture.verts.elements(), ea.refVertCount * 3);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.equal", equalStart);
        }
        float[] localTransform = IDENTITY;
        if (!equal) {
            long now = RtComposite.frameCounter();
            if (now < ea.retryYawFitAfter) {
                return false;
            }
            long yawStart = RtFrameStats.FRAME.startStage();
            try {
                localTransform = fitYawTransform(ea.refVerts, capture.verts.elements(), ea.refVertCount);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.yaw", yawStart);
            }
            if (localTransform == null) {
                RtFrameStats.FRAME.count("entityRigidFitFailures", 1);
                ea.retryYawFitAfter = now + 8L;
                return false;
            }
            RtFrameStats.FRAME.count("entityRigidFitSuccesses", 1);
            ea.retryYawFitAfter = 0L;
        }
        // Same topology + rigid pose, but tint/sprite/material lanes may still have changed (dyed sheep,
        // item frame content swap that kept counts). Compare the rotation-invariant shading hash.
        long shadeStart = RtFrameStats.FRAME.startStage();
        try {
            if (shadeHash() != ea.refShadeHash) {
                return false;
            }
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.shade", shadeStart);
        }
        beginBuildIfNeeded(ctx, build);
        ea.lastSeen = RtComposite.frameCounter();
        ea.lastLocal = localTransform;
        if (ea.refSlot == null) {
            throw new IllegalStateException("Rigid entity reuse lost its owning slot");
        }
        build.lists.usedEntitySlots.add(ea.refSlot);
        writeTableEntry(build, ea.refPrimAddr, ea.refIndexAddr, ea.refUvAddr,
                motion.dispAddr, motion.rigidX, motion.rigidY, motion.rigidZ, ea.refBucketTris, mask);
        if(entityId==HAND_ENTITY_KEY)MemoryUtil.memPutInt(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+60,1);
        build.instances.add(new RtAccel.Instance(placeTransform(localTransform, placeX, placeY, placeZ),
                ea.refAccel.deviceAddress,
                ENTITY_BIT | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));
        build.count++;
        RtFrameStats.FRAME.count("entityReuse", 1);
        return true;
    }

    /**
     * Fit {@code cur ???R_yaw·ref + t} in entity-local space. Exact local equality is handled before this
     * method; this slower centroid/yaw fit covers dropped-item spin and minecarts. Pitch/roll and skeletal
     * deformation take the full path.
     */
    private static float[] fitYawTransform(float[] ref, float[] cur, int vc) {
        // Centroid-align, then the least-squares rotation angle about Y for
        // x' = x·cos + z·sin, z' = −x·sin + z·cos is atan2(Σ(cx·rz −cz·rx), Σ(cx·rx + cz·rz)).
        float crx = 0f, cry = 0f, crz = 0f, ccx = 0f, ccy = 0f, ccz = 0f;
        for (int i = 0; i < vc; i++) {
            crx += ref[i * 3];
            cry += ref[i * 3 + 1];
            crz += ref[i * 3 + 2];
            ccx += cur[i * 3];
            ccy += cur[i * 3 + 1];
            ccz += cur[i * 3 + 2];
        }
        float inv = 1f / vc;
        crx *= inv; cry *= inv; crz *= inv;
        ccx *= inv; ccy *= inv; ccz *= inv;
        double a = 0.0, b = 0.0;
        for (int i = 0; i < vc; i++) {
            float rx = ref[i * 3] - crx, rz = ref[i * 3 + 2] - crz;
            float cx = cur[i * 3] - ccx, cz = cur[i * 3 + 2] - ccz;
            a += (double) cx * rx + (double) cz * rz;
            b += (double) cx * rz - (double) cz * rx;
        }
        float cos = (float) Math.cos(Math.atan2(b, a));
        float sin = (float) Math.sin(Math.atan2(b, a));
        for (int i = 0; i < vc; i++) {
            float rx = ref[i * 3] - crx, ry = ref[i * 3 + 1] - cry, rz = ref[i * 3 + 2] - crz;
            float ex = (rx * cos + rz * sin) - (cur[i * 3] - ccx);
            float ey = ry - (cur[i * 3 + 1] - ccy);
            float ez = (-rx * sin + rz * cos) - (cur[i * 3 + 2] - ccz);
            if (Math.abs(ex) > RIGID_FIT_EPS || Math.abs(ey) > RIGID_FIT_EPS || Math.abs(ez) > RIGID_FIT_EPS) {
                return null;
            }
        }
        // p_out = R·(p −cr) + cc  ??? t = cc −R·cr.
        float rcx = crx * cos + crz * sin;
        float rcz = -crx * sin + crz * cos;
        return new float[] {
                cos, 0, sin, ccx - rcx,
                0,   1, 0,   ccy - cry,
                -sin, 0, cos, ccz - rcz};
    }

    private static boolean positionsBitwiseEqual(float[] a, float[] b, int size) {
        // Vectorized range compare; it differs from raw-bit equality only for NaN payloads, which never
        // occur in captured vertex positions (a NaN would already break the BLAS).
        return java.util.Arrays.equals(a, 0, size, b, 0, size);
    }

    private static float[] translationTransform(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    private static float[] placeTransform(float[] local, float x, float y, float z) {
        if (local == IDENTITY) {
            return translationTransform(x, y, z);
        }
        return new float[] {
                local[0], local[1], local[2], local[3] + x,
                local[4], local[5], local[6], local[7] + y,
                local[8], local[9], local[10], local[11] + z};
    }

    /**
     * FNV-1a over the capture's shading data that must match for AS reuse: UVs plus each prim record's
     * emission/tint/material lanes. Prim NORMALS are deliberately excluded …they rotate with the pose,
     * and the hit shader re-rotates the cached ones via the instance transform.
     */
    private long shadeHash() {
        // Equality key only (compared with a value produced by this same function). Two floats per 64-bit
        // step on four independent lanes: the serial FNV chain over every UV/prim float was a per-entity,
        // per-frame render-thread hotspot.
        long h0 = 0x9E3779B97F4A7C15L, h1 = 0xC2B2AE3D27D4EB4FL, h2 = 0x165667B19E3779F9L, h3 = 0x27D4EB2F165667C5L;
        float[] uv = capture.uvList.elements();
        int un = capture.uvList.size();
        int i = 0;
        for (; i + 8 <= un; i += 8) {
            h0 = mixLane(h0, pairBits(uv[i], uv[i + 1]));
            h1 = mixLane(h1, pairBits(uv[i + 2], uv[i + 3]));
            h2 = mixLane(h2, pairBits(uv[i + 4], uv[i + 5]));
            h3 = mixLane(h3, pairBits(uv[i + 6], uv[i + 7]));
        }
        for (; i < un; i++) h0 = mixLane(h0, Float.floatToRawIntBits(uv[i]) & 0xffffffffL);
        float[] pr = capture.prim.elements();
        int pn = capture.prim.size();
        for (int base = 0; base < pn; base += 12) {
            // skip normal.xyz (0..2); keep emission(3), tint(4..7), mat(8..11)
            h0 = mixLane(h0, pairBits(pr[base + 3], pr[base + 4]));
            h1 = mixLane(h1, pairBits(pr[base + 5], pr[base + 6]));
            h2 = mixLane(h2, pairBits(pr[base + 7], pr[base + 8]));
            h3 = mixLane(h3, pairBits(pr[base + 9], pr[base + 10]) ^ ((long) Float.floatToRawIntBits(pr[base + 11]) << 17));
        }
        int[] buckets = capture.alphaBuckets.elements();
        for (int b = 0; b < capture.alphaBuckets.size(); b++) h0 = mixLane(h0, buckets[b] & 0xffffffffL);
        long h = mixLane(mixLane(mixLane(h0, h1), h2), h3);
        return mixLane(h, ((long) un << 32) ^ pn);
    }

    private static long pairBits(float a, float b) {
        return (Float.floatToRawIntBits(a) & 0xffffffffL) | ((long) Float.floatToRawIntBits(b) << 32);
    }

    private static long mixLane(long h, long v) {
        h = (h ^ v) * 0xFF51AFD7ED558CCDL;
        return h ^ (h >>> 31);
    }

    /**
     * Upload the current {@link #capture} as a per-object mesh + BLAS, add its instance + geom-table entry.
     * {@code entityId} ???0 →?refit path (persistent updatable AS keyed by id); {@code < 0} (refit disabled)
     * →?transient one-shot full BUILD. Used by the animated-entity pass; block entities use {@link #buildBe}.
     */
    private void appendCapture(RtContext ctx, FrameBuild build, float[] disp, int entityId, int instanceBit, int mask) {
        beginBuildIfNeeded(ctx, build);
        appendCapture(ctx, build, new Motion(uploadDisp(ctx, build, disp), 0f, 0f, 0f),
                entityId, instanceBit, mask, IDENTITY);
    }

    private void appendCapture(RtContext ctx, FrameBuild build, Motion motion, int entityId, int instanceBit, int mask,
                               float[] instanceTransform) {
        beginBuildIfNeeded(ctx, build);
        if (entityId >= 0) {
            appendPackedEntity(ctx, build, motion, entityId, instanceBit, mask, instanceTransform);
            return;
        }
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        int idxCount = packed.indices().size();
        EntityGeometryLayout layout = EntityGeometryLayout.create(capture.verts.size(), idxCount,
                capture.uvList.size(), packed.primitives().size());
        long required = Math.addExact(layout.totalBytes, EntityGeometryLayout.REGION_ALIGNMENT - 1L);
        RtBuffer geometry = allocBuffer(ctx, required, asInput | storage, true, "particle geometry");
        layout = layout.shifted((-geometry.deviceAddress) & (EntityGeometryLayout.REGION_ALIGNMENT - 1L));
        MemoryUtil.memFloatBuffer(geometry.mapped + layout.positionOffset, capture.verts.size())
                .put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(geometry.mapped + layout.indexOffset, idxCount)
                .put(packed.indices().elements(), 0, idxCount);
        MemoryUtil.memFloatBuffer(geometry.mapped + layout.uvOffset, capture.uvList.size())
                .put(capture.uvList.elements(), 0, capture.uvList.size());
        MemoryUtil.memFloatBuffer(geometry.mapped + layout.primOffset, packed.primitives().size())
                .put(packed.primitives().elements(), 0, packed.primitives().size());
        geometry.flush(layout.positionOffset, layout.totalBytes - layout.positionOffset);

        long positionAddr = Math.addExact(geometry.deviceAddress, layout.positionOffset);
        long indexAddr = Math.addExact(geometry.deviceAddress, layout.indexOffset);
        long uvAddr = Math.addExact(geometry.deviceAddress, layout.uvOffset);
        long primAddr = Math.addExact(geometry.deviceAddress, layout.primOffset);

        RtAccel.PreparedBlas blas = RtAccel.prepareEntityBlas(ctx, positionAddr, vertCount, indexAddr, packed.bucketTris(),
                "particle BLAS");
        build.blas.add(blas);
        build.pooledBlas.add(blas);

        writeTableEntry(build, primAddr, indexAddr, uvAddr, motion.dispAddr,
                motion.rigidX, motion.rigidY, motion.rigidZ, packed.bucketTris(), mask);
        if(entityId==HAND_ENTITY_KEY)MemoryUtil.memPutInt(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+60,1);

        build.instances.add(new RtAccel.Instance(instanceTransform, blas.accel.deviceAddress,
                instanceBit | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));
        build.buffers.add(geometry);
        build.count++;
    }

    /** Pack one changed entity's four logical geometry regions into its retired ring slot's backing. */
    private void appendPackedEntity(RtContext ctx, FrameBuild build, Motion motion, int entityId,
                                    int instanceBit, int mask, float[] instanceTransform) {
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        int idxCount = packed.indices().size();
        EntityGeometryLayout layout = EntityGeometryLayout.create(capture.verts.size(), idxCount,
                capture.uvList.size(), packed.primitives().size());

        EntitySlot slot;
        RtBuffer geometry;
        long allocStart = RtFrameStats.FRAME.startStage();
        try {
            slot = selectEntityBuildSlot(ctx, build, entityId);
            build.lists.usedEntitySlots.add(slot);
            long required = Math.addExact(layout.totalBytes, EntityGeometryLayout.REGION_ALIGNMENT - 1L);
            geometry = slot.geometry;
            if (geometry == null || geometry.size < required) {
                RtBuffer old = geometry;
                long capacity = old == null ? required : growCapacity(old.size, required);
                geometry = allocBuffer(ctx, capacity, asInput | storage, true, "entity geometry");
                slot.geometry = geometry;
                retainedGeometryBytes = Math.addExact(retainedGeometryBytes, geometry.size);
                RtFrameStats.FRAME.count("entityVmaBufferCreates", 1);
                if (old != null) {
                    retainedGeometryBytes = Math.subtractExact(retainedGeometryBytes, old.size);
                    old.destroy();
                }
            } else {
                RtFrameStats.FRAME.count("entityGeometryBufferReuses", 1);
            }
            layout = layout.shifted((-geometry.deviceAddress) & (EntityGeometryLayout.REGION_ALIGNMENT - 1L));
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.append.alloc", allocStart);
        }

        long copyStart = RtFrameStats.FRAME.startStage();
        try {
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.positionOffset, capture.verts.size())
                    .put(capture.verts.elements(), 0, capture.verts.size());
            MemoryUtil.memIntBuffer(geometry.mapped + layout.indexOffset, idxCount)
                    .put(packed.indices().elements(), 0, idxCount);
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.uvOffset, capture.uvList.size())
                    .put(capture.uvList.elements(), 0, capture.uvList.size());
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.primOffset, packed.primitives().size())
                    .put(packed.primitives().elements(), 0, packed.primitives().size());
            geometry.flush(layout.positionOffset, layout.totalBytes - layout.positionOffset);
            RtFrameStats.FRAME.count("entityUploadBytes", layout.logicalBytes);
            RtFrameStats.FRAME.count("entityPackedBytes", layout.totalBytes);
            RtFrameStats.FRAME.count("entityPackedPaddingBytes", layout.totalBytes - layout.logicalBytes);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.append.copy", copyStart);
        }

        long positionAddr = Math.addExact(geometry.deviceAddress, layout.positionOffset);
        long indexAddr = Math.addExact(geometry.deviceAddress, layout.indexOffset);
        long uvAddr = Math.addExact(geometry.deviceAddress, layout.uvOffset);
        long primAddr = Math.addExact(geometry.deviceAddress, layout.primOffset);
        RtAccel accel;
        long blasStart = RtFrameStats.FRAME.startStage();
        try {
            accel = refitOrBuild(ctx, build, slot, positionAddr, indexAddr, vertCount,
                    packed.indices(), packed.bucketTris());
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.append.blas", blasStart);
        }

        writeTableEntry(build, primAddr, indexAddr, uvAddr, motion.dispAddr,
                motion.rigidX, motion.rigidY, motion.rigidZ, packed.bucketTris(), mask);
        if(entityId==HAND_ENTITY_KEY)MemoryUtil.memPutInt(build.tableBase+(long)build.count*TABLE_ENTRY_BYTES+60,1);
        build.instances.add(new RtAccel.Instance(instanceTransform, accel.deviceAddress,
                instanceBit | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));

        EntityAccel ea = slot.owner;
        clearRefGeometry(ea);
        ea.lastLocal = IDENTITY;
        ea.refAccel = accel;
        ea.refSlot = slot;
        ea.refIndexAddr = indexAddr;
        ea.refUvAddr = uvAddr;
        ea.refPrimAddr = primAddr;
        ea.refBucketTris = packed.copyBucketTris();
        int size = capture.verts.size();
        if (ea.refVerts == null || ea.refVerts.length < size) {
            ea.refVerts = new float[size];
        }
        System.arraycopy(capture.verts.elements(), 0, ea.refVerts, 0, size);
        ea.refVertCount = vertCount;
        ea.refIdxCount = idxCount;
        ea.refShadeHash = shadeHash();
        build.count++;
    }

    /** Clear the latest rigid-reuse view; the backing remains owned by its retired ring slot. */
    private void clearRefGeometry(EntityAccel ea) {
        ea.refAccel = null;
        ea.refSlot = null;
        ea.refIndexAddr = 0L;
        ea.refUvAddr = 0L;
        ea.refPrimAddr = 0L;
        ea.refBucketTris = null;
    }

    private static long growCapacity(long current, long required) {
        long grown = current <= Long.MAX_VALUE - current / 2L ? current + current / 2L : Long.MAX_VALUE;
        return Math.max(required, grown);
    }

    /** Upload a per-vertex displacement array into this frame slot's motion arena; returns 0 if null. */
    private long uploadDisp(RtContext ctx, FrameBuild build, float[] disp) {
        if (disp == null) {
            return 0L;
        }
        beginBuildIfNeeded(ctx, build);
        MotionSlice slice = build.motion.allocate(ctx, (long) disp.length * Float.BYTES);
        MemoryUtil.memFloatBuffer(slice.mapped, disp.length).put(disp, 0, disp.length);
        return slice.deviceAddress;
    }

    /** Upload a reusable primitive list without first copying its backing into a right-sized array. */
    private long uploadDisp(RtContext ctx, FrameBuild build, FloatArrayList disp) {
        // Static and build-budget-reused block entities deliberately have no displacement buffer.
        // Address zero means camera-only motion, not a failed entity frame.
        if (disp == null) return 0L;
        int size = disp.size();
        if (size == 0) {
            return 0L;
        }
        beginBuildIfNeeded(ctx, build);
        MotionSlice slice = build.motion.allocate(ctx, (long) size * Float.BYTES);
        MemoryUtil.memFloatBuffer(slice.mapped, size).put(disp.elements(), 0, size);
        return slice.deviceAddress;
    }

    /** Write one std430 EntityGeom entry, including bases for the two packed BLAS geometries. */
    private void writeTableEntry(FrameBuild build, long primAddr, long idxAddr, long uvAddr, long dispAddr,
                                 float rigidX, float rigidY, float rigidZ, int[] bucketTris, int mask) {
        if (bucketTris == null || bucketTris.length != RtAccel.ENTITY_BUCKETS) {
            throw new IllegalArgumentException("Missing entity BLAS bucket counts");
        }
        long entry = build.tableBase + (long) build.count * TABLE_ENTRY_BYTES;
        MemoryUtil.memPutLong(entry, primAddr);
        MemoryUtil.memPutLong(entry + 8, idxAddr);
        MemoryUtil.memPutLong(entry + 16, uvAddr);
        MemoryUtil.memPutLong(entry + 24, dispAddr);
        MemoryUtil.memPutFloat(entry + 32, rigidX);
        MemoryUtil.memPutFloat(entry + 36, rigidY);
        MemoryUtil.memPutFloat(entry + 40, rigidZ);
        MemoryUtil.memPutFloat(entry + 44, 0f);
        MemoryUtil.memPutInt(entry + 48, 0);
        MemoryUtil.memPutInt(entry + 52, bucketTris[RtAccel.ENTITY_BUCKET_OPAQUE]);
        MemoryUtil.memPutInt(entry + 56, (mask & LightOwnerMask.HELD_SHADOW) == 0 ? 1 : 0);
        MemoryUtil.memPutInt(entry + 60, 0);
    }

    /** Select the next per-entity slot, waiting on its exact last graphics use before mutable reuse. */
    private EntitySlot selectEntityBuildSlot(RtContext ctx, FrameBuild build, int entityId) {
        EntityAccel ea = entityAccels.get(entityId);
        if (ea == null) {
            ea = new EntityAccel();
            entityAccels.put(entityId, ea);
        }
        ea.lastSeen = RtComposite.frameCounter();
        int s = ea.cursor;
        ea.cursor = (ea.cursor + 1) % REFIT_RING;
        EntitySlot slot = ea.ring[s];
        if (slot == null) {
            slot = new EntitySlot();
            slot.owner = ea;
            ea.ring[s] = slot;
        } else {
            awaitGraphicsUse(build, slot.graphicsUse, "entitySlotWaits");
        }
        return slot;
    }

    /**
     * Refit-or-build this entity's persistent acceleration structure in an already-selected retired slot.
     * Records an in-place UPDATE (cheap refit) when the slot already holds an
     * AS of the same topology when refit is enabled. Otherwise it records a full BUILD; BLAS built while
     * refit is disabled omit ALLOW_UPDATE. Refit scratch and packed geometry persist per ring slot.
     */
    private RtAccel refitOrBuild(RtContext ctx, FrameBuild build, EntitySlot slot,
                                 long positionAddr, long indexAddr,
                                 int vertCount, IntArrayList indices, int[] bucketTris) {
        int triCount = 0;
        for (int bucketTriCount : bucketTris) {
            triCount += bucketTriCount;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        boolean refitEnabled = CausticaConfig.Rt.Entities.REFIT_ENABLED.value();
        boolean canUpdate = refitEnabled && slot.accel != null && slot.updatable
                && slot.vertCount == vertCount && slot.triCount == triCount
                && java.util.Arrays.equals(slot.bucketTris, bucketTris)
                // VUID-03768: indexed BLAS UPDATE requires every referenced index to match the BUILD.
                && sameIndexTopology(slot, indices)
                && slot.updatesSinceBuild < REFIT_REBUILD_INTERVAL;
        if (canUpdate) {
            RtFrameStats.FRAME.count("refits", 1);
            long required = slot.updateScratchSize;
            if (slot.refitScratch == null || slot.refitScratch.size < required) {
                if (slot.refitScratch != null) {
                    slot.refitScratch.destroy();
                }
                slot.refitScratch = allocAlignedBuffer(ctx, required, storage, false, "entity refit scratch",
                        ctx.accelerationStructureScratchAlignment());
                RtFrameStats.FRAME.count("entityVmaBufferCreates", 1);
            } else {
                RtFrameStats.FRAME.count("entityScratchBufferReuses", 1);
            }
            build.blas.add(RtAccel.refitEntityUpdate(slot.accel, slot.refitScratch,
                    positionAddr, indexAddr, vertCount, bucketTris,
                    "entity BLAS refit"));
            slot.updatesSinceBuild++;
            return slot.accel;
        }
        // (Re)build: the selected ring slot's exact prior graphics use has completed, so replace its old AS.
        if (slot.accel != null) {
            RtAccel.destroyEntityAccel(slot.accel, slot.backing);
            slot.accel = null;
            slot.backing = null;
        }
        if (!refitEnabled && slot.refitScratch != null) {
            slot.refitScratch.destroy();
            slot.refitScratch = null;
        }
        RtFrameStats.FRAME.count("entityVmaBufferCreates", 2); // persistent AS backing + transient build scratch
        if (refitEnabled) {
            RtAccel.UpdatableBuild ub = RtAccel.prepareUpdatableEntityBlasBuild(ctx, positionAddr, vertCount,
                    indexAddr, bucketTris, "entity BLAS");
            slot.accel = ub.accel();
            slot.backing = ub.backing();
            slot.updateScratchSize = ub.updateScratchSize();
            build.blas.add(ub.op());
            build.refitScratch.add(ub.scratch());
        } else {
            RtAccel.PersistentBuild pb = RtAccel.preparePersistentEntityBlasBuild(ctx, positionAddr, vertCount,
                    indexAddr, bucketTris, "entity BLAS");
            slot.accel = pb.accel();
            slot.backing = pb.backing();
            slot.updateScratchSize = 0L;
            build.blas.add(pb.op());
            build.refitScratch.add(pb.scratch());
        }
        slot.updatable = refitEnabled;
        slot.vertCount = vertCount;
        slot.triCount = triCount;
        slot.bucketTris = bucketTris.clone();
        rememberIndexTopology(slot, indices);
        slot.updatesSinceBuild = 0;
        return slot.accel;
    }

    private static boolean sameIndexTopology(EntitySlot slot, IntArrayList indices) {
        int count = indices.size();
        if (slot.indices == null || slot.indexCount != count) {
            return false;
        }
        int[] current = indices.elements();
        for (int i = 0; i < count; i++) {
            if (slot.indices[i] != current[i]) {
                return false;
            }
        }
        return true;
    }

    private static void rememberIndexTopology(EntitySlot slot, IntArrayList indices) {
        int count = indices.size();
        if (slot.indices == null || slot.indices.length < count) {
            slot.indices = new int[count];
        }
        System.arraycopy(indices.elements(), 0, slot.indices, 0, count);
        slot.indexCount = count;
    }

    /** Retire persistent AS for entities not captured within the last KEEP_FRAMES frames. */
    private void evictStaleAccels(RtContext ctx) {
        if (entityAccels.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        var it = entityAccels.values().iterator();
        while (it.hasNext()) {
            EntityAccel ea = it.next();
            if (now - ea.lastSeen < KEEP_FRAMES) {
                continue;
            }
            for (EntitySlot slot : ea.ring) {
                if (slot != null) {
                    retireEntitySlot(ctx, slot);
                }
            }
            clearRefGeometry(ea);
            it.remove();
        }
    }

    private void destroyEntitySlot(EntitySlot slot) {
        if (slot.accel != null) {
            RtAccel.destroyEntityAccel(slot.accel, slot.backing);
            slot.accel = null;
            slot.backing = null;
        }
        if (slot.geometry != null) {
            retainedGeometryBytes = Math.subtractExact(retainedGeometryBytes, slot.geometry.size);
            slot.geometry.destroy();
            slot.geometry = null;
        }
        if (slot.refitScratch != null) {
            slot.refitScratch.destroy();
            slot.refitScratch = null;
        }
        slot.indices = null;
        slot.indexCount = 0;
    }

    /** Detach a stale slot immediately and destroy its GPU owners after their exact last use completes. */
    private void retireEntitySlot(RtContext ctx, EntitySlot slot) {
        RtAccel accel = slot.accel;
        RtBuffer backing = slot.backing;
        RtBuffer geometry = slot.geometry;
        RtBuffer scratch = slot.refitScratch;
        long geometryBytes = geometry == null ? 0L : geometry.size;
        retainedGeometryBytes = Math.subtractExact(retainedGeometryBytes, geometryBytes);
        slot.accel = null;
        slot.backing = null;
        slot.geometry = null;
        slot.refitScratch = null;
        slot.indices = null;
        slot.indexCount = 0;
        ctx.gpuExecutor().retireAfterGraphics(slot.graphicsUse, () -> {
            if (accel != null) RtAccel.destroyEntityAccel(accel, backing);
            if (geometry != null) geometry.destroy();
            if (scratch != null) scratch.destroy();
        });
        RtFrameStats.FRAME.count("entitySlotRetirements", 1);
    }

    private void setCamera(double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        if (cameraState == null) {
            cameraState = new CameraRenderState();
        }
        cameraState.pos = new Vec3(camX, camY, camZ);
        cameraState.projectionMatrix.set(projection);
        cameraState.viewRotationMatrix.set(viewRotation);
        // viewRotation is the world->view rotation (mvCurProjView = frameProjection * frameViewRotation);
        // vanilla's Camera.rotation() (what CameraRenderState.orientation actually holds, per Camera.java
        // "cameraState.orientation.set(this.rotation())") is the INVERSE of that …view->world, i.e. the
        // camera's own facing direction, used to billboard world-space quads (name tags) to face the
        // camera. A pure rotation's inverse is its conjugate. Nothing consumed this field before
        // RtNameTagFeature; a plain setFromUnnormalized(viewRotation) here would billboard backwards.
        cameraState.orientation.setFromUnnormalized(viewRotation).conjugate();
        cameraState.initialized = true;
    }

    private void ensureResources(RtContext ctx) {
        int requiredCapacity = maxEntities()+RtDhLods.MAX_SECTIONS;
        if (tableRing != null && tableCapacity >= requiredCapacity) {
            return;
        }
        if (tableRing != null) {
            for (TableSlot old : tableRing) {
                ctx.gpuExecutor().retireAfterGraphics(old.graphicsUse, old.buffer::destroy);
                RtFrameStats.FRAME.count("entityTableRetirements", 1);
            }
            tableRing = null;
            tableCapacity = 0;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        tableRing = new TableSlot[TABLE_RING];
        for (int i = 0; i < TABLE_RING; i++) {
            tableRing[i] = new TableSlot(ctx.createBuffer((long) requiredCapacity * TABLE_ENTRY_BYTES + DH_COVERAGE_BYTES,
                    storage, true, "entity geometry table ring " + i));
        }
        tableCapacity = requiredCapacity;
    }

    /** Drop CPU templates that retain resource-pack-owned model trees. */
    public void onResourceReload() {
        previousHand=null;RtFirstPerson.onReload();
        collector.clearCaches();
    }

    /** Free the geometry-table ring and entity resources (teardown; caller has idled the device). */
    public void shutdown() {
        RtDhLods.INSTANCE.shutdown();
        for (FrameLists lists : frameLists) {
            lists.releaseDeferred();
            lists.destroyPersistent();
        }
        for (EntityAccel ea : entityAccels.values()) {
            for (EntitySlot slot : ea.ring) {
                if (slot != null) {
                    destroyEntitySlot(slot);
                }
            }
            clearRefGeometry(ea);
        }
        entityAccels.clear();
        for (BeEntry e : beCache.values()) {
            RtAccel.destroyEntityAccel(e.accel, e.backing);
            e.geometry.destroy();
        }
        beCache.clear();
        if (tableRing != null) {
            for (TableSlot slot : tableRing) {
                slot.buffer.destroy();
            }
            tableRing = null;
            tableCapacity = 0;
        }
        prevVerts.clear();
        curVerts.clear();
        particlePrev.clear();
        particleCur.clear();
        particleDisp.clear();
        glowBatches.clear();
        nameTagBatches.clear();
        resetPoseStack(blockEntityPoseStack);
        beCandidates.clear();
        beCandidatePool.clear();
        retainedGeometryBytes = 0L;
        collector.clearCaches();
    }
}
