package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.*;
import org.joml.*;
import java.lang.reflect.Method;
import java.util.*;

/** Optional Litematica 26.2 bridge. Original schematic terrain/culling/upload code remains the owner. */
public final class LitematicaCompat {
    private static final boolean INSTALLED=FabricLoader.getInstance().isModLoaded("litematica");
    private static Object renderer;
    private static final Map<String,Method> METHODS=new HashMap<>();
    private static boolean failed,prepared,drawing,logged;
    private static RenderTarget target;
    private static final Matrix4f VIEW=new Matrix4f(),PROJECTION=new Matrix4f();
    private static final Set<RenderPipeline> FAILED_PIPELINES=Collections.newSetFromMap(new IdentityHashMap<>());
    private static Object call(String name,Object... args) throws ReflectiveOperationException {
        return METHODS.get(name).invoke(renderer,args);
    }
    private static void resolve() throws ReflectiveOperationException {
        if(renderer!=null)return;
        Class<?> type=Class.forName("fi.dy.masa.litematica.render.LitematicaRenderer");
        renderer=type.getMethod("getInstance").invoke(null);
        for(Method m:type.getMethods())METHODS.put(m.getName(),m);
    }
    public static void prepare(CameraRenderState state,Matrix4fc view,GpuBufferSlice fog,DeltaTracker delta) {
        prepared=false;
        if(!INSTALLED || failed || !VanillaRenderController.rtRuntimeWorkRequested())return;
        try {
            resolve();
            Object world=call("getWorldRenderer");
            if(!(boolean)world.getClass().getMethod("hasWorld").invoke(world))return;
            var mc=Minecraft.getInstance();var camera=mc.gameRenderer.mainCamera();var profiler=Profiler.get();
            VIEW.set(view);PROJECTION.set(RtComposite.INSTANCE.capturedWorldProjection());
            var frustum=new Frustum(VIEW,PROJECTION);frustum.prepare(state.pos.x,state.pos.y,state.pos.z);
            call("updateCameraState",camera,delta.getGameTimeDeltaPartialTick(false),state);
            call("updateConfigState");
            call("piecewisePrepare",frustum,profiler);
            call("piecewiseUpdate",camera,profiler);
            call("scheduleTranslucentSorting",camera.position(),profiler);
            call("capturePreMainValues",state,fog,profiler);
            call("uploadRemainingBuffers",camera,delta,profiler);
            call("piecewisePrepareBlockLayers",VIEW,profiler);
            prepared=true;
        } catch(Throwable t) { fail("prepare",t); }
    }
    public static void draw(RenderTarget main) {
        if(!prepared || failed || !VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame())return;
        prepared=false;
        var oldFog=RenderSystem.getShaderFog();var oldProjection=RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldType=RenderSystem.getProjectionType();
        var modelView=RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            // Block batches store VIEW in their UBOs; blue overlays read this global stack later.
            modelView.set(VIEW);
            LitematicaMaterials.prepare(VIEW,PROJECTION,main.width,main.height);
            if(LitematicaMaterials.projection()==null)return;
            target=RtUiOverlay.beginCompositeLayer(main);drawing=true;
            RenderSystem.setProjectionMatrix(LitematicaMaterials.projection().slice(),ProjectionType.PERSPECTIVE);
            call("piecewiseDrawBlockLayerGroup",ChunkSectionLayerGroup.OPAQUE,null);
            call("piecewiseDrawBlockLayerGroup",ChunkSectionLayerGroup.TRANSLUCENT,null);
            call("piecewiseRenderOverlay",Profiler.get());
            drawBoxes();
            if(!logged) {logged=true;CausticaMod.LOGGER.info("Litematica independent Vulkan PBR/parallax bridge active; schematic geometry excluded from TLAS");}
        } catch(Throwable t) { fail("draw",t); }
        finally {
            drawing=false;target=null;
            modelView.popMatrix();
            RenderSystem.setShaderFog(oldFog);
            RenderSystem.setProjectionMatrix(oldProjection,oldType);
        }
    }
    private static Object overlay;
    private static Method[] boxMethods;
    private static boolean boxesFailed,overlayDepthFailed;
    /** Selection, placement and verifier boxes: Litematica draws them from malilib's world-last pass,
     * which the skipped vanilla world never reaches under RT. Same calls, same order as its RenderHandler. */
    private static void drawBoxes() {
        if(boxesFailed)return;
        try {
            if(overlay==null) {
                Class<?> type=Class.forName("fi.dy.masa.litematica.render.OverlayRenderer");
                overlay=type.getMethod("getInstance").invoke(null);
                boxMethods=new Method[]{type.getMethod("renderBoxes",ProfilerFiller.class),
                        type.getMethod("renderSchematicVerifierMismatches",ProfilerFiller.class),
                        type.getMethod("renderSchematicRebuildTargetingOverlay",ProfilerFiller.class)};
            }
            for(Method m:boxMethods)m.invoke(overlay,Profiler.get());
        } catch(Throwable t) {
            boxesFailed=true;
            CausticaMod.LOGGER.error("Litematica box overlay unavailable under RT; schematic itself stays visible",t);
        }
    }
    public static RenderTarget target(RenderTarget original) {return drawing && target!=null?target:RtRasterFallback.target(original);}
    public static void setOverlayPipeline(RenderPass pass,RenderPipeline pipeline) {
        if(!drawing) {pass.setPipeline(RtRasterFallback.active()?LitematicaMaterials.overlayPipeline(pipeline):pipeline);return;}
        if(!overlayDepthFailed) try {LitematicaMaterials.setOverlay(pass,pipeline);return;}
        catch(Throwable t) {overlayDepthFailed=true;CausticaMod.LOGGER.error("Litematica overlay depth test unavailable; drawing boxes without it",t);}
        pass.setPipeline(LitematicaMaterials.overlayPipeline(pipeline));
    }
    public static void setPipeline(RenderPass pass,RenderPipeline pipeline) {
        if(!drawing) {pass.setPipeline(pipeline);return;}
        if(FAILED_PIPELINES.contains(pipeline)) {setOverlayPipeline(pass,pipeline);return;}
        try {LitematicaMaterials.setPipeline(pass,pipeline);}
        catch(Throwable t) {
            FAILED_PIPELINES.add(pipeline);
            CausticaMod.LOGGER.error("Litematica PBR pipeline failed; keeping base schematic visible: {}",pipeline.getLocation(),t);
            setOverlayPipeline(pass,pipeline);
        }
    }
    private static void fail(String phase,Throwable t) {
        failed=true;prepared=false;
        CausticaMod.LOGGER.error("Litematica Vulkan compatibility failure in {}; main RT remains enabled",phase,t);
    }
    public static void releaseMaterialBindings() {
        if(!INSTALLED)return;
        LitematicaMaterials.releaseMaterials();FAILED_PIPELINES.clear();failed=false;
    }
    public static void releaseDepthBinding() {if(INSTALLED)LitematicaMaterials.releaseDepth();}
    public static void destroy() {
        if(!INSTALLED)return;
        LitematicaMaterials.destroy();prepared=false;drawing=false;target=null;failed=false;logged=false;FAILED_PIPELINES.clear();
    }
    private LitematicaCompat() {}
}
