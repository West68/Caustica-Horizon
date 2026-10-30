package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-button showcase film. Finds its own locations in the current single-player world (biomes and a
 * village via /locate, a water spot for a small glass pavilion it builds), sets time and weather, flies
 * a scripted camera through each shot and renders it OFFLINE: the world ticks are frozen, every output
 * frame is held for {@link #RENDERS_PER_FRAME} rendered frames so the path tracer and DLSS-RR settle, and
 * the shader clock advances exactly 1/60 s per output frame. Frames are written as PNG, each shot is
 * encoded with ffmpeg as soon as it finishes (then its PNGs are deleted), and at the end the shots are
 * joined with crossfades and scored with C418's "Sweden" from the game's own assets.
 *
 * <p>Output: {@code .minecraft/caustica-cinematic/<time>/showcase.mp4}. Settings, game mode, HUD and the
 * tick state are restored afterwards; the pavilion stays in the world.
 */
public final class CinematicDirector {
    private static final int FPS = 60;
    private static final int RENDERS_PER_FRAME = 8;   // rendered frames per captured frame (convergence)
    private static final int WARM_RENDERS = 150;      // exposure + RR history settle at a shot's first pose
    // The test map's rooms are small and fully loaded: fewer renders per frame still converge there.
    private static final int MAP_RENDERS_PER_FRAME = 5, MAP_WARM_RENDERS = 90;
    private static int rendersPerFrame = RENDERS_PER_FRAME, warmRenders = WARM_RENDERS;
    private static final double LOAD_SECONDS = 5.0;   // minimum: weather transition, exposure
    private static final double LOAD_TIMEOUT = 120.0; // give up waiting for terrain and film anyway
    private static final double READY_HOLD = 2.0;     // terrain must stay complete this long
    private static final double LOD_WAIT = 30.0;     // DH's queue never drains in the End: a fixed wait instead
    /** Shots that look far past the render distance: give Distant Horizons time to mesh the horizon. */
    /** Save folder of the "Ray Tracing testing place" map (Jikali, Ms-PL): films TestMapShots instead. */
    static final String TEST_MAP = "ray-tracing-test-place";
    private static final Set<String> LOD_SHOTS = Set.of("peaks", "the_end");

    // ------------------------------------------------------------------ camera

    record Pose(double x, double y, double z, float yaw, float pitch) {}
    interface CamPath { Pose at(double t); }

    static Pose look(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z, l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-9;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.asin(dy / l));
        return new Pose(x, y, z, yaw, pitch);
    }
    static double ease(double t) { return 0.4 * t + 0.6 * t * t * (3 - 2 * t); }
    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Circle around a target, always looking at it. */
    static CamPath orbit(BlockPos c, double radius, double height, double startDeg, double sweepDeg, double lookUp) {
        return t -> {
            double a = Math.toRadians(startDeg + sweepDeg * ease(t));
            double x = c.getX() + 0.5 + Math.cos(a) * radius, z = c.getZ() + 0.5 + Math.sin(a) * radius;
            return look(x, c.getY() + height, z, c.getX() + 0.5, c.getY() + lookUp, c.getZ() + 0.5);
        };
    }
    /** Straight move with a fixed heading, pitch interpolated. */
    static CamPath dolly(double x0, double y0, double z0, double x1, double y1, double z1, float yaw, float p0, float p1) {
        return t -> {
            double e = ease(t);
            return new Pose(lerp(x0, x1, e), lerp(y0, y1, e), lerp(z0, z1, e), yaw, (float) lerp(p0, p1, e));
        };
    }
    /** Straight move that keeps looking at a point. */
    static CamPath track(double x0, double y0, double z0, double x1, double y1, double z1, double tx, double ty, double tz) {
        return t -> {
            double e = ease(t);
            return look(lerp(x0, x1, e), lerp(y0, y1, e), lerp(z0, z1, e), tx, ty, tz);
        };
    }
    /** Heading turning from yaw0 to yaw1 while moving. */
    static CamPath pan(double x0, double y0, double z0, double x1, double y1, double z1, float yaw0, float yaw1, float p0, float p1) {
        return t -> {
            double e = ease(t);
            return new Pose(lerp(x0, x1, e), lerp(y0, y1, e), lerp(z0, z1, e), (float) lerp(yaw0, yaw1, e), (float) lerp(p0, p1, e));
        };
    }

    // ------------------------------------------------------------------ shots

    /** Where a shot happens, found on the server thread: several places, best first. */
    interface Locator { List<BlockPos> find(MinecraftServer server, ServerLevel level); }
    interface Builder { void build(ServerLevel level, BlockPos anchor); }
    /** A camera move from a start position and heading (degrees, Minecraft yaw). */
    interface Move { CamPath make(double x, double y, double z, float yaw); }
    enum Height { SURFACE, WATER, CAVERN, ANCHOR }

    /** What a good frame of this shot looks like, judged from raycasts. */
    record Look(double skyMin, double skyMax, double nearLimit, double medianMin, Predicate<BlockState> interest,
                double interestWant, boolean ceiling) {}
    /** How to search: candidate starts within radius (step), at a height, with headings, scored by Look. */
    record Scout(int radius, int step, Height height, double heightAbove, float[] headings, Move move, Look look) {}

    record Shot(String name, ResourceKey<Level> dim, String locateKey, Locator locator, long dayTime, boolean rain,
                double seconds, Scout scout, Builder build) {}

    private static final long DAY = 24000L; // day 1: a waning gibbous moon, so the terminator shows relief
    private static final float[] ALL = {0, 45, 90, 135, 180, 225, 270, 315};
    private static final float[] EAST = {-120, -105, -90, -75, -60};   // facing +X: sunrise
    private static final float[] WEST = {60, 75, 90, 105, 120};        // facing -X: sunset
    private static final float[] BACKLIT = {30, 60, 90, 120, 150};    // into a low western sun: lit edges, long shadows
    private static final float[] SIDELIT = {0, 30, 150, 180};          // sun from the side: relief on snow slopes
    private static final float[] END_SKY = {-90, -75, -63, -50, -35};  // the black hole sits near yaw -63

    // Moves. Forward = (-sin yaw, 0, cos yaw).
    static double fx(float yaw) { return -Math.sin(Math.toRadians(yaw)); }
    static double fz(float yaw) { return Math.cos(Math.toRadians(yaw)); }
    /** Slow push forward with a tilt. */
    static Move push(double dist, double rise, float p0, float p1) {
        return (x, y, z, yaw) -> dolly(x, y, z, x + fx(yaw) * dist, y + rise, z + fz(yaw) * dist, yaw, p0, p1);
    }
    /** Lateral slide that keeps a point ahead centred: parallax between foreground and distance. */
    static Move slide(double dist, double lookAhead, double lookDrop) {
        return (x, y, z, yaw) -> {
            double rx = -fz(yaw), rz = fx(yaw);
            double tx = x + fx(yaw) * lookAhead, tz = z + fz(yaw) * lookAhead;
            return track(x - rx * dist / 2, y, z - rz * dist / 2, x + rx * dist / 2, y, z + rz * dist / 2, tx, y - lookDrop, tz);
        };
    }
    /** Arc around a point ahead. */
    static Move arc(double radius, double sweep, double lookDrop) {
        return (x, y, z, yaw) -> {
            double cx = x + fx(yaw) * radius, cz = z + fz(yaw) * radius;
            double start = Math.toDegrees(Math.atan2(z - cz, x - cx));
            return t -> {
                double a = Math.toRadians(start + sweep * ease(t));
                return look(cx + Math.cos(a) * radius, y, cz + Math.sin(a) * radius, cx, y - lookDrop, cz);
            };
        };
    }
    /** Circle around the start point itself (a built subject), starting at angle yaw, looking at it. */
    static Move around(double radius, double sweep, double lookDrop) {
        return (x, y, z, yaw) -> t -> {
            double a = Math.toRadians(yaw + sweep * ease(t));
            return look(x + Math.cos(a) * radius, y, z + Math.sin(a) * radius, x, y - lookDrop, z);
        };
    }
    /** Walk through the start point: from dist/2 behind it to dist/2 ahead. */
    static Move through(double dist, float p0, float p1) {
        return (x, y, z, yaw) -> dolly(x - fx(yaw) * dist / 2, y, z - fz(yaw) * dist / 2,
                x + fx(yaw) * dist / 2, y + 0.2, z + fz(yaw) * dist / 2, yaw, p0, p1);
    }

    /** Move while turning and tilting (a reveal); rise > 0 makes it a crane. */
    static Move crane(double rise, double dist, float turn, float p0, float p1) {
        return (x, y, z, yaw) -> pan(x, y, z, x + fx(yaw) * dist, y + rise, z + fz(yaw) * dist, yaw, yaw + turn, p0, p1);
    }

    static boolean is(BlockState s, Block... blocks) { for (Block b : blocks) if (s.is(b)) return true; return false; }

    private static List<Shot> script() {
        List<Shot> s = new ArrayList<>();
        Locator meadow = (srv, lvl) -> biomes(srv, lvl, "minecraft:meadow", "minecraft:flower_forest", "minecraft:sunflower_plains", "minecraft:plains");
        Locator sea = (srv, lvl) -> biomes(srv, lvl, "minecraft:beach", "minecraft:warm_ocean", "minecraft:ocean");
        Locator village = (srv, lvl) -> structures(srv, lvl, "minecraft:village_plains", "minecraft:village_taiga", "minecraft:village_savanna");
        Predicate<BlockState> flowers = st -> st.is(BlockTags.FLOWERS) || st.is(BlockTags.LEAVES) || is(st, Blocks.SHORT_GRASS, Blocks.TALL_GRASS);
        Predicate<BlockState> water = st -> st.getFluidState().is(FluidTags.WATER);
        Predicate<BlockState> villageBlocks = st -> st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS) || is(st, Blocks.DIRT_PATH,
                Blocks.HAY_BLOCK, Blocks.GLASS_PANE, Blocks.COBBLESTONE, Blocks.BELL, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN)
                || st.getFluidState().is(FluidTags.WATER);
        Predicate<BlockState> peaks = st -> st.is(BlockTags.SNOW) || is(st, Blocks.STONE, Blocks.PACKED_ICE, Blocks.CALCITE, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW);
        Predicate<BlockState> cherry = st -> is(st, Blocks.CHERRY_LEAVES, Blocks.PINK_PETALS, Blocks.CHERRY_LOG);
        Predicate<BlockState> forest = st -> st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(BlockTags.FLOWERS);
        Predicate<BlockState> glass = st -> is(st, Blocks.GLASS, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR, Blocks.LANTERN) || st.getFluidState().is(FluidTags.WATER);
        Predicate<BlockState> crimson = st -> st.is(BlockTags.NYLIUM) || is(st, Blocks.CRIMSON_STEM, Blocks.CRIMSON_FUNGUS, Blocks.SHROOMLIGHT,
                Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.NETHER_WART_BLOCK, Blocks.CRIMSON_ROOTS, Blocks.WARPED_STEM, Blocks.WARPED_WART_BLOCK);
        Predicate<BlockState> lava = st -> st.getFluidState().is(FluidTags.LAVA) || is(st, Blocks.GLOWSTONE, Blocks.MAGMA_BLOCK, Blocks.BASALT);
        Predicate<BlockState> end = st -> is(st, Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER, Blocks.PURPUR_BLOCK);

        s.add(new Shot("night_sky", Level.OVERWORLD, "meadow", meadow, DAY + 18000, false, 8.0,
                new Scout(64, 16, Height.SURFACE, 2.0, ALL, crane(1.0, 3.0, 25f, -6f, -58f), new Look(0.30, 0.85, 6, 12, flowers, 0.0, false)), null));
        s.add(new Shot("sunrise_sea", Level.OVERWORLD, "sea", sea, DAY + 23250, false, 7.0,
                new Scout(64, 16, Height.WATER, 3.0, EAST, push(36, -0.5, -5f, -3f), new Look(0.30, 0.60, 6, 25, water, 0.25, false)), null));
        s.add(new Shot("cherry_grove", Level.OVERWORLD, "cherry", (srv, lvl) -> biomes(srv, lvl, "minecraft:cherry_grove", "minecraft:flower_forest"),
                DAY + 11000, false, 7.0,
                new Scout(64, 12, Height.SURFACE, 7.0, BACKLIT, slide(18, 22, 4), new Look(0.10, 0.45, 5, 10, cherry, 0.25, false)), null));
        s.add(new Shot("peaks", Level.OVERWORLD, "peaks", (srv, lvl) -> biomes(srv, lvl, "minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:stony_peaks", "minecraft:snowy_slopes"),
                DAY + 11300, false, 7.0,
                new Scout(96, 16, Height.SURFACE, 24.0, SIDELIT, push(40, 6, 14f, 8f), new Look(0.20, 0.65, 10, 25, peaks, 0.20, false)), null));
        s.add(new Shot("village_sunset", Level.OVERWORLD, "village", village, DAY + 11900, false, 8.0,
                new Scout(48, 8, Height.SURFACE, 6.0, WEST, slide(34, 20, 4), new Look(0.20, 0.50, 4, 12, villageBlocks, 0.25, false)), null));
        s.add(new Shot("village_rain", Level.OVERWORLD, "village", village, DAY + 6000, true, 7.0,
                new Scout(40, 6, Height.SURFACE, 1.9, ALL, slide(10, 10, 1), new Look(0.05, 0.40, 2.5, 6, villageBlocks, 0.30, false)), null));
        s.add(new Shot("forest", Level.OVERWORLD, "forest", (srv, lvl) -> biomes(srv, lvl, "minecraft:old_growth_birch_forest", "minecraft:birch_forest", "minecraft:forest", "minecraft:dark_forest"),
                DAY + 10600, false, 7.0,
                new Scout(48, 6, Height.SURFACE, 1.8, BACKLIT, push(6, 0.4, -3f, -9f), new Look(0.03, 0.30, 2.0, 5, forest, 0.40, false)), null));
        s.add(new Shot("pavilion_sunset", Level.OVERWORLD, "sea", sea, DAY + 12150, false, 8.0,
                new Scout(0, 1, Height.ANCHOR, 4.5, ALL, around(15, 95, 2.0), new Look(0.15, 0.65, 4, 10, glass, 0.20, false)), CinematicDirector::buildPavilion));
        s.add(new Shot("pavilion_night", Level.OVERWORLD, "sea", sea, DAY + 18000, false, 7.0,
                new Scout(0, 1, Height.ANCHOR, 2.4, new float[]{-45, 45, 135, 225}, through(4.0, -12f, -24f), new Look(0.0, 1.0, 1.0, 2, glass, 0.0, false)),
                CinematicDirector::buildPavilion));
        s.add(new Shot("nether_crimson", Level.NETHER, "crimson", (srv, lvl) -> biomes(srv, lvl, "minecraft:crimson_forest", "minecraft:warped_forest"),
                DAY + 6000, false, 7.0,
                new Scout(64, 8, Height.CAVERN, 3.5, ALL, arc(10, 75, 1.5), new Look(0.0, 1.0, 3, 10, crimson, 0.35, true)), null));
        s.add(new Shot("nether_lava", Level.NETHER, "lava", (srv, lvl) -> biomes(srv, lvl, "minecraft:nether_wastes", "minecraft:basalt_deltas", "minecraft:soul_sand_valley"),
                DAY + 6000, false, 7.0,
                new Scout(96, 16, Height.CAVERN, 7.0, ALL, push(28, 1, 14f, 8f), new Look(0.0, 1.0, 6, 25, lava, 0.30, true)), null));
        s.add(new Shot("the_end", Level.END, "end", (srv, lvl) -> List.of(new BlockPos(-60, 64, -40), new BlockPos(60, 64, 40), new BlockPos(-40, 64, 60)),
                DAY + 6000, false, 9.0,
                new Scout(32, 8, Height.SURFACE, 4.0, END_SKY, crane(3.0, 10.0, 18f, -8f, -16f), new Look(0.45, 0.85, 4, 15, end, 0.10, false)), null));
        s.add(new Shot("finale", Level.OVERWORLD, "meadow", meadow, DAY + 18000, false, 9.0,
                new Scout(64, 16, Height.SURFACE, 2.0, ALL, crane(18, 0.0, 35f, -16f, -55f), new Look(0.30, 0.85, 6, 12, flowers, 0.0, false)), null));
        return s;
    }

    // ------------------------------------------------------------------ scouting (server thread)

    /** One candidate's verdict: a score, or rejected (NEGATIVE_INFINITY) with the reason for the log. */
    record Verdict(double score, String reason) {
        static Verdict reject(String why) { return new Verdict(Double.NEGATIVE_INFINITY, why); }
        boolean ok() { return score > Double.NEGATIVE_INFINITY; }
    }

    private static final int CLEAR_SAMPLES = 32;     // poses along the path whose surroundings must be air
    private static final int VIEW_SAMPLES = 6;       // poses along the path whose view is judged
    private static final int RAYS_X = 11, RAYS_Y = 7;
    private static final double GOOD_ENOUGH = 0.95;  // stop searching further anchors past this score

    /** Searches candidate starts around each anchor and returns the best-composed path, or null. */
    private static CamPath scout(ServerLevel level, List<BlockPos> anchors, Shot shot) {
        Scout sc = shot.scout();
        long start = System.nanoTime();
        CamPath best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Map<String, Integer> rejects = new TreeMap<>();
        int tried = 0;
        for (BlockPos anchor : anchors) {
            // Pass 1, every candidate: free space along the whole path plus the middle frame's view.
            List<Map.Entry<Double, CamPath>> shortlist = new ArrayList<>();
            for (int dx = -sc.radius(); dx <= sc.radius(); dx += sc.step()) for (int dz = -sc.radius(); dz <= sc.radius(); dz += sc.step()) {
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                for (double y : heights(level, sc, anchor, x, z)) for (float yaw : sc.headings()) {
                    CamPath path = sc.move().make(x + 0.5, y, z + 0.5, yaw);
                    tried++;
                    Verdict v = judge(level, path, sc.look(), true);
                    if (!v.ok()) { rejects.merge(v.reason(), 1, Integer::sum); continue; }
                    shortlist.add(Map.entry(v.score(), path));
                }
            }
            // Pass 2, the 40 best: every view sample along the path; one bad frame rejects it.
            shortlist.sort((a, b) -> Double.compare(b.getKey(), a.getKey()));
            for (var e : shortlist.subList(0, Math.min(40, shortlist.size()))) {
                Verdict v = judge(level, e.getValue(), sc.look(), false);
                if (!v.ok()) { rejects.merge(v.reason(), 1, Integer::sum); continue; }
                if (v.score() > bestScore) { bestScore = v.score(); best = e.getValue(); }
            }
            if (bestScore >= GOOD_ENOUGH) break;
        }
        log("scout {}: {} candidates in {} s, best {}, rejected {}", shot.name(), tried,
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9),
                best == null ? "none" : String.format(Locale.ROOT, "%.2f", bestScore), rejects);
        return best;
    }

    /** Camera heights to try at a column. */
    private static List<Double> heights(ServerLevel level, Scout sc, BlockPos anchor, int x, int z) {
        double h = sc.heightAbove();
        switch (sc.height()) {
            case ANCHOR -> { return List.of(anchor.getY() + h); }
            case SURFACE -> { return List.of(height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + h); }
            case WATER -> {
                int top = height(level, Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                return level.getFluidState(new BlockPos(x, top, z)).is(FluidTags.WATER) ? List.of(top + 1 + h) : List.of();
            }
            case CAVERN -> {
                // Open Nether caverns: a floor (or lava) with at least 14 blocks of air above it, under y 110.
                List<Double> out = new ArrayList<>();
                int air = 0;
                for (int y = 110; y > 31 && out.size() < 2; y--) {
                    BlockState st = level.getBlockState(new BlockPos(x, y, z));
                    if (st.isAir()) { air++; continue; }
                    if (air >= 14) out.add(y + 1 + h);
                    air = 0;
                }
                return out;
            }
        }
        return List.of();
    }

    /**
     * Rejects a path on any single bad sample - one bad frame spoils the shot - and scores the rest by
     * composition: sky share in range, depth spread over near/mid/far layers, subject matter in view,
     * nothing looming in front of the lens, no Nether roof filling the frame.
     */
    private static Verdict judge(Level level, CamPath path, Look look, boolean quick) {
        boolean nether = level.dimension() == Level.NETHER;
        // 1) Free space along the whole move: the lens sits in clear air, and no solid block or liquid comes
        //    within CLEARANCE of it (plants further than the lens cell are fine). Interior shots (nearLimit
        //    <= 1) only need a smaller bubble.
        double clearance = look.nearLimit() <= 1.0 ? 0.6 : 1.4;
        for (int i = 0; i <= CLEAR_SAMPLES; i++) {
            Pose p = path.at(i / (double) CLEAR_SAMPLES);
            if (p.y() < level.getMinY() + 3 || p.y() > level.getMaxY() - 3) return Verdict.reject("out of world");
            if (nether && p.y() > 112) return Verdict.reject("nether roof");
            BlockPos c = BlockPos.containing(p.x(), p.y(), p.z());
            if (!level.getBlockState(c).isAir()) return Verdict.reject("lens inside a block");
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                BlockPos q = c.offset(dx, dy, dz);
                BlockState st = level.getBlockState(q);
                if (st.isAir() || (st.getCollisionShape(level, q).isEmpty() && st.getFluidState().isEmpty())) continue;
                double ex = Math.max(Math.max(q.getX() - p.x(), p.x() - (q.getX() + 1)), 0);
                double ey = Math.max(Math.max(q.getY() - p.y(), p.y() - (q.getY() + 1)), 0);
                double ez = Math.max(Math.max(q.getZ() - p.z(), p.z() - (q.getZ() + 1)), 0);
                if (ex * ex + ey * ey + ez * ez < clearance * clearance) return Verdict.reject("blocks around camera");
            }
            if (level.dimension() == Level.OVERWORLD && look.nearLimit() > 1.0 // interiors sit under their roof
                    && p.y() < height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()))
                return Verdict.reject("under terrain");
        }
        // 2) What the camera sees at several points along the move; the worst frame bounds the score.
        double worst = Double.POSITIVE_INFINITY, sum = 0;
        int samples = quick ? 1 : VIEW_SAMPLES;
        for (int i = 0; i < samples; i++) {
            Pose p = path.at(quick ? 0.5 : i / (double) (VIEW_SAMPLES - 1));
            double[] m = view(level, p, look, nether ? 96 : 160);
            double sky = m[0], near = m[1], median = m[2], interest = m[3], layers = m[4], roof = m[5];
            if (near > 0.08) return Verdict.reject("wall in front");
            if (median < look.medianMin()) return Verdict.reject("view too short");
            if (look.ceiling() && roof > 0.10) return Verdict.reject("nether roof in frame");
            if (sky < look.skyMin() - 0.10 || sky > look.skyMax() + 0.10) return Verdict.reject("sky share");
            double skyOff = sky < look.skyMin() ? look.skyMin() - sky : sky > look.skyMax() ? sky - look.skyMax() : 0;
            double subject = look.interestWant() > 0 ? Math.min(interest / look.interestWant(), 1.0) : 1.0;
            double score = 0.6 * layers + 0.8 * subject - 4.0 * skyOff - 4.0 * near - 2.0 * roof;
            worst = Math.min(worst, score);
            sum += score;
        }
        if (worst < 0.2) return Verdict.reject("weak frame");
        return new Verdict(0.6 * worst + 0.4 * sum / samples, "");
    }

    /**
     * Ray grid over a 70x40 degree frame. Returns: sky share, share of rays hitting within the near limit,
     * median distance, share of hits on the subject, depth-layer spread (entropy over near/mid/far/sky,
     * 0..1), share of rays on the Nether roof.
     */
    private static double[] view(Level level, Pose p, Look look, double maxDist) {
        double yaw = Math.toRadians(p.yaw()), pitch = Math.toRadians(p.pitch());
        double[] f = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] r = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        if (u[1] < 0) { u[0] = -u[0]; u[1] = -u[1]; u[2] = -u[2]; }
        double tx = Math.tan(Math.toRadians(35)), ty = Math.tan(Math.toRadians(20));
        int n = RAYS_X * RAYS_Y, sky = 0, near = 0, interest = 0, roof = 0, hits = 0;
        int[] bins = new int[4];
        double[] dists = new double[n];
        Vec3 from = new Vec3(p.x(), p.y(), p.z());
        for (int j = 0; j < RAYS_Y; j++) for (int i = 0; i < RAYS_X; i++) {
            double a = ((i + 0.5) / RAYS_X * 2 - 1) * tx, b = ((j + 0.5) / RAYS_Y * 2 - 1) * ty;
            double dx = f[0] + r[0] * a + u[0] * b, dy = f[1] + r[1] * a + u[1] * b, dz = f[2] + r[2] * a + u[2] * b;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= l; dy /= l; dz /= l;
            Vec3 to = from.add(dx * maxDist, dy * maxDist, dz * maxDist);
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, CollisionContext.empty()));
            int k = j * RAYS_X + i;
            if (hit.getType() == HitResult.Type.MISS) {
                dists[k] = maxDist;
                if (dy > 0 && level.dimension() != Level.NETHER) { sky++; bins[3]++; } else bins[2]++;
                continue;
            }
            double d = hit.getLocation().distanceTo(from);
            dists[k] = d;
            hits++;
            if (d < look.nearLimit()) near++;
            bins[d < 12 ? 0 : d < 48 ? 1 : 2]++;
            BlockPos hp = hit.getBlockPos();
            BlockState st = level.getBlockState(hp);
            if (look.interest().test(st)) interest++;
            if (level.dimension() == Level.NETHER && (hp.getY() >= 118 || st.is(Blocks.BEDROCK))) roof++;
        }
        Arrays.sort(dists);
        double entropy = 0;
        for (int c : bins) if (c > 0) { double q = c / (double) n; entropy -= q * Math.log(q); }
        return new double[]{sky / (double) n, near / (double) n, dists[n / 2], interest / (double) Math.max(hits, 1),
                entropy / Math.log(4), roof / (double) n};
    }

    // ------------------------------------------------------------------ locating (server thread)

    private static final Pattern COORDS = Pattern.compile("\\[\\s*(-?\\d+)\\s*[,，]\\s*(~|-?\\d+)\\s*[,，]\\s*(-?\\d+)\\s*]");

    private static BlockPos locate(MinecraftServer server, ServerLevel level, String what) {
        List<String> out = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component c) { out.add(c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack stack = server.createCommandSourceStack().withSource(capture).withLevel(level);
        server.getCommands().performPrefixedCommand(stack, "locate " + what);
        for (String line : out) {
            Matcher m = COORDS.matcher(line);
            if (m.find()) {
                int y = m.group(2).equals("~") ? 64 : Integer.parseInt(m.group(2));
                return new BlockPos(Integer.parseInt(m.group(1)), y, Integer.parseInt(m.group(3)));
            }
        }
        log("locate {} found nothing: {}", what, out);
        return null;
    }

    /** Every biome of the list that exists nearby, in list order: several places to scout. */
    private static List<BlockPos> biomes(MinecraftServer server, ServerLevel level, String... ids) {
        List<BlockPos> out = new ArrayList<>();
        for (String id : ids) {
            BlockPos p = locate(server, level, "biome " + id);
            if (p != null) out.add(level.dimension() == Level.NETHER ? p : surface(level, p));
        }
        return out;
    }

    private static List<BlockPos> structures(MinecraftServer server, ServerLevel level, String... ids) {
        List<BlockPos> out = new ArrayList<>();
        for (String id : ids) {
            BlockPos p = locate(server, level, "structure " + id);
            if (p != null) out.add(surface(level, p));
        }
        return out;
    }

    /** Heightmap of a column, generating/loading its chunk first: an unloaded chunk reports the world bottom. */
    private static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel) level.getChunk(x >> 4, z >> 4);
        return level.getHeight(type, x, z);
    }

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        if (p == null) return null;
        return new BlockPos(p.getX(), height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** A 9x9 glass pavilion standing on the water: quartz frame, oak floor, lanterns inside. */
    private static void buildPavilion(ServerLevel level, BlockPos near) {
        BlockPos water = pavilionSite(level, near);
        if (water == null) return;
        pavilionAt = water;
        BlockPos f = water.above();
        if (level.getBlockState(f.above(5)).is(Blocks.SMOOTH_QUARTZ)) return; // already built
        BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            boolean edgeX = Math.abs(dx) == 4, edgeZ = Math.abs(dz) == 4;
            level.setBlock(f.offset(dx, 0, dz), (edgeX || edgeZ ? Blocks.SMOOTH_QUARTZ : Blocks.DARK_OAK_PLANKS).defaultBlockState(), 3);
            level.setBlock(f.offset(dx, 5, dz), Blocks.SMOOTH_QUARTZ.defaultBlockState(), 3);
            for (int y = 1; y <= 4; y++) {
                BlockState st = Blocks.AIR.defaultBlockState();
                if (edgeX && edgeZ) st = Blocks.QUARTZ_PILLAR.defaultBlockState();
                else if (edgeX || edgeZ) st = Blocks.GLASS.defaultBlockState();
                level.setBlock(f.offset(dx, y, dz), st, 3);
            }
        }
        for (int dx = -4; dx <= 4; dx += 8) for (int dz = -4; dz <= 4; dz += 8)
            for (int y = -1; y >= -6 && level.getFluidState(f.offset(dx, y, dz)).is(FluidTags.WATER); y--)
                level.setBlock(f.offset(dx, y, dz), Blocks.QUARTZ_PILLAR.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx += 4) for (int dz = -2; dz <= 2; dz += 4) level.setBlock(f.offset(dx, 4, dz), lantern, 3);
        level.setBlock(f.offset(0, 1, 0), Blocks.LANTERN.defaultBlockState(), 3);
    }

    private static BlockPos pavilionAt;

    /** Open water at least 15x15 wide and two deep, nearest to the anchor. */
    private static BlockPos pavilionSite(ServerLevel level, BlockPos near) {
        for (int r = 0; r <= 96; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) for (int dz = -r; dz <= r; dz += 4) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = near.getX() + dx, z = near.getZ() + dz;
                int top = height(level, Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                boolean ok = true;
                for (int ox = -7; ox <= 7 && ok; ox += 7) for (int oz = -7; oz <= 7 && ok; oz += 7) {
                    BlockPos q = new BlockPos(x + ox, top, z + oz);
                    ok = level.getFluidState(q).is(FluidTags.WATER) && level.getFluidState(q.below()).is(FluidTags.WATER)
                            && level.getBlockState(q.above()).isAir();
                }
                if (ok) return new BlockPos(x, top, z);
            }
        }
        return null;
    }

    /** Lift curve so the path never dips into terrain or blocks (server thread; loads the chunks it needs). */
    private static double[] lift(ServerLevel level, CamPath path, double clearance) {
        int n = 96;
        double[] need = new double[n + 1];
        boolean nether = level.dimension() == Level.NETHER;
        for (int i = 0; i <= n; i++) {
            Pose p = path.at(i / (double) n);
            int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
            double y = p.y();
            if (!nether && clearance > 0) y = Math.max(y, height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + clearance);
            for (int k = 0; k < 24; k++) {
                BlockPos b = BlockPos.containing(x + 0.5, y, z + 0.5);
                if (!solid(level, b) && !solid(level, b.above())) break;
                y += 1.0;
            }
            need[i] = y - p.y();
        }
        double[] m = new double[n + 1], out = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double v = 0;
            for (int j = Math.max(0, i - 6); j <= Math.min(n, i + 6); j++) v = Math.max(v, need[j]);
            m[i] = v;
        }
        for (int i = 0; i <= n; i++) {
            double sum = 0, w = 0;
            for (int j = Math.max(0, i - 6); j <= Math.min(n, i + 6); j++) { double g = Math.exp(-(j - i) * (j - i) / 18.0); sum += m[j] * g; w += g; }
            out[i] = Math.max(sum / w, need[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------ run state

    private enum Phase { IDLE, SETUP, LOAD, WARM, CAPTURE, FINISH }
    private static Phase phase = Phase.IDLE;
    private static List<Shot> shots;
    private static int shotIndex, frame, renders, frameCount;
    private static long phaseStart;
    private static CompletableFuture<Object[]> setup;
    private static CamPath path;
    private static double[] lift;
    private static final Map<String, BlockPos> anchors = new HashMap<>();
    private static final List<Path> encoded = new ArrayList<>();
    private static final List<CompletableFuture<Boolean>> encodes = new ArrayList<>();
    private static final AtomicInteger pendingWrites = new AtomicInteger();
    private static ExecutorService io, enc;
    private static Path outDir, shotDir;
    private static double clock;
    // restored afterwards
    private static GameType savedMode;
    private static int savedFov;
    private static double savedFovEffect;
    private static boolean savedPause, savedHudHidden;

    public static boolean running() { return phase != Phase.IDLE; }

    private static volatile boolean abortRequested;
    /** Ctrl+Shift+X while filming (CinematicInputMixin). */
    public static void requestAbort() { abortRequested = true; }

    public static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (running() || mc.player == null || mc.getSingleplayerServer() == null) {
            say("请在单人世界里开始拍摄。");
            return;
        }
        String world = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize().getFileName().toString();
        boolean testMap = world.startsWith(TEST_MAP);
        rendersPerFrame = testMap ? MAP_RENDERS_PER_FRAME : RENDERS_PER_FRAME;
        warmRenders = testMap ? MAP_WARM_RENDERS : WARM_RENDERS;
        shots = testMap ? TestMapShots.script() : script();
        try {
            // Resume: the newest run that is still missing shots gets only those filmed; otherwise a new run.
            Path root = FabricLoader.getInstance().getGameDir().resolve(testMap ? "caustica-cinematic-map" : "caustica-cinematic");
            Files.createDirectories(root);
            Path latest = null;
            try (var runs = Files.list(root)) {
                latest = runs.filter(Files::isDirectory).max(Comparator.comparing(Path::getFileName)).orElse(null);
            }
            if (latest != null && missing(latest) > 0 && missing(latest) < shots.size()) outDir = latest;
            else outDir = root.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(outDir);
        } catch (Exception e) {
            say("无法创建输出目录：" + e.getMessage());
            return;
        }
        anchors.clear(); anchorLists.clear(); pavilionAt = null; encoded.clear(); encodes.clear();
        io = Executors.newFixedThreadPool(3);
        enc = Executors.newSingleThreadExecutor();
        savedMode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : GameType.CREATIVE;
        savedFov = mc.options.fov().get();
        savedFovEffect = mc.options.fovEffectScale().get();
        savedPause = mc.options.pauseOnLostFocus;
        savedHudHidden = mc.gui.hud.isHidden();
        mc.options.fov().set(70);
        mc.options.fovEffectScale().set(0.0);
        mc.options.pauseOnLostFocus = false;
        abortRequested = false;
        net.minecraft.client.KeyMapping.releaseAll();
        mc.mouseHandler.releaseMouse();
        int todo = missing(outDir);
        say((todo < shots.size() ? "补拍上次缺少的 " + todo + " 个镜头（已拍好的保留）" : "开始拍摄展示片：共 " + todo + " 个镜头")
                + "，全程自动，键盘鼠标已锁定（按 Ctrl+Shift+X 中止）。");
        command(mc.getSingleplayerServer(), "gamemode spectator @a");
        shotIndex = 0;
        enter(Phase.SETUP);
    }

    private static Path shotFile(Path dir, int i) {
        return dir.resolve(String.format(Locale.ROOT, "shot%02d_%s.mp4", i, shots.get(i).name()));
    }

    private static int missing(Path dir) {
        int n = 0;
        for (int i = 0; i < shots.size(); i++) if (!Files.exists(shotFile(dir, i))) n++;
        return n;
    }

    private static final Set<String> GALAXY_SHOTS = Set.of("night_sky", "finale");
    private static boolean aimed;

    /**
     * Rebuilds a night move so the Milky Way fills the frame: samples points along the galactic band
     * (the star field's sky frame, rotated to world by the current star angle), keeps those 25-70 degrees
     * up, and picks the one whose whole path is clear with the most open sky, nearest the bright core.
     * If no point passes, the highest band point is used anyway: the shot always shows the Milky Way.
     */
    private static CamPath aimAtGalaxy(Level level, Pose start, String shot) {
        float[] c = RtComposite.CELESTIAL;
        if (c == null) return null;
        double[] axis = {c[0], c[1], c[2]};
        double al = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (al < 1e-6) return null;
        for (int i = 0; i < 3; i++) axis[i] /= al;
        double[] core = {-0.2790, 0.5700, 0.7723}, pole = {0.9403, 0.0, 0.3402};
        double[] side = {pole[1] * core[2] - pole[2] * core[1], pole[2] * core[0] - pole[0] * core[2], pole[0] * core[1] - pole[1] * core[0]};
        Look open = new Look(0.80, 1.0, 6, 0, st -> false, 0.0, false);
        CamPath best = null, fallback = null;
        double bestScore = Double.NEGATIVE_INFINITY, highest = -90;
        for (int k = -12; k <= 12; k++) {
            double th = Math.toRadians(k * 15);
            double[] sky = new double[3];
            for (int i = 0; i < 3; i++) sky[i] = core[i] * Math.cos(th) + side[i] * Math.sin(th);
            double[] w = rotate(sky, axis, c[3]);
            double elev = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, w[1]))));
            if (elev > highest) { highest = elev; fallback = galaxyMove(start, w, shot); }
            if (elev < 25 || elev > 70) continue;
            CamPath p = galaxyMove(start, w, shot);
            Verdict v = judge(level, p, open, false);
            if (!v.ok()) continue;
            double score = v.score() - Math.abs(k) * 0.03;
            if (score > bestScore) { bestScore = score; best = p; }
        }
        log("galaxy aim for {}: {}", shot, best != null ? "clear view of the band"
                : String.format(Locale.ROOT, "no clear band point, highest one (%.0f deg)", highest));
        return best != null ? best : fallback;
    }

    private static double[] rotate(double[] v, double[] k, double ang) {
        double co = Math.cos(ang), si = Math.sin(ang), d = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
        double[] x = {k[1] * v[2] - k[2] * v[1], k[2] * v[0] - k[0] * v[2], k[0] * v[1] - k[1] * v[0]};
        return new double[]{v[0] * co + x[0] * si + k[0] * d * (1 - co), v[1] * co + x[1] * si + k[1] * d * (1 - co),
                v[2] * co + x[2] * si + k[2] * d * (1 - co)};
    }

    /** night_sky: slow pan along the band; finale: crane up while tilting from the horizon to the band. */
    private static CamPath galaxyMove(Pose start, double[] dir, String shot) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dir[0], dir[2]));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir[1]))));
        if (shot.equals("finale"))
            return pan(start.x(), start.y(), start.z(), start.x(), start.y() + 18, start.z(), yaw - 20, yaw,
                    Math.min(pitch + 30, -4f), pitch);
        return pan(start.x(), start.y(), start.z(), start.x() + fx(yaw) * 2, start.y() + 0.5, start.z() + fz(yaw) * 2,
                yaw - 14, yaw + 14, pitch + 6, pitch - 4);
    }

    private static void enter(Phase p) {
        phase = p;
        readySince = -1;
        if (p == Phase.SETUP) aimed = false;
        phaseStart = System.nanoTime();
        renders = 0;
    }

    private static double phaseSeconds() { return (System.nanoTime() - phaseStart) / 1.0e9; }

    /** GameRenderer.render HEAD: pin the camera and the shader clock for this frame. */
    public static void beginFrame() {
        if (phase == Phase.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) { abort("世界已关闭"); return; }
        if (abortRequested) { abortRequested = false; abort("按下了 Ctrl+Shift+X"); return; }
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) { abort("打开了菜单"); return; }
        if (mc.gui.screen() != null) return; // loading terrain after a dimension change
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        if (path != null && (phase == Phase.LOAD || phase == Phase.WARM || phase == Phase.CAPTURE)) {
            double t = phase == Phase.CAPTURE ? frame / (double) Math.max(frameCount - 1, 1) : 0.0;
            Pose p = path.at(t);
            double l = liftAt(t);
            double eye = player.getEyeHeight();
            player.setDeltaMovement(0, 0, 0);
            player.snapTo(p.x(), p.y() + l - eye, p.z(), p.yaw(), p.pitch());
            player.setYHeadRot(p.yaw());
            player.setOldPosAndRot();
            RtComposite.animationOverride = clock + (phase == Phase.CAPTURE ? frame / (double) FPS : 0.0);
        }
    }

    private static double readySince = -1;

    /**
     * Every chunk inside the render distance set in Options is loaded, and its surface sections (the
     * terrain top and the two sections below it) are meshed and in the ray-tracing scene. The outermost
     * ring is skipped: the game keeps it as a border and never builds it.
     */
    private static boolean terrainReady(Minecraft mc) {
        if (!mc.levelRenderer.hasRenderedAllSections()) return false;
        var level = mc.level;
        int radius = Math.max(mc.options.getEffectiveRenderDistance() - 1, 1);
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        int minY = level.getMinY(), maxY = level.getMaxY();
        for (int cx = -radius; cx <= radius; cx++) for (int cz = -radius; cz <= radius; cz++) {
            if (cx * cx + cz * cz > radius * radius) continue;
            int x = (pcx + cx) << 4, z = (pcz + cz) << 4;
            if (!level.hasChunk(pcx + cx, pcz + cz)) return false;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + 8, z + 8);
            for (int dy = 0; dy >= -32; dy -= 16) {
                int y = Math.min(Math.max(top + dy, minY), maxY - 1);
                if (!dev.comfyfluffy.caustica.rt.terrain.RtTerrain.isSectionReady(new BlockPos(x + 8, y, z + 8))) return false;
            }
        }
        return true;
    }

    private static double liftAt(double t) {
        if (lift == null) return 0;
        double f = t * (lift.length - 1);
        int i = Math.min((int) f, lift.length - 2);
        return lerp(lift[i], lift[i + 1], f - i);
    }

    /** GameRenderer.render TAIL: advance the schedule; capture when the frame has settled. */
    public static void endFrame() {
        if (phase == Phase.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) { abort("服务器已关闭"); return; }
        if (mc.gui.screen() != null) { if (phase == Phase.LOAD) phaseStart = System.nanoTime(); return; }
        renders++;
        switch (phase) {
            case SETUP -> {
                if (setup == null && Files.exists(shotFile(outDir, shotIndex))) {
                    // Already filmed in this run: keep it for the final cut and move on.
                    encoded.add(shotFile(outDir, shotIndex));
                    log("shot {} '{}' already filmed, kept", shotIndex, shots.get(shotIndex).name());
                    nextShot();
                    return;
                }
                if (setup == null) {
                    Shot shot = shots.get(shotIndex);
                    command(server, "tick unfreeze");
                    command(server, shot.rain() ? "weather rain" : "weather clear");
                    setup = server.submit(() -> prepare(server, shot));
                }
                if (!setup.isDone()) return;
                Object[] r = setup.join();
                setup = null;
                if (r == null) { log("skip shot {}", shots.get(shotIndex).name()); nextShot(); return; }
                path = (CamPath) r[0];
                lift = (double[]) r[1];
                Shot shot = shots.get(shotIndex);
                Pose p0 = path.at(0);
                command(server, String.format(Locale.ROOT, "execute in %s run tp @a %.2f %.2f %.2f %.1f %.1f",
                        shot.dim().identifier(), p0.x(), p0.y() + lift[0] - 1.62, p0.z(), p0.yaw(), p0.pitch()));
                log("shot {} '{}' at {}", shotIndex, shot.name(), anchors.get(shot.locateKey()));
                enter(Phase.LOAD);
            }
            case LOAD -> {
                Shot shot = shots.get(shotIndex);
                if (mc.level.dimension() != shot.dim()) { phaseStart = System.nanoTime(); return; }
                // Film only once every terrain section the camera will see is meshed, uploaded and in
                // the ray-tracing scene, and the vanilla renderer has nothing left to build.
                boolean ready = terrainReady(mc);
                if (!ready) readySince = -1;
                else if (readySince < 0) readySince = phaseSeconds();
                boolean settled = ready && phaseSeconds() - readySince >= READY_HOLD;
                if (phaseSeconds() < LOAD_SECONDS || (!settled && phaseSeconds() < LOAD_TIMEOUT)) return;
                if (!settled) log("shot {} filmed with terrain still loading", shot.name());
                if (LOD_SHOTS.contains(shot.name()) && phaseSeconds() < LOD_WAIT) return;
                command(server, "time set " + shot.dayTime());
                TestMapShots.applySun(shot.name());
                command(server, "tick freeze");
                clock = 100.0 + shotIndex * 37.0; // distinct cloud/wave state per shot
                enter(Phase.WARM);
            }
            case WARM -> {
                Shot warmShot = shots.get(shotIndex);
                if (!aimed && GALAXY_SHOTS.contains(warmShot.name()) && renders >= 20) {
                    // The star field has its time-of-day rotation now: point the move at the Milky Way.
                    aimed = true;
                    CamPath a = aimAtGalaxy(mc.level, path.at(0), warmShot.name());
                    if (a != null) { path = a; renders = 0; }
                }
                if (renders < warmRenders) return;
                Shot shot = shots.get(shotIndex);
                frameCount = (int) Math.round(shot.seconds() * FPS);
                frame = 0;
                try {
                    shotDir = outDir.resolve(String.format(Locale.ROOT, "shot%02d_%s", shotIndex, shot.name()));
                    Files.createDirectories(shotDir);
                } catch (Exception e) { abort("无法写入：" + e.getMessage()); return; }
                enter(Phase.CAPTURE);
            }
            case CAPTURE -> {
                if (renders < rendersPerFrame || pendingWrites.get() > 6) return;
                Path file = shotDir.resolve(String.format(Locale.ROOT, "f%05d.png", frame));
                pendingWrites.incrementAndGet();
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> io.execute(() -> {
                    try (image) { image.writeToFile(file); }
                    catch (Exception e) { CausticaMod.LOGGER.error("Cinematic frame write failed", e); }
                    finally { pendingWrites.decrementAndGet(); }
                }));
                frame++;
                renders = 0;
                // Time-lapse: the sun moves a step per captured frame, so light sweeps across the room.
                Long sunEnd = TestMapShots.TIMELAPSE.get(shots.get(shotIndex).name());
                if (sunEnd != null && frame < frameCount)
                    command(server, "time set " + Math.round(lerp(shots.get(shotIndex).dayTime(), sunEnd, frame / (double) (frameCount - 1))));
                if (frame >= frameCount) finishShot();
            }
            case FINISH -> {
                if (pendingWrites.get() == 0 && encodes.stream().allMatch(CompletableFuture::isDone)) assemble();
            }
            default -> {}
        }
    }

    private static Object[] prepare(MinecraftServer server, Shot shot) {
        try {
            ServerLevel level = server.getLevel(shot.dim());
            if (level == null) return null;
            CamPath fixed = TestMapShots.PATHS.get(shot.name());
            if (fixed != null) return new Object[]{fixed, new double[97]};
            List<BlockPos> found = anchorLists.get(shot.locateKey());
            if (found == null) {
                found = shot.locator().find(server, level);
                anchorLists.put(shot.locateKey(), found);
            }
            if (found.isEmpty()) return null;
            List<BlockPos> search = found;
            if (shot.build() != null) {
                // The pavilion is built once at the first suitable water; its shots work around that spot.
                if (pavilionAt == null) for (BlockPos a : found) { buildPavilion(level, a); if (pavilionAt != null) break; }
                if (pavilionAt == null) return null;
                search = List.of(pavilionAt.above());
            }
            CamPath p = scout(level, search, shot);
            if (p == null) return null;
            anchors.put(shot.locateKey(), BlockPos.containing(p.at(0).x(), p.at(0).y(), p.at(0).z()));
            return new Object[]{p, new double[97]};
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Cinematic shot setup failed: " + shot.name(), t);
            return null;
        }
    }
    private static final Map<String, List<BlockPos>> anchorLists = new HashMap<>();

    private static void finishShot() {
        Path dir = shotDir, mp4 = outDir.resolve(dir.getFileName() + ".mp4");
        encoded.add(mp4);
        encodes.add(CompletableFuture.supplyAsync(() -> {
            while (pendingWrites.get() > 0) try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
            boolean ok = ffmpeg(List.of("-y", "-framerate", "" + FPS, "-i", dir.resolve("f%05d.png").toString(),
                    "-c:v", "libx264", "-preset", "medium", "-crf", "12", "-pix_fmt", "yuv420p", mp4.toString()));
            if (ok) try (var files = Files.list(dir)) { files.forEach(f -> f.toFile().delete()); dir.toFile().delete(); }
                    catch (Exception ignored) {}
            return ok;
        }, enc));
        nextShot();
    }

    private static void nextShot() {
        path = null;
        lift = null;
        shotIndex++;
        if (shotIndex >= shots.size()) { enter(Phase.FINISH); RtComposite.animationOverride = Double.NaN; return; }
        enter(Phase.SETUP);
    }

    private static void assemble() {
        phase = Phase.IDLE;
        restore();
        long clips = encoded.stream().filter(Files::exists).count();
        if (clips == 0) { say("拍摄结束，但没有成功编码的镜头，请看日志。"); return; }
        // Shots stay separate clips: the edit is cut by hand from them, not from a rough merge.
        say("全部镜头拍完，共 " + clips + " 个，分别保存在 " + outDir);
        io.shutdown();
        enc.shutdown();
    }



    private static boolean ffmpeg(List<String> args) {
        for (String exe : List.of("ffmpeg", "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe")) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(exe);
                cmd.add("-hide_banner");
                cmd.add("-loglevel");
                cmd.add("error");
                cmd.addAll(args);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(outDir.resolve("ffmpeg.log").toFile()).start();
                return p.waitFor() == 0;
            } catch (java.io.IOException notFound) {
                // try the next location
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        CausticaMod.LOGGER.error("ffmpeg not found");
        return false;
    }

    private static void abort(String why) {
        if (phase == Phase.IDLE) return;
        phase = Phase.IDLE;
        restore();
        if (io != null) io.shutdown();
        if (enc != null) enc.shutdown();
        say("拍摄已中止：" + why + "。已拍好的镜头在 " + outDir);
    }

    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        RtComposite.animationOverride = Double.NaN;
        TestMapShots.restoreSun();
        path = null;
        lift = null;
        setup = null;
        mc.options.fov().set(savedFov);
        mc.options.fovEffectScale().set(savedFovEffect);
        mc.options.pauseOnLostFocus = savedPause;
        if (mc.gui.hud.isHidden() != savedHudHidden) mc.gui.hud.toggle();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            command(server, "tick unfreeze");
            command(server, "gamemode " + savedMode.getName() + " @a");
        }
    }

    private static void command(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
    }

    private static void log(String fmt, Object... args) { CausticaMod.LOGGER.info("[cinematic] " + fmt, args); }

    private static void say(String text) {
        var mc = Minecraft.getInstance();
        try { mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Caustica] " + text)); }
        catch (Throwable ignored) { CausticaMod.LOGGER.info("[Caustica] {}", text); }
    }

    private CinematicDirector() {}
}
