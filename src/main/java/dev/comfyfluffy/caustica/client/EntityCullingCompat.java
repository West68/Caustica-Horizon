package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.reflect.Field;
import net.fabricmc.loader.api.FabricLoader;

/**
 * EntityCulling's tick culling skips the rotation/AI half of the client tick for entities it marks culled or
 * out of camera. Those flags come from its vanilla world-render hooks, which never run while RT draws the
 * world, so every mob kept a frozen body yaw: walking backwards/sideways while the head swung around. RT also
 * shows mobs in reflections and shadows that vanilla would cull. Turn tick culling off while RT runs and give
 * the user's value back when it stops. Render thread only.
 *
 * <p>Its block-entity culling has the same problem: the occlusion/frustum state it tests is computed for the
 * vanilla raster view, so under RT an enchanting book or chest blinked in and out, and off-screen block
 * entities never reached shadows or reflections. {@code skipBlockEntityCulling} is forced on while RT runs.
 */
public final class EntityCullingCompat {
    private static final Object CONFIG;
    private static final Field TICK_CULLING;
    private static final Field SKIP_BE_CULLING;
    private static Boolean saved;
    private static Boolean savedSkipBe;

    static {
        Object config = null;
        Field tickCulling = null;
        Field skipBe = null;
        if (FabricLoader.getInstance().isModLoaded("entityculling")) {
            try {
                Class<?> base = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
                Object instance = base.getField("instance").get(null);
                config = instance == null ? null : base.getField("config").get(instance);
                if (config != null) tickCulling = config.getClass().getField("tickCulling");
                if (config != null) {
                    try { skipBe = config.getClass().getField("skipBlockEntityCulling"); }
                    catch (NoSuchFieldException ignored) { skipBe = null; }
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                config = null;
                CausticaMod.LOGGER.warn("EntityCulling bridge unavailable; mobs may freeze their rotation under RT", e);
            }
        }
        CONFIG = config;
        TICK_CULLING = config == null ? null : tickCulling;
        SKIP_BE_CULLING = config == null ? null : skipBe;
    }

    private EntityCullingCompat() {}

    public static void update(boolean rtActive) {
        if (SKIP_BE_CULLING != null) {
            try {
                if (rtActive) {
                    if (savedSkipBe == null) savedSkipBe = SKIP_BE_CULLING.getBoolean(CONFIG);
                    if (!SKIP_BE_CULLING.getBoolean(CONFIG)) SKIP_BE_CULLING.setBoolean(CONFIG, true);
                } else if (savedSkipBe != null) {
                    SKIP_BE_CULLING.setBoolean(CONFIG, savedSkipBe);
                    savedSkipBe = null;
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        if (TICK_CULLING == null) return;
        try {
            if (rtActive) {
                if (saved == null) saved = TICK_CULLING.getBoolean(CONFIG);
                if (TICK_CULLING.getBoolean(CONFIG)) TICK_CULLING.setBoolean(CONFIG, false);
            } else if (saved != null) {
                TICK_CULLING.setBoolean(CONFIG, saved);
                saved = null;
            }
        } catch (IllegalAccessException ignored) {
        }
    }
}
