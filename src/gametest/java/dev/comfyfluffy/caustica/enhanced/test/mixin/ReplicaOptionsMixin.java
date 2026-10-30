package dev.comfyfluffy.caustica.enhanced.test.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Test-only: retain the real replica options. Fabric's vanilla default snapshot omits mod key mappings.
 * The integration test sets FOV, focus, VSync, frame limit, DLSS, camera, weather and time explicitly.
 * This is never packaged in the renderer JAR and changes no production controls.
 */
@Mixin(value=net.fabricmc.fabric.impl.client.gametest.context.ClientGameTestContextImpl.class,remap=false)
public abstract class ReplicaOptionsMixin {
 @Inject(method="restoreDefaultGameOptions",at=@At("HEAD"),cancellable=true,remap=false)
 private void retainReplicaOptions(CallbackInfo ci){if(Boolean.getBoolean("ce.integration"))ci.cancel();}
}
