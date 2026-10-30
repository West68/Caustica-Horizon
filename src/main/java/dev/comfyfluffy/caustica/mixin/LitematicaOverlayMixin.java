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
    @WrapOperation(method="drawOverlayInternal",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),require=0,remap=false)
    private void ce$alpha(RenderPass pass,RenderPipeline pipeline,Operation<Void> original) {
        LitematicaCompat.setOverlayPipeline(pass,pipeline);
    }
    @WrapOperation(method="drawOverlayInternal",at=@At(value="INVOKE",target="Lfi/dy/masa/malilib/render/RenderUtils;mainTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),require=0,remap=false)
    private RenderTarget ce$overlayTarget(Operation<RenderTarget> original) {
        return LitematicaCompat.target(original.call());
    }
}
