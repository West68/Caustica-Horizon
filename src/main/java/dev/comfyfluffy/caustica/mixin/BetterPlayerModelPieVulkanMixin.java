package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw BPM's animation wheel through Minecraft's GUI extractor on Vulkan.
 * BPM 1.1.5's Pie.draw compiles an OpenGL shader and calls GL20/GL11 directly. */
@Pseudo
@Mixin(targets = "rip.ysm.gpu.Pie", remap = false)
public abstract class BetterPlayerModelPieVulkanMixin {
    private static final double TAU = Math.PI * 2.0;

    @Inject(method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;FFFFFFIF)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void caustica$drawOnVulkan(GuiGraphicsExtractor gui,
            float centerX, float centerY, float innerRadius, float outerRadius,
            float startAngle, float endAngle, int argb, float feather, CallbackInfo ci) {
        if (!(RenderSystem.getDevice() instanceof GpuDeviceAccessor accessor)
                || !(accessor.caustica$getBackend() instanceof VulkanDevice)) return;

        // The GUI extractor records Vulkan draws. Never enter BPM's OpenGL shader path.
        ci.cancel();
        if (gui == null || !Float.isFinite(centerX) || !Float.isFinite(centerY)
                || !Float.isFinite(innerRadius) || !Float.isFinite(outerRadius)
                || !Float.isFinite(startAngle) || !Float.isFinite(endAngle)
                || outerRadius <= 0 || outerRadius > 4096 || (argb >>> 24) == 0) return;

        double span = endAngle - startAngle;
        if (span <= 0) return;
        boolean fullCircle = span >= TAU - 1e-4;
        boolean smallArc = span <= Math.PI;
        double startX = Math.cos(startAngle), startY = Math.sin(startAngle);
        double endX = Math.cos(endAngle), endY = Math.sin(endAngle);
        double outer2 = (double) outerRadius * outerRadius;
        double inner = Math.max(0, innerRadius);
        double inner2 = inner * inner;
        int minX = (int) Math.floor(centerX - outerRadius);
        int maxX = (int) Math.ceil(centerX + outerRadius);
        int minY = (int) Math.floor(centerY - outerRadius);
        int maxY = (int) Math.ceil(centerY + outerRadius);

        // One scanline per GUI pixel preserves the wheel sectors, labels and mouse hit regions.
        // Runs become ordinary batched GUI rectangles instead of immediate-mode GL triangles.
        for (int y = minY; y < maxY; y++) {
            double dy = y + 0.5 - centerY;
            int run = -1;
            for (int x = minX; x <= maxX; x++) {
                boolean inside = false;
                if (x < maxX) {
                    double dx = x + 0.5 - centerX;
                    double r2 = dx * dx + dy * dy;
                    if (r2 <= outer2 && r2 >= inner2) {
                        double fromStart = startX * dy - startY * dx;
                        double toEnd = dx * endY - dy * endX;
                        inside = fullCircle || (smallArc
                                ? fromStart >= 0 && toEnd >= 0
                                : fromStart >= 0 || toEnd >= 0);
                    }
                }
                if (inside && run < 0) run = x;
                if (!inside && run >= 0) {
                    gui.fill(run, y, x, y + 1, argb);
                    run = -1;
                }
            }
        }
    }
}
