package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BPM's optional blur stack also creates an OpenGL shader. It has no Vulkan target. */
@Pseudo
@Mixin(targets = "rip.ysm.gpu.BlurStack", remap = false)
public abstract class BetterPlayerModelBlurVulkanMixin {
    @Shadow @Final private static List<?> regions;

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void caustica$skipGlBlur(CallbackInfo ci) {
        if (RenderSystem.getDevice() instanceof GpuDeviceAccessor accessor
                && accessor.caustica$getBackend() instanceof VulkanDevice) {
            // Match BPM's own failed-shader path: discard queued blur regions each frame.
            regions.clear();
            ci.cancel();
        }
    }
}
