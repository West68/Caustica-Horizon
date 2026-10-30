package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.rt.entity.RtEntityCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BPM 1.1.5's JNI mesh builder performs camera-projected face culling inside native code. It bypasses
 * BetterPlayerModelFaceCullingMixin, and uses a camera-relative projection on our entity-local meshes.
 * Select BPM's existing Java mesh builder only for RT sinks: it retains the same model/bone data and
 * reaches the all-faces hook needed for primary rays, mirrors and shadows. No config or DLL is changed.
 */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.geckolib3.geo.NativeModelRenderer", remap = false)
public abstract class BetterPlayerModelNativeCaptureMixin {
    @ModifyExpressionValue(
        method = "renderMesh(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/elfmcys/yesstevemodel/geckolib3/geo/render/built/GeoModel;[F[FIIIIFFFFLnet/minecraft/resources/Identifier;ZZ)V",
        at = @At(value = "INVOKE", target = "Lcom/elfmcys/yesstevemodel/NativeLibLoader;isLoaded()Z"),
        require = 2, expect = 2, allow = 2, remap = false)
    private static boolean caustica$useCompleteRtMesh(boolean loaded,
            @Local(argsOnly = true) VertexConsumer consumer) {
        return loaded && !RtEntityCollector.isGeometryCapture(consumer);
    }
}
