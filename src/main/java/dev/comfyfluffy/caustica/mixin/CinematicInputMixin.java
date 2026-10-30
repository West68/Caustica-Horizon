package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CinematicDirector;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
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
        if (action == GLFW.GLFW_PRESS && event.key() == GLFW.GLFW_KEY_X
                && (mods & GLFW.GLFW_MOD_CONTROL) != 0 && (mods & GLFW.GLFW_MOD_SHIFT) != 0) {
            CinematicDirector.requestAbort();
        }
        ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void caustica$cinematicChars(long window, CharacterEvent event, CallbackInfo ci) {
        if (CinematicDirector.running()) ci.cancel();
    }
}
