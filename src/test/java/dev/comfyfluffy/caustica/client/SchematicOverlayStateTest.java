package dev.comfyfluffy.caustica.client;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.*;
import java.util.Optional;
import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SchematicOverlayStateTest {
 @Test void rgbOnlySourceMustAccumulateAlphaInTransparentIntermediate() {
  var source=new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_COLOR);
  var fixed=SchematicOverlayState.colors(new ColorTargetState[]{source})[0];
  assertTrue(fixed.writeAlpha());assertEquals(Optional.of(BlendFunction.TRANSLUCENT),fixed.blendFunction());
  assertFalse(source.writeAlpha());assertEquals(source.format(),fixed.format());
 }
 @Test void intermediateOverMatchesDirectOverEvenInsideManyOverlappingFaces() {
  double[] scene={.2,.4,.6},overlay={0,0,0};double alpha=0;
  double[] direct=scene.clone();
  for(int i=0;i<12;i++) {
   double a=.2+(i%3)*.1;double[] rgb={.1,.5,.8};
   for(int c=0;c<3;c++){overlay[c]=rgb[c]*a+overlay[c]*(1-a);direct[c]=rgb[c]*a+direct[c]*(1-a);}
   alpha=a+alpha*(1-a);
   for(int c=0;c<3;c++)assertEquals(direct[c],overlay[c]+scene[c]*(1-alpha),1e-12);
  }
 }
 @Test void overlayRestoresWorldMatrixAndLeavesHandMatrixUntouched() {
  var view=new Matrix4f().rotateXYZ(.4f,-.8f,.2f);
  var hand=new Matrix4f().translate(.3f,-.2f,.8f).rotateZ(.3f);
  var stack=new Matrix4fStack(8);stack.set(hand);stack.pushMatrix();stack.set(view);
  stack.translate(14,-9,25);
  var expected=new Matrix4f(view).translate(14,-9,25);
  assertTrue(expected.equals(stack,1e-6f));stack.popMatrix();assertTrue(hand.equals(stack,1e-6f));
 }
}
