package dev.comfyfluffy.caustica.rt.entity;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Optional First Person Model API bridge; its camera pose must not replace the reflection pose. */
public final class FirstPersonModelCompat {
    private static Object core, logic;
    private static Method shouldApply, updateOffset, getOffset, rendering;
    private static boolean initialized, fullBody, cameraCapture;

    public static boolean cameraCapture() { return cameraCapture; }
    public static void cameraCapture(boolean value) { cameraCapture = value; }

    private static void init() {
        if (initialized) return;
        initialized = true;
        if (!FabricLoader.getInstance().isModLoaded("firstperson")) return;
        try {
            Class<?> api = Class.forName("dev.tr7zw.firstperson.api.FirstPersonAPI");
            Class<?> type = Class.forName("dev.tr7zw.firstperson.FirstPersonModelCore");
            core = type.getField("instance").get(null);
            logic = type.getMethod("getLogicHandler").invoke(core);
            shouldApply = logic.getClass().getMethod("shouldApplyThirdPerson", boolean.class);
            updateOffset = logic.getClass().getMethod("updatePositionOffset", Entity.class, float.class);
            getOffset = logic.getClass().getMethod("getOffset");
            rendering = api.getMethod("isRenderingPlayer");
            Class<?> activation = Class.forName("dev.tr7zw.firstperson.api.ActivationHandler");
            Object handler = Proxy.newProxyInstance(activation.getClassLoader(), new Class<?>[]{activation},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "preventFirstperson" -> fullBody;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Caustica reflection capture";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            api.getMethod("registerPlayerHandler", Object.class).invoke(null, handler);
        } catch (ReflectiveOperationException error) {
            core = null;
            dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn("First Person Model RT API unavailable", error);
        }
    }

    public static boolean active() {
        init();
        if (core == null) return false;
        try { return (boolean) shouldApply.invoke(logic, false); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public static boolean rendering() {
        if (core == null || fullBody) return false;
        try { return (boolean) rendering.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public static Vec3 cameraOffset(Entity entity, float partial) {
        try {
            updateOffset.invoke(logic, entity, partial);
            return (Vec3) getOffset.invoke(logic);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public static Scope fullBodyScope(boolean requested) {
        init();
        if (!requested || core == null) return Scope.NONE;
        boolean previous = fullBody;
        fullBody = true;
        return new Scope(previous, true);
    }

    public record Scope(boolean previous, boolean restore) implements AutoCloseable {
        private static final Scope NONE = new Scope(false, false);
        @Override public void close() { if (restore) fullBody = previous; }
    }

    private FirstPersonModelCompat() {}
}
