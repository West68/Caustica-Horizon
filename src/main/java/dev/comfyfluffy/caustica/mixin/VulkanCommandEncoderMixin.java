package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Complete the last UI/cleanup recording before Minecraft submits it during device destruction. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private void caustica$benchmarkSubmitBegin(CallbackInfo ci) {
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.submitBegin();
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void caustica$benchmarkSubmitEnd(CallbackInfo ci) {
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.submitEnd();
    }

    @Inject(method = "awaitSubmitCompletion", at = @At("HEAD"))
    private void caustica$benchmarkAwaitBegin(long index, long timeoutNanos, CallbackInfoReturnable<Boolean> cir) {
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.awaitBegin();
    }

    @Inject(method = "awaitSubmitCompletion", at = @At("RETURN"))
    private void caustica$benchmarkAwaitEnd(long index, long timeoutNanos, CallbackInfoReturnable<Boolean> cir) {
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.awaitEnd();
    }

    @Inject(method = "writeToBuffer", at = @At("HEAD"), cancellable = true)
    private void caustica$skipEmptyUpload(com.mojang.blaze3d.buffers.GpuBufferSlice slice,
            java.nio.ByteBuffer data, CallbackInfo ci) {
        // Empty dynamic geometry is legal at the API boundary, but Vulkan forbids size-zero copies.
        if (!data.hasRemaining()) ci.cancel();
    }

    @Shadow
    private void endCommandBuffer() { throw new AssertionError(); }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void caustica$finishShutdownRecording(CallbackInfo ci) {
        // The 26.2 destroy path calls Submission.close without the endCommandBuffer step used by submit.
        // endCommandBuffer is a no-op if there is no recording. Preserve the original submit/idle/free order.
        endCommandBuffer();
    }
}
