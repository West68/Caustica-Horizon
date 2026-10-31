package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.VanillaRenderController;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While RT draws the world the vanilla entity render states are never submitted, but extracting them still
 * ran every entity renderer once more per frame. Caustica extracts each entity itself, so mobs were extracted
 * twice per frame. Animation mods (EMF/Fresh Animations) advance per-entity tick-interpolated state during
 * extraction; the second pass saw that state already advanced and posed the mob slightly behind on the first
 * frame of every tick — a sawtooth that read as head shaking and walking backwards. Extract once, like vanilla.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorEntitiesMixin {
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"), cancellable = true, require = 0)
    private void caustica$rtExtractsEntities(CallbackInfo ci) {
        // While RT is on but has fallen back to the vanilla path, vanilla is the only one drawing entities.
        if (VanillaRenderController.rtRuntimeWorkRequested() && !VanillaRenderController.INSTANCE.onVanillaFallback()
                && !Boolean.getBoolean("caustica.test.vanillaEntityExtract")) ci.cancel();
    }
}
