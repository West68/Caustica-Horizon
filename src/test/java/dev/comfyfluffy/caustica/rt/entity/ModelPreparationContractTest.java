package dev.comfyfluffy.caustica.rt.entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import java.lang.reflect.Modifier;
class ModelPreparationContractTest {
 @Test void captureSeamsExistInTheActualMinecraftClasses() throws Exception {
  var prepare=ModelFeatureRenderer.class.getDeclaredMethod("prepareModel",ModelFeatureRenderer.Submit.class);
  assertTrue(Modifier.isPrivate(prepare.getModifiers()));
  var builder=RenderTypeFeatureRenderer.class.getDeclaredMethod("getVertexBuilder",RenderType.class);
  assertEquals(VertexConsumer.class,builder.getReturnType());
 }
 @Test void poseSnapshotRetainsFinalAttachmentAndDoesNotAliasLaterMutations() {
  var poses=new PoseStack();poses.translate(2,3,4);poses.scale(1.2f,.8f,1.1f);
  var copy=poses.last().copy();var expected=new org.joml.Matrix4f(copy.pose());
  poses.translate(100,-200,300);
  assertEquals(expected,copy.pose());
  assertNotEquals(poses.last().pose(),copy.pose());
 }
}
