package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.reflect.Field;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Sodium's "animate only visible textures" ticks an atlas animation only after its own terrain renderer marked
 * the sprite as drawn. While RT draws the world that renderer never runs, so every animated block texture
 * (campfire and fire flames, stonecutter saw, sea lanterns, prismarine, ...) froze on one frame. Animate all
 * of them while RT runs and give the user's value back when it stops. Render thread only.
 */
public final class SodiumAnimationCompat {
    private static Object performance;
    private static Field onlyVisible;
    private static boolean resolved;
    private static Boolean saved;

    private SodiumAnimationCompat() {}

    private static void resolve() {
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded("sodium")) return;
        try {
            Object options = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod").getMethod("options").invoke(null);
            Object perf = options.getClass().getField("performance").get(options);
            onlyVisible = perf.getClass().getField("animateOnlyVisibleTextures");
            performance = perf;
        } catch (ReflectiveOperationException | LinkageError e) {
            onlyVisible = null;
            CausticaMod.LOGGER.warn("Sodium animation bridge unavailable; animated block textures may freeze under RT", e);
        }
    }

    public static void update(boolean rtActive) {
        if (!resolved) resolve();
        if (onlyVisible == null) return;
        try {
            if (rtActive) {
                if (saved == null) saved = onlyVisible.getBoolean(performance);
                if (onlyVisible.getBoolean(performance)) onlyVisible.setBoolean(performance, false);
            } else if (saved != null) {
                onlyVisible.setBoolean(performance, saved);
                saved = null;
            }
        } catch (IllegalAccessException ignored) {
        }
    }
}
