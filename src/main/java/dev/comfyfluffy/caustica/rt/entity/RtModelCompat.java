package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.mixin.ModelFeatureRendererInvoker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Uses the real model preparation seam, including EMF/ETF submit-data and pose hooks. */
public final class RtModelCompat {
    private static final boolean ENABLED = FabricLoader.getInstance().isModLoaded("entity_model_features")
        || FabricLoader.getInstance().isModLoaded("entity_texture_features");
    private static final ThreadLocal<VertexConsumer> TARGET = new ThreadLocal<>();
    private static final class Renderer { static final ModelFeatureRenderer INSTANCE = new ModelFeatureRenderer(); }
    private static final class EtfScope {
        static final java.lang.invoke.MethodHandle PUSH, POP;
        static {
            java.lang.invoke.MethodHandle push=null, pop=null;
            if(FabricLoader.getInstance().isModLoaded("entity_texture_features")) try {
                var type=Class.forName("traben.entity_texture_features.features.state.ETFState");
                var signature=java.lang.invoke.MethodType.methodType(void.class);
                push=java.lang.invoke.MethodHandles.publicLookup().findStatic(type,"mountNone",signature);
                pop=java.lang.invoke.MethodHandles.publicLookup().findStatic(type,"unMount",signature);
            } catch(ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
            PUSH=push; POP=pop;
        }
    }
    public static boolean enabled() { return ENABLED; }
    public static VertexConsumer target() { return TARGET.get(); }
    static <S> void render(Model<? super S> model, S state, PoseStack pose, RenderType type,
            int light, int overlay, int color, TextureAtlasSprite sprite, RtEntityCapture capture) {
        // Constructing the real Submit runs ETF DATA_IN: model variant and biped attachment pose
        // metadata are captured before its prepareModel hooks mount the matching render state.
        var submit = new ModelFeatureRenderer.Submit<>(type, pose.last().copy(), model, state,
            light, overlay, color, sprite, null);
        VertexConsumer previous = TARGET.get();
        var textureTransform=capture.textureTransform;
        capture.clearUvRemap(); // prepareModel wraps sprite UVs itself; never remap twice.
        capture.textureTransform=textureTransform; // preserve glint/charged-creeper/wither shield scrolling on EMF replay
        TARGET.set(capture);
        // ETF mount(state) is a no-op when that same entity is already mounted by the dispatcher,
        // but prepareModel's tail always pops it. A null scope gives the nested replay its own mount
        // and preserves the dispatcher's state for subsequent features and item attachments.
        boolean scoped=false;
        int emfPhase=RtEmfReplayScope.begin();
        try {
            if(EtfScope.PUSH!=null) { EtfScope.PUSH.invokeExact(); scoped=true; }
            ((ModelFeatureRendererInvoker) Renderer.INSTANCE).caustica$prepareModel(submit);
        } catch(Throwable error) { throw new IllegalStateException("Animated RT model replay failed",error); }
        finally {
            try {
                if(scoped) EtfScope.POP.invokeExact();
            } catch(Throwable error) { throw new IllegalStateException("ETF RT replay scope failed",error); }
            finally {
                RtEmfReplayScope.end(emfPhase);
                if (previous == null) TARGET.remove(); else TARGET.set(previous);
                if (sprite != null) capture.setUvRemap(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1());
            }
        }
    }
    private RtModelCompat() {}
}
