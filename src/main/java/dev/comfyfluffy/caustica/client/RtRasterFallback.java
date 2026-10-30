package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.Profiler;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Generic raster fallback for everything RT does not capture. Under RT the vanilla level renderer is
 * cancelled at its head, so every mod that draws during it never runs. Each frame this:
 * <ol>
 *   <li>writes the RT primary depth into the transparent overlay layer, so any raster draw into it is
 *       occluded by the traced world with ordinary depth testing;</li>
 *   <li>draws the Litematica schematic bridge into that layer;</li>
 *   <li>replays every malilib world renderer (MiniHUD, Tweakeroo, ... — Litematica's own is handled by
 *       its bridge) into the same layer.</li>
 * </ol>
 * These draws get no RT lighting, but they are visible, correctly occluded and composited under the GUI.
 * Any failure disables only the failing step, never RT.
 */
public final class RtRasterFallback {
    private static final boolean MALILIB = FabricLoader.getInstance().isModLoaded("malilib");
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Vector4f FOG_COLOR = new Vector4f();
    private static CameraRenderState camera;
    private static GpuBufferSlice fog;
    private static net.minecraft.client.renderer.RenderBuffers renderBuffers;
    private static boolean captured, depthFailed, malilibFailed, active;
    private static RenderTarget target;
    private static List<?> worldLast, preWeather;
    private static Method renderLast, renderPreWeather;

    /** From the level renderer's head, right before RT cancels it. */
    public static void capture(CameraRenderState state, Matrix4fc view, GpuBufferSlice terrainFog, Vector4f fogColor,
                               net.minecraft.client.renderer.RenderBuffers buffers) {
        camera = state;
        renderBuffers = buffers;
        VIEW.set(view);
        fog = terrainFog;
        FOG_COLOR.set(fogColor);
        captured = true;
    }

    public static void draw(RenderTarget main) {
        boolean run = captured && camera != null && VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame();
        captured = false;
        if (run && !depthFailed) {
            try {
                LitematicaMaterials.prepare(VIEW, RtComposite.INSTANCE.capturedWorldProjection(), main.width, main.height);
                LitematicaMaterials.fillDepth(RtUiOverlay.beginCompositeLayer(main));
            } catch (Throwable t) {
                depthFailed = true;
                CausticaMod.LOGGER.error("RT raster fallback: depth fill unavailable; overlays draw unoccluded", t);
            }
        }
        LitematicaCompat.draw(main);
        if (run && MALILIB && !malilibFailed) drawMalilib(main);
    }

    /** The overlay layer while a fallback draw runs, else {@code original}. */
    public static RenderTarget target(RenderTarget original) { return active && target != null ? target : original; }

    public static boolean active() { return active; }

    private static void drawMalilib(RenderTarget main) {
        var projectionBuffer = LitematicaMaterials.projection();
        if (projectionBuffer == null) return;
        var oldFog = RenderSystem.getShaderFog();
        var oldProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldType = RenderSystem.getProjectionType();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            resolveMalilib();
            target = RtUiOverlay.beginCompositeLayer(main);
            active = true;
            modelView.set(VIEW);
            RenderSystem.setProjectionMatrix(projectionBuffer.slice(), ProjectionType.PERSPECTIVE);
            var frustum = new Frustum(VIEW, new Matrix4f(RtComposite.INSTANCE.capturedWorldProjection()));
            frustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
            var profiler = Profiler.get();
            for (Object renderer : preWeather) if (!ownedByLitematica(renderer))
                renderPreWeather.invoke(renderer, target, VIEW, camera, frustum, renderBuffers, fog, FOG_COLOR, profiler);
            for (Object renderer : worldLast) if (!ownedByLitematica(renderer))
                renderLast.invoke(renderer, target, VIEW, camera, frustum, renderBuffers, fog, FOG_COLOR, profiler);
        } catch (Throwable t) {
            malilibFailed = true;
            CausticaMod.LOGGER.error("RT raster fallback: malilib world renderers disabled under RT", t);
        } finally {
            active = false;
            target = null;
            modelView.popMatrix();
            RenderSystem.setShaderFog(oldFog);
            RenderSystem.setProjectionMatrix(oldProjection, oldType);
        }
    }

    /** Litematica's world renderer is replayed by {@link LitematicaCompat} with its schematic bridge. */
    private static boolean ownedByLitematica(Object renderer) {
        return renderer.getClass().getName().startsWith("fi.dy.masa.litematica.");
    }

    private static void resolveMalilib() throws ReflectiveOperationException {
        if (worldLast != null) return;
        Class<?> handler = Class.forName("fi.dy.masa.malilib.event.RenderEventHandler");
        Object instance = handler.getMethod("getInstance").invoke(null);
        worldLast = (List<?>) field(handler, "worldLastRenderers").get(instance);
        preWeather = (List<?>) field(handler, "worldPreWeatherRenderers").get(instance);
        Class<?> renderer = Class.forName("fi.dy.masa.malilib.interfaces.IRenderer");
        for (Method m : renderer.getMethods()) {
            if (m.getName().equals("onRenderWorldLast") && m.getParameterCount() == 8) renderLast = m;
            if (m.getName().equals("onRenderWorldPreWeather") && m.getParameterCount() == 8) renderPreWeather = m;
        }
        if (renderLast == null || renderPreWeather == null) throw new NoSuchMethodException("malilib IRenderer world hooks");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private RtRasterFallback() {}
}
