package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fancy World Animations (fwa) compatibility. FWA hides a block from vanilla/Sodium section meshing while it
 * animates (doors, trapdoors, fence gates, levers, swinging lanterns and chains, bells, ...) and draws a moving
 * copy through {@code submitCustomGeometry} in its own level-render callback. The RT terrain is meshed by our
 * own snapshot region and the raster output is discarded, so without this the static block stayed in the BLAS
 * and the moving copy never reached the ray tracer: no animation at all.
 *
 * <p>Two hooks: {@link #hides} drops an animating block from the RT mesh exactly like FWA's
 * {@code RenderSectionRegion} mixin, and {@link #render} replays FWA's renderer into the RT capture collector
 * each frame. FWA is an optional dependency, reached by reflection; any failure disables the bridge.
 */
public final class FancyWorldAnimationsCompat {
    private static final MethodHandle RENDER, CONTEXT, SHOULD_CANCEL, TYPE_OF, PARTIAL_TICK, MOD_TOGGLE;
    /** Our toggle and FWA's own master switch, sampled once per frame for the mesher workers. */
    private static volatile boolean shown = true;
    private static final Object USELESS;
    /** Non-approving visibility probes (FWA's shouldCancelBlockRendering approves removal as a side effect). */
    private static final MethodHandle GET_AT, HIDE_ORIGINAL, FOR_REMOVAL, APPROVE_REMOVAL;
    /** Finished animations whose static block is being re-meshed: position → frame time it started waiting. */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap AWAITING = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** FWA's private clock: {@code timer = nanoTime - startingTime}, advanced only in its level-render callback. */
    private static final java.lang.reflect.Field TIMER, STARTING_TIME;
    private static final Map<?, ?> ANIMATIONS;
    private static volatile boolean active;
    /** Per block state: can FWA animate it at all? Mesher worker threads read this for every block. */
    private static final Map<BlockState, Boolean> ANIMATABLE = new ConcurrentHashMap<>();
    /** Positions hidden from the RT mesh last frame; a change re-meshes that block. Render thread only. */
    private static LongOpenHashSet hidden = new LongOpenHashSet(), scratch = new LongOpenHashSet();
    private static boolean failureLogged;
    private static long lastReport;
    private static int lastRendered;

    /** RtEntities: vertices captured from this frame's replay (diagnostics). */
    public static void reportCaptured(int vertices) { lastRendered = vertices; }

    /** Every 5 s while animations exist: what the bridge sees, so a "nothing moves" report can be pinned down. */
    private static void diagnostics(boolean on, boolean vanillaRan) {
        long now = System.nanoTime();
        if (now - lastReport < 5_000_000_000L) return;
        lastReport = now;
        StringBuilder kinds = new StringBuilder();
        java.util.Map<String, Integer> byType = new java.util.TreeMap<>();
        for (Object a : ANIMATIONS.values()) byType.merge(a.getClass().getSimpleName(), 1, Integer::sum);
        byType.forEach((k, v) -> kinds.append(k).append('=').append(v).append(' '));
        CausticaMod.LOGGER.info("[FWA-RT] on={} vanillaRan={} animations={} hidden={} capturedVerts={} types: {}",
                on, vanillaRan, ANIMATIONS.size(), hidden.size(), lastRendered, kinds);
    }

    static {
        MethodHandle render = null, context = null, shouldCancel = null, typeOf = null, partial = null, toggle = null;
        Object useless = null;
        java.lang.reflect.Field timer = null, startingTime = null;
        MethodHandle getAt = null, hideOriginal = null, forRemoval = null, approve = null;
        Map<?, ?> animations = null;
        boolean ok = false;
        if (FabricLoader.getInstance().isModLoaded("fwa")) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> client = Class.forName("fr.madu59.fwa.FancyWorldAnimationsClient");
                Class<?> ctx = Class.forName("fr.madu59.fwa.rendering.AnimationRenderingContext");
                Class<?> type = Class.forName("fr.madu59.fwa.FancyWorldAnimationsClient$Type");
                Class<?> frustum = Class.forName("net.minecraft.client.renderer.culling.Frustum");
                render = lookup.findStatic(client, "render", MethodType.methodType(void.class, ctx));
                context = lookup.findConstructor(ctx, MethodType.methodType(void.class, PoseStack.class, Vec3.class,
                        SubmitNodeCollector.class, frustum, double.class, boolean.class));
                shouldCancel = lookup.findStatic(client, "shouldCancelBlockRendering",
                        MethodType.methodType(boolean.class, BlockPos.class));
                typeOf = lookup.findStatic(client, "typeOf", MethodType.methodType(type, BlockState.class));
                partial = lookup.findStatic(client, "getPartialTick", MethodType.methodType(double.class));
                useless = type.getField("USELESS").get(null);
                try {
                    timer = client.getDeclaredField("timer");
                    startingTime = client.getDeclaredField("startingTime");
                    timer.setAccessible(true);
                    startingTime.setAccessible(true);
                } catch (Throwable t) {
                    timer = null;
                    startingTime = null;
                    CausticaMod.LOGGER.warn("Fancy World Animations clock not reachable; animations may not advance under RT: {}", t.toString());
                }
                try {
                    Class<?> settings = Class.forName("fr.madu59.fwa.config.SettingsManager");
                    Object option = settings.getField("MOD_TOGGLE").get(null);
                    toggle = lookup.findVirtual(option.getClass(), "getValue", MethodType.methodType(Object.class)).bindTo(option);
                } catch (Throwable ignored) {
                    toggle = null; // older FWA without a master switch: always on
                }
                Object holder = client.getField("animations").get(null);
                Class<?> animation = Class.forName("fr.madu59.fwa.anims.Animation");
                getAt = lookup.findVirtual(holder.getClass(), "getAt", MethodType.methodType(animation, BlockPos.class)).bindTo(holder);
                hideOriginal = lookup.findVirtual(animation, "hideOriginalBlock", MethodType.methodType(boolean.class));
                forRemoval = lookup.findVirtual(animation, "isForRemoval", MethodType.methodType(boolean.class));
                approve = lookup.findVirtual(animation, "approveRemoval", MethodType.methodType(void.class, double.class));
                animations = (Map<?, ?>) holder.getClass().getField("animations").get(holder);
                ok = true;
                CausticaMod.LOGGER.info("Fancy World Animations detected: RT block animations enabled");
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Fancy World Animations present but its API changed; RT animations disabled: {}", t.toString());
            }
        }
        RENDER = render; CONTEXT = context; SHOULD_CANCEL = shouldCancel; TYPE_OF = typeOf; PARTIAL_TICK = partial; MOD_TOGGLE = toggle;
        USELESS = useless; ANIMATIONS = animations; active = ok; TIMER = timer; STARTING_TIME = startingTime;
        GET_AT = getAt; HIDE_ORIGINAL = hideOriginal; FOR_REMOVAL = forRemoval; APPROVE_REMOVAL = approve;
    }

    private FancyWorldAnimationsCompat() {}

    /** Bridge loaded and working (the per-frame {@link #render} must keep running even while switched off). */
    public static boolean present() { return active; }

    /** Present and switched on: animating blocks are hidden from the static mesh. */
    public static boolean active() { return active && shown; }

    /** RT mesher: is this block currently drawn by FWA instead of the static mesh? Any thread. */
    public static boolean hides(BlockState state, int x, int y, int z) {
        if (!active || !shown || state.isAir()) return false;
        Boolean animatable = ANIMATABLE.get(state);
        if (animatable == null) {
            try { animatable = TYPE_OF.invoke(state) != USELESS; }
            catch (Throwable t) { disable(t); return false; }
            ANIMATABLE.put(state, animatable);
        }
        if (!animatable) return false;
        try { return hiddenNow(new BlockPos(x, y, z)); }
        catch (Throwable t) { disable(t); return false; }
    }

    /**
     * Render thread, once per RT frame: re-mesh blocks whose hidden state changed (an animation that started or
     * finished), then replay every live animation into {@code collector} with coordinates relative to
     * {@code origin}, with a null frustum so off-screen animations still cast shadows and appear in reflections.
     */
    public static boolean render(PoseStack pose, Vec3 origin, SubmitNodeCollector collector) {
        if (!active) return false;
        try {
            boolean on = dev.comfyfluffy.caustica.CausticaConfig.Rt.Composite.FWA_ANIMATIONS.value()
                    && (MOD_TOGGLE == null || Boolean.TRUE.equals(MOD_TOGGLE.invoke()));
            shown = on;
            syncHidden(); // switched off: every hidden block is re-meshed back into the static terrain
            diagnostics(on, !VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame());
            if (!on || ANIMATIONS.isEmpty()) return false;
            boolean vanillaSkipped = VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame();
            // FWA's clock only advances in its level-render callback, which never fires while RT cancels the
            // vanilla world render: every animation stood frozen at its first frame. Advance it the same way.
            if (vanillaSkipped && TIMER != null) TIMER.setLong(null, System.nanoTime() - STARTING_TIME.getLong(null));
            double now = (double) PARTIAL_TICK.invoke();
            // FWA advances, finishes and removes animations only in its main pass, which runs from the vanilla
            // level-render event. RT cancels LevelRenderer.render, so that event never fires: replay the main
            // pass here (it ticks). Only when vanilla did draw the world this frame is FWA's own pass live,
            // and then the draw-only shadow mode avoids ticking twice.
            boolean vanillaRan = !VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame();
            Object ctx = CONTEXT.invoke(pose, origin, collector, null, now, vanillaRan);
            RENDER.invoke(ctx);
            return true;
        } catch (Throwable t) {
            disable(t);
            return false;
        }
    }

    /**
     * Is the block drawn by FWA right now (static copy must stay out of the mesh)? Same answer as FWA's
     * shouldCancelBlockRendering but without its side effect: that call approves a finished animation's
     * removal, which FWA expects only once the chunk mesh showing the static block exists. Approving earlier
     * (every frame, or at RT mesh time several frames before publication) left the block missing meanwhile.
     */
    private static boolean hiddenNow(BlockPos pos) throws Throwable {
        Object a = GET_AT.invoke(pos);
        return a != null && (boolean) HIDE_ORIGINAL.invoke(a) && !(boolean) FOR_REMOVAL.invoke(a);
    }

    /**
     * Render thread: an RT section was published. Finished animations inside it now have their static block
     * in the BLAS, so FWA may drop the moving copy (it removes it half a tick after approval).
     */
    public static void onSectionPublished(int sx, int sy, int sz) {
        if (!active || AWAITING.isEmpty()) return;
        try {
            double now = (double) PARTIAL_TICK.invoke();
            for (var it = AWAITING.keySet().iterator(); it.hasNext(); ) {
                long p = it.nextLong();
                if (BlockPos.getX(p) >> 4 != sx || BlockPos.getY(p) >> 4 != sy || BlockPos.getZ(p) >> 4 != sz) continue;
                Object a = GET_AT.invoke(BlockPos.of(p));
                if (a != null) APPROVE_REMOVAL.invoke(a, now);
                it.remove();
            }
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static void syncHidden() throws Throwable {
        LongOpenHashSet next = scratch;
        next.clear();
        long nowNanos = System.nanoTime();
        if (shown) for (Object key : ANIMATIONS.keySet()) {
            BlockPos pos = (BlockPos) key;
            Object a = GET_AT.invoke(pos);
            if (a == null || !(boolean) HIDE_ORIGINAL.invoke(a)) continue;
            if (!(boolean) FOR_REMOVAL.invoke(a)) next.add(pos.asLong());
            else if (!AWAITING.containsKey(pos.asLong())) AWAITING.put(pos.asLong(), nowNanos);
        }
        // A re-mesh that never publishes (section emptied, cancelled) must not keep the moving copy forever.
        if (!AWAITING.isEmpty()) {
            double now = (double) PARTIAL_TICK.invoke();
            for (var it = AWAITING.long2LongEntrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (nowNanos - e.getLongValue() < 2_000_000_000L) continue;
                Object a = GET_AT.invoke(BlockPos.of(e.getLongKey()));
                if (a != null) APPROVE_REMOVAL.invoke(a, now);
                it.remove();
            }
        }
        for (LongIterator it = next.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            if (!hidden.contains(p)) dirty(p);
        }
        for (LongIterator it = hidden.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            if (!next.contains(p)) dirty(p);
        }
        scratch = hidden;
        hidden = next;
    }

    private static void dirty(long packed) {
        int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
        RtTerrain.markBlocksDirty(x, y, z, x, y, z);
    }

    private static void disable(Throwable t) {
        active = false;
        if (!failureLogged) {
            failureLogged = true;
            CausticaMod.LOGGER.error("Fancy World Animations RT bridge failed; disabled for this session", t);
        }
    }
}
