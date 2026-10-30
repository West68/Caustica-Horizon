package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.client.CinematicDirector.CamPath;
import dev.comfyfluffy.caustica.client.CinematicDirector.Shot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed camera moves for the "Ray Tracing testing place" map (Jikali, Ms-PL). The tracks were found offline
 * from the map's region files: each is a roofed, straight line through its room with clear air around the
 * lens for the whole move, checked with a voxel preview. Day times were chosen by tracing the sun (30 degree
 * south tilt) through the map: a shot about coloured blocks only runs while sunlight actually reaches them.
 * Yaw follows Minecraft: -90 looks along +X, 0 along +Z.
 */
final class TestMapShots {
    static final Map<String, CamPath> PATHS = new HashMap<>();
    /** Shots filmed as a time-lapse: the day time at the last frame (the first is the shot's dayTime). */
    static final Map<String, Long> TIMELAPSE = new HashMap<>();
    /**
     * Sun path tilt for a shot, in degrees (the map's author asks Java players to adjust the sun path). Applied
     * in memory while that shot films and restored afterwards; the saved config is never touched.
     */
    static final Map<String, Float> SUN_TILT = new HashMap<>();
    private static Float savedTilt;
    private TestMapShots() {}

    private static final long DAY = 24000L;

    static List<Shot> script() {
        PATHS.clear();
        TIMELAPSE.clear();
        SUN_TILT.clear();
        List<Shot> s = new ArrayList<>();
        // Nether garden hall: nylium, huge fungi and hanging lanterns under a netherrack ceiling.
        add(s, "map_nether_hall", DAY + 6000, 8.0, CinematicDirector.dolly(12.5, -55.4, -106.5, 30.5, -55.4, -106.5, -90f, 4f, 0f));
        // Wool rainbow along a corridor under a roof slit. The slit only lets sun onto the east-facing wool in the
        // morning: from just after sunrise the beam sweeps down the colours and onto the floor.
        add(s, "map_rainbow_wool", DAY + 300, 10.0, CinematicDirector.pan(246.5, -54.4, -101.5, 246.5, -54.4, -90.5, 8f, 20f, 3f, 0f));
        TIMELAPSE.put("map_rainbow_wool", DAY + 2600);
        // Gallery ceiling of sea lanterns behind red, green and blue stained glass, at night: the only light is
        // the lamps filtered through the glass, so the room is lit in their colours.
        add(s, "map_rgb_ceiling", DAY + 18000, 8.0, CinematicDirector.pan(22.5, -57.4, 10.5, 22.5, -57.4, 21.5, 95f, 75f, -16f, -22f));
        // Sandstone hall whose north wall is a row of stained-glass windows with sea lanterns behind them. No
        // sunlight reaches it at any sun path, so it is filmed at night: the lamps through the glass are the light.
        add(s, "map_lamp_windows", DAY + 18000, 9.0, CinematicDirector.pan(-39.5, -45.4, 48.5, -25.5, -45.4, 48.5, -115f, -100f, 2f, 0f));
        // Tall quartz hall under skylights. With a flat sun path the noon sun falls straight through them, and
        // the time-lapse walks the light shafts across the hall while the camera tilts up into the windows.
        add(s, "map_quartz_hall", DAY + 5200, 9.0, CinematicDirector.dolly(82.5, -56.4, -106.5, 97.5, -56.4, -106.5, -90f, 2f, -14f));
        TIMELAPSE.put("map_quartz_hall", DAY + 7400);
        SUN_TILT.put("map_quartz_hall", 0f);
        // Corridor with a glowstone ceiling, dusk into night: daylight fades until only the emissive light and its
        // bounce off the walls remain.
        add(s, "map_glow_corridor", DAY + 12000, 9.0, CinematicDirector.dolly(-56.5, -51.4, -31.5, -56.5, -51.4, -8.5, 0f, 3f, -2f));
        TIMELAPSE.put("map_glow_corridor", DAY + 14000);
        // Walled spruce forest at golden hour, looking into the trees.
        add(s, "map_forest", DAY + 10000, 10.0, CinematicDirector.pan(176.5, -57.4, -143.5, 204.5, -57.4, -143.5, -60f, -80f, 0f, -4f));
        TIMELAPSE.put("map_forest", DAY + 12300);
        return s;
    }

    /** Before a shot films: its own sun tilt, or the player's. */
    static void applySun(String name) {
        var tilt = dev.comfyfluffy.caustica.CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT;
        if (savedTilt == null) savedTilt = tilt.value();
        Float deg = SUN_TILT.get(name);
        tilt.set(deg != null ? (float) Math.toRadians(deg) : savedTilt);
    }

    /** After filming (or an abort): the player's sun tilt again. */
    static void restoreSun() {
        if (savedTilt == null) return;
        dev.comfyfluffy.caustica.CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT.set(savedTilt);
        savedTilt = null;
    }

    private static void add(List<Shot> s, String name, long dayTime, double seconds, CamPath path) {
        PATHS.put(name, path);
        s.add(new Shot(name, Level.OVERWORLD, name, (srv, lvl) -> List.of(BlockPos.ZERO), dayTime, false, seconds, null, null));
    }
}
