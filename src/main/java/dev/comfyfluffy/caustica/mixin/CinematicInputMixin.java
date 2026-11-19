package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.comfyfluffy.caustica.client.CinematicDirector;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the showcase is filming, the game takes no keyboard input; Ctrl+Shift+X aborts the shoot. */
@Mixin(KeyboardHandler.class)
public abstract class CinematicInputMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (!CinematicDirector.running()) return;
        int mods = event.modifiers();
        // InputConstants mirrors GLFW (<=26.2) or SDL (26.3+) codes, so the same check works on both.
        if (action == InputConstants.PRESS && event.key() == InputConstants.KEY_X
                && (mods & InputConstants.MOD_CONTROL) != 0 && (mods & InputConstants.MOD_SHIFT) != 0) {
            CinematicDirector.requestAbort();
        }
        ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicChars(long window, CharacterEvent event, CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }

    //? if >=26.3 {
    /*// SDL delivers typed text as whole strings instead of per-character events.
    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicText(long window, String text, CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }
    *///?}
}
