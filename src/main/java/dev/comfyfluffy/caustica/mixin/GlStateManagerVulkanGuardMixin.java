package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft never touches GlStateManager on its Vulkan backend, but some GUI mods still do (Better Player
 * Model's model browser buttons call _enableBlend/_blendFuncSeparate/_disableBlend). With no GL context the
 * call faults inside the driver and the game exits without a crash report (Alt+Y). On Vulkan these blend
 * toggles have no meaning, so drop them; the GUI blit itself goes through the normal Vulkan GUI pipeline.
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GlStateManagerVulkanGuardMixin {
    private static Boolean caustica$vulkan;

    private static boolean caustica$onVulkan() {
        Boolean v = caustica$vulkan;
        if (v == null) {
            v = ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice;
            caustica$vulkan = v;
        }
        return v;
    }

    @Inject(method = {"_enableBlend", "_disableBlend", "_blendFuncSeparate"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void caustica$noGlStateOnVulkan(CallbackInfo ci) {
        if (caustica$onVulkan()) ci.cancel();
    }
}
