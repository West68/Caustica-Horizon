package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Better Player Model (YSM models) drives its per-frame animation from LevelRenderer.render HEAD/RETURN:
 * world-render mode on, EntityRenderCache.tick (async pose updates), then clear (await + release). Caustica
 * cancels that method while RT draws the world, so the models never posed and were missing from the RT
 * capture. Replay the same pair around Caustica's own entity capture. Render thread only.
 */
public final class BetterPlayerModelCompat {
    private static final MethodHandle AVAILABLE, IS_WORLD, SET_WORLD, TICK, CLEAR;
    private static boolean opened, failed;
    private static net.minecraft.client.CameraType realCamera;

    static {
        // BPM's GPU renderer is OpenGL-only (GL SSBO compute); on the Vulkan backend its GL calls kill the process.
        // Its own switch keeps the CPU renderer, which Caustica captures for RT. Respect an explicit user value.
        if (System.getProperty("OYSM_DISABLE_GPU") == null) System.setProperty("OYSM_DISABLE_GPU", "true");
        MethodHandle available = null, isWorld = null, setWorld = null, tick = null, clear = null;
        if (FabricLoader.getInstance().isModLoaded("better_player_model")) {
            try {
                var lookup = MethodHandles.publicLookup();
                Class<?> mod = Class.forName("com.elfmcys.yesstevemodel.YesSteveModel");
                Class<?> preview = Class.forName("com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer");
                Class<?> cache = Class.forName("com.elfmcys.yesstevemodel.client.entity.EntityRenderCache");
                available = lookup.findStatic(mod, "isAvailable", MethodType.methodType(boolean.class));
                isWorld = lookup.findStatic(preview, "isWorldRender", MethodType.methodType(boolean.class));
                setWorld = lookup.findStatic(preview, "setWorldRenderMode", MethodType.methodType(void.class, boolean.class));
                tick = lookup.findStatic(cache, "tick", MethodType.methodType(void.class, float.class));
                clear = lookup.findStatic(cache, "clear", MethodType.methodType(void.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                available = null;
                CausticaMod.LOGGER.warn("Better Player Model RT bridge unavailable; its models may be missing under RT", e);
            }
        }
        AVAILABLE = available; IS_WORLD = isWorld; SET_WORLD = setWorld; TICK = tick; CLEAR = clear;
    }

    private BetterPlayerModelCompat() {}

    /** Called from client init so the GPU switch is set before BPM first checks its renderer. */
    public static void init() {}

    /**
     * The player's camera mode. In first person BPM hides the local model or gives it the camera-only arm pose,
     * but RT shows that body in mirrors, shadows and GI, so the capture runs in F5 mode (see beginWorldFrame).
     */
    public static boolean realFirstPerson() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return (realCamera != null ? realCamera : mc.options.getCameraType()).isFirstPerson();
    }

    /** Before the RT entity capture of a frame whose vanilla world pass was cancelled. */
    public static void beginWorldFrame(float partialTick) {
        opened = false;
        if (AVAILABLE == null || failed || !VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame()) return;
        try {
            if (!(boolean) AVAILABLE.invokeExact()) return;
            // BPM's own HEAD hook may already have run before the cancel; never tick the same frame twice
            // (a second tick per frame corrupted the model's pose in every view, RT on or off).
            if (!(boolean) IS_WORLD.invokeExact()) {
                SET_WORLD.invokeExact(true);
                TICK.invokeExact(partialTick);
            }
            opened = true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("Better Player Model RT bridge disabled", t);
        }
    }

    /** After the capture: the RETURN half that the cancelled LevelRenderer.render never reaches. */
    public static void endWorldFrame() {
        if (!opened) { restoreCamera(); return; }
        opened = false;
        try {
            CLEAR.invokeExact();
            SET_WORLD.invokeExact(false);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("Better Player Model RT bridge disabled", t);
        } finally {
            restoreCamera();
        }
    }

    private static void restoreCamera() {
        if (realCamera == null) return;
        net.minecraft.client.Minecraft.getInstance().options.setCameraType(realCamera);
        realCamera = null;
    }
}
