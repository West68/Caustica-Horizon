package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderPass;
import dev.comfyfluffy.caustica.client.LitematicaCompat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="fi.dy.masa.litematica.render.schematic.ChunkRenderBatchDraw",remap=false)
public abstract class LitematicaDrawMixin {
    @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),require=0,remap=false)
    private RenderTarget ce$target(ChunkSectionLayerGroup group,Operation<RenderTarget> original) {
        return LitematicaCompat.target(original.call(group));
    }
    @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),require=0,remap=false)
    private void ce$materials(RenderPass pass,RenderPipeline pipeline,Operation<Void> original) {
        LitematicaCompat.setPipeline(pass,pipeline);
    }
}
