package dev.comfyfluffy.caustica.mixin;
import dev.comfyfluffy.caustica.client.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets={"com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhFogRenderer",
 "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhTaaRenderer",
 "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhSsaoRenderer",
 "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhFarFadeRenderer",
 "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeVanillaFadeRenderer",
 "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhSharpenRenderer",
 "com.seibel.distanthorizons.common.render.blaze.BlazeDhGenericObjectRenderer"},remap=false)
public abstract class DhPostProcessingMixin {
 @Inject(method="render",at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$sharedFogAndReconstruction(CallbackInfo ci){if(DistantHorizonsCompat.rasterUnused())ci.cancel();}
}
