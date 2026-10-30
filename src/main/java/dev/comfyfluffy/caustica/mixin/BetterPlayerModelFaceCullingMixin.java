package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.rt.entity.RtFirstPerson;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Better Player Model's CPU renderer drops back-facing cubes by projecting them with the main camera's
 * view-projection. RT captures the model around the entity anchor rather than camera-relative, so that test
 * culled whole limbs depending on view angle, and back faces are needed anyway for reflections and shadows.
 *
 * <p>The first-person arm pass is the exception: it is camera-relative there and only primary rays see it,
 * exactly like raster. Keeping its back faces showed the inside of the arm cubes whenever the model's arm
 * pose wraps around the eye (a hollow box in front of the camera after joining a world), so it is culled as
 * Better Player Model does without RT.
 */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.geckolib3.geo.FaceCulling", remap = false)
public abstract class BetterPlayerModelFaceCullingMixin {
    @Inject(method = "isFrontFacing", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void caustica$keepAllFacesUnderRt(CallbackInfoReturnable<Boolean> cir) {
        if (VanillaRenderController.rtRuntimeWorkRequested() && !RtFirstPerson.collecting()) cir.setReturnValue(true);
    }
}
