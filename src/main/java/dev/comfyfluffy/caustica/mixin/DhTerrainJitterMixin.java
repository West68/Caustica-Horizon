package dev.comfyfluffy.caustica.mixin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.comfyfluffy.caustica.client.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer",remap=false)
public abstract class DhTerrainJitterMixin {
 @ModifyExpressionValue(method="render",at=@At(value="INVOKE",target="Ljava/lang/Boolean;booleanValue()Z",ordinal=0),require=0,remap=false)
 private boolean caustica$singleJitter(boolean enabled){return enabled && !DistantHorizonsCompat.capturing();}
}
