package dev.comfyfluffy.caustica.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.comfyfluffy.caustica.client.LitematicaCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="fi.dy.masa.litematica.render.schematic.WorldRendererSchematic",remap=false)
public abstract class LitematicaOverlayMixin {
    //? if <26.3 {
    @WrapOperation(method="drawOverlayInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),require=0,remap=false)
    private void ce$alpha(RenderPass pass,RenderPipeline pipeline,Operation<Void> original) {
        LitematicaCompat.setOverlayPipeline(pass,pipeline);
    }
    //?} else {
    /*// 26.3 compiles first (RenderSystem.getCompiledPipeline) and then sets the compiled pipeline:
    // remember the source pipeline at the first call and substitute ours at the second.
    @org.spongepowered.asm.mixin.Unique private static RenderPipeline ce$pending;
    @WrapOperation(method="drawOverlayInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;"),require=0,remap=false)
    private com.mojang.blaze3d.pipeline.CompiledRenderPipeline ce$remember(RenderPipeline pipeline,Operation<com.mojang.blaze3d.pipeline.CompiledRenderPipeline> original) {
        ce$pending=pipeline;
        return original.call(pipeline);
    }
    @WrapOperation(method="drawOverlayInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;)V"),require=0,remap=false)
    private void ce$materials(RenderPass pass,com.mojang.blaze3d.pipeline.CompiledRenderPipeline compiled,Operation<Void> original) {
        RenderPipeline pipeline=ce$pending;ce$pending=null;
        if(pipeline==null) {original.call(pass,compiled);return;}
        LitematicaCompat.setOverlayPipeline(pass,pipeline);
    }
    *///?}
    @WrapOperation(method="drawOverlayInternal",at=@At(value="INVOKE",target="Lfi/dy/masa/malilib/render/RenderUtils;mainTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),require=0,remap=false)
    private RenderTarget ce$overlayTarget(Operation<RenderTarget> original) {
        return LitematicaCompat.target(original.call());
    }
}
