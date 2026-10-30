package dev.comfyfluffy.caustica.enhanced.test.mixin;
import dev.comfyfluffy.caustica.enhanced.test.Cadence;
@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.Minecraft.class)
public class CadenceMixin {
 @org.spongepowered.asm.mixin.injection.Inject(method="renderFrame",at=@org.spongepowered.asm.mixin.injection.At("HEAD"))
 private void ce$frame(boolean tick,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){Cadence.frame();dev.comfyfluffy.caustica.enhanced.test.EntityPortalRegression.frame();}
}
