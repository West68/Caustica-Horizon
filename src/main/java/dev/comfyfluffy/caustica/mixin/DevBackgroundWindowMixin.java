//? if <26.3 {
package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Dev-only automated testing (-Dcaustica.test.background=true): the game window never takes keyboard focus
 * and opens off-screen, so a scripted PerfAutoBench run can render and screenshot while the user keeps using
 * the desktop. Inert without the property; stripped from release jars.
 */
@Mixin(Window.class)
public abstract class DevBackgroundWindowMixin {
    @WrapOperation(method = "createGlfwWindow", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
    private static long caustica$backgroundWindow(int width, int height, CharSequence title, long monitor, long share,
                                                  Operation<Long> original) {
        boolean background = Boolean.getBoolean("caustica.test.background");
        if (background) {
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        }
        long handle = original.call(width, height, title, monitor, share);
        if (background && handle != 0L) GLFW.glfwSetWindowPos(handle, -4000, 40);
        return handle;
    }
}
//?}
// 26.3 replaced GLFW with SDL; this mixin is excluded there (see mixin_exclude).
