package dev.comfyfluffy.caustica.mixin;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ModelFeatureRenderer.class)
public interface ModelFeatureRendererInvoker {
    @Invoker("prepareModel") void caustica$prepareModel(ModelFeatureRenderer.Submit<?> submit);
}
