package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.DhLodCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures a finished LOD mesh once per rebuild, on DH's build thread.
 *
 * <p>{@code getAndBuildRenderData()} returns the merged {@code LodQuadBuilder} that is then packed into
 * vertex buffers, so RETURN is the last point where CPU quads exist before upload. The return value is typed
 * {@code Object} here because DH is not a compile dependency; the erased callback signature matches.
 *
 * <p>{@code close()} drops the snapshot so unloaded sections do not accumulate.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection", remap = false)
public abstract class DhLodRenderSectionMixin {

    @Shadow
    @Final
    public long pos;

    @Inject(method = "getAndBuildRenderData", at = @At("RETURN"), remap = false, require = 0)
    private void caustica$captureBuiltQuads(CallbackInfoReturnable<Object> cir) {
        DhLodCapture.capture(pos, cir.getReturnValue(), this);
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false, require = 0)
    private void caustica$dropSnapshot(CallbackInfo ci) {
        DhLodCapture.unload(pos,this);
    }
}
