package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.client.LitematicaCompat;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OutputTarget.class)
public abstract class LitematicaBlockEntityTargetMixin {
    @Inject(method = "getRenderTarget", at = @At("RETURN"), cancellable = true)
    private void caustica$schematicBlockEntityTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget original = cir.getReturnValue();
        RenderTarget target = LitematicaCompat.blockEntityTarget(original);
        if (target != original) cir.setReturnValue(target);
    }
}
