package dev.comfyfluffy.caustica.mixin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.comfyfluffy.caustica.client.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
/** DH creates its own vanilla-style cloud boxes inside the LOD pass. Keep its visibility update,
 * rather than cancelling preRender (which would leave already-visible cached groups alive). */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler",remap=false)
public abstract class DhCloudMixin {
 @ModifyExpressionValue(method="preRender",at=@At(value="INVOKE",target="Ljava/lang/Boolean;booleanValue()Z",ordinal=0),require=0,remap=false)
 private boolean caustica$onlyRtClouds(boolean enabled){return enabled && !DistantHorizonsCompat.capturing();}
}
