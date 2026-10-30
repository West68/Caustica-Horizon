package dev.comfyfluffy.caustica.mixin;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** While Caustica replaces the world with its own RT terrain (vanilla LevelRenderer cancelled), Sodium's
 * per-frame terrain setup only schedules chunk meshing and GPU uploads that are never drawn: ~10 worker
 * threads of CPU and arena VRAM competing with the RT frame. Skip it in that state only; as soon as the
 * raster world is needed again Sodium resumes and rebuilds what it shows. Block entities and entities are
 * gathered by Caustica from the level itself, not from Sodium's section graph.
 * {@code -Dcaustica.sodium.skipTerrain=false} restores Sodium's original behaviour. */
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",remap=false)
public abstract class SodiumTerrainSkipMixin {
 private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("caustica.sodium.skipTerrain","true"));
 @Inject(method="setupTerrain",at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$skipUnusedTerrain(CallbackInfo ci){
  if(ENABLED && VanillaRenderController.rtRuntimeWorkRequested() && VanillaRenderController.INSTANCE.shouldCancelLevelRenderer())ci.cancel();
 }
}
