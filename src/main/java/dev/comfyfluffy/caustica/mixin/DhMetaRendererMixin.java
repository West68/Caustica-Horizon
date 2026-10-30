package dev.comfyfluffy.caustica.mixin;
import dev.comfyfluffy.caustica.client.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer",remap=false)
public abstract class DhMetaRendererMixin {
 @Inject(method={"copyToMcTexture","applyToMcTexture"},at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$offscreen(CallbackInfo ci){if(DistantHorizonsCompat.rasterUnused())ci.cancel();}
 @Inject(method="clearDhDepthAndColorTextures",at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$fresh(CallbackInfo ci){DistantHorizonsCompat.texturesCleared();if(DistantHorizonsCompat.rasterUnused())ci.cancel();}
 // Screen-sized DH color/depth are only allocated for DH's raster output; never create or resize them under RT.
 @Inject(method={"runRenderPassSetup","runRenderPassCleanup"},at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$noRasterTargets(CallbackInfo ci){if(DistantHorizonsCompat.rasterUnused())ci.cancel();}
}
