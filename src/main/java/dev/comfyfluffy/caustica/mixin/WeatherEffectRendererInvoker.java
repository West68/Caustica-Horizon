package dev.comfyfluffy.caustica.mixin;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WeatherEffectRenderer.class)
public interface WeatherEffectRendererInvoker {
    @Invoker("renderInstances")
    void caustica$renderInstances(VertexConsumer vertices,List<WeatherEffectRenderer.ColumnInstance> columns,
                                  Vec3 camera,float opacity,int radius,float intensity);
}
