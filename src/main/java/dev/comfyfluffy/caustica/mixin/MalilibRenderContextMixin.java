package dev.comfyfluffy.caustica.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import dev.comfyfluffy.caustica.client.LitematicaCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Litematica's selection/placement boxes are drawn through malilib's batched context; under RT they go
 * into the same transparent composite layer as the schematic, only while that bridge is drawing. */
@Pseudo
@Mixin(targets="fi.dy.masa.malilib.render.RenderContext",remap=false)
public abstract class MalilibRenderContextMixin {
    @ModifyVariable(method="drawInternal",at=@At("HEAD"),argsOnly=true,ordinal=0,require=0,remap=false)
    private RenderTarget ce$target(RenderTarget original) {
        return original==null?null:LitematicaCompat.target(original);
    }
    @WrapOperation(method="drawInternal",at=@At(value="INVOKE",target="Lfi/dy/masa/malilib/render/RenderUtils;mainTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),require=0,remap=false)
    private RenderTarget ce$mainTarget(Operation<RenderTarget> original) {
        return LitematicaCompat.target(original.call());
    }
    //? if <26.3 {
    @WrapOperation(method="drawInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),require=0,remap=false)
    private void ce$alpha(RenderPass pass,RenderPipeline pipeline,Operation<Void> original) {
        LitematicaCompat.setOverlayPipeline(pass,pipeline);
    }
    //?} else {
    /*// 26.3 compiles first (RenderSystem.getCompiledPipeline) and then sets the compiled pipeline:
    // remember the source pipeline at the first call and substitute ours at the second.
    @org.spongepowered.asm.mixin.Unique private static RenderPipeline ce$pending;
    @WrapOperation(method="drawInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;"),require=0,remap=false)
    private com.mojang.blaze3d.pipeline.CompiledRenderPipeline ce$remember(RenderPipeline pipeline,Operation<com.mojang.blaze3d.pipeline.CompiledRenderPipeline> original) {
        ce$pending=pipeline;
        return original.call(pipeline);
    }
    @WrapOperation(method="drawInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;)V"),require=0,remap=false)
    private void ce$materials(RenderPass pass,com.mojang.blaze3d.pipeline.CompiledRenderPipeline compiled,Operation<Void> original) {
        RenderPipeline pipeline=ce$pending;ce$pending=null;
        if(pipeline==null) {original.call(pass,compiled);return;}
        LitematicaCompat.setOverlayPipeline(pass,pipeline);
    }
    *///?}
}
