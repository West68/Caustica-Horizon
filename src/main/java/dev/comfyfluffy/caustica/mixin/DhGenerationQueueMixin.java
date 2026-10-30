package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.DhLodProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers each DH world generation queue so the cinematic director can wait for distant LODs. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public abstract class DhGenerationQueueMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
    private void caustica$register(CallbackInfo ci) {
        DhLodProgress.register(this);
    }
}
