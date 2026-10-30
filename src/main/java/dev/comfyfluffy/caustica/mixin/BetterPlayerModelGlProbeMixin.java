package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Better Player Model probes OpenGL on CLIENT_STARTED (glGetInteger / glCreateShader) to validate its GPU
 * renderer. On Minecraft's Vulkan backend there is no GL context, and the call kills the process with no
 * crash report. Its CPU renderer needs no GL (the GPU path already disables itself without ysm-core), so on
 * Vulkan report the probe as passed (null = no error) instead of touching GL.
 */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ClientSetupEvent", remap = false)
public abstract class BetterPlayerModelGlProbeMixin {
    @Inject(method = "nativeClientInit", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void caustica$noGlProbeOnVulkan(CallbackInfoReturnable<Object> cir) {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice) {
            cir.setReturnValue(null);
        }
    }
}
