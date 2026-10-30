package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.YsmModelMemory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers Better Player Model's model switches so YsmModelMemory can restore them on rejoin. */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.network.NetworkHandler", remap = false)
public abstract class YsmNetworkHandlerMixin {
    @Inject(method = "sendToServer", at = @At("HEAD"), require = 0, remap = false)
    private static void caustica$rememberModel(Object packet, CallbackInfo ci) {
        YsmModelMemory.onSend(packet);
    }
}
