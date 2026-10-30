package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CinematicDirector;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the showcase is filming, the mouse is released to the desktop and the game ignores it. */
@Mixin(MouseHandler.class)
public abstract class CinematicMouseMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicScroll(long window, double x, double y, CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicMove(long window, double x, double y, CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicTurn(CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicNoGrab(CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }
}
