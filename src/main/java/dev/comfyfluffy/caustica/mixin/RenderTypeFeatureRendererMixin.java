package dev.comfyfluffy.caustica.mixin;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.rt.entity.RtModelCompat;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin {
    @Inject(method="getVertexBuilder",at=@At("HEAD"),cancellable=true)
    private void caustica$captureModelVertices(RenderType type, CallbackInfoReturnable<VertexConsumer> cir) {
        VertexConsumer target=RtModelCompat.target();
        if(target!=null) cir.setReturnValue(target);
    }
}
