package dev.comfyfluffy.caustica.mixin;
import dev.comfyfluffy.caustica.client.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Keep DH's quadtree updates, task draining and mesh uploads, skip the unused legacy terrain draw only.
 * Hardware geometry is shaded by Caustica; no DH color/depth texture is consumed on either supported DH version. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer",remap=false)
public abstract class DhHardwareTerrainMixin {
 @Inject(method="render",at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$skipDuplicateTerrainDraw(CallbackInfo ci){if(DistantHorizonsCompat.hardwareOnly() || DistantHorizonsCompat.rasterUnused() || dev.comfyfluffy.caustica.client.DhUploadSkip.blockRasterDraw())ci.cancel();}
}
