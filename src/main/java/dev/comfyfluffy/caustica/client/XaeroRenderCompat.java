package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.joml.Matrix4fc;

/** Preserves Xaero's frame-matrix notification when vanilla LevelRenderer is cancelled. */
public final class XaeroRenderCompat {
    private XaeroRenderCompat() {}
    private static final org.joml.Matrix4f projectionSnapshot = new org.joml.Matrix4f();
    private static final class OptionalApi {
        static MethodHandle updateProjection;
        static MethodHandle updateView = resolve();
        private static MethodHandle resolve() {
            if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) return null;
            try {
                // Verified in Xaero Minimap 26.6.0 for MC 26.2: its optional LevelRenderer
                // HEAD injection calls exactly this method. Do not alter map/subworld coordinates.
                Class<?> api = Class.forName("xaero.common.core.XaeroMinimapCore", false,
                    XaeroRenderCompat.class.getClassLoader());
                updateProjection = MethodHandles.publicLookup().findStatic(api, "onProjectionMatrix",
                    MethodType.methodType(void.class, org.joml.Matrix4f.class));
                return MethodHandles.publicLookup().findStatic(api, "onWorldModelViewMatrix",
                    MethodType.methodType(void.class, Matrix4fc.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                CausticaMod.LOGGER.warn("Xaero matrix notification API unavailable; compatibility disabled", e);
                return null;
            }
        }
    }
    public static void beforeCancelWorld(Matrix4fc view) {
        MethodHandle update = OptionalApi.updateView;
        if (update == null) return;
        try {
            projectionSnapshot.set(dev.comfyfluffy.caustica.rt.RtComposite.INSTANCE.capturedWorldProjection());
            OptionalApi.updateProjection.invokeExact(projectionSnapshot);
            update.invokeExact(view);
        }
        catch (Throwable e) {
            OptionalApi.updateView = null;
            CausticaMod.LOGGER.warn("Xaero matrix notification failed; compatibility disabled", e);
        }
    }
}
