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
    @WrapOperation(method="drawInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),require=0,remap=false)
    private void ce$alpha(RenderPass pass,RenderPipeline pipeline,Operation<Void> original) {
        LitematicaCompat.setOverlayPipeline(pass,pipeline);
    }
}
