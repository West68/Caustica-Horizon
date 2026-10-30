package dev.comfyfluffy.caustica.rt.entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtGlintTransformTest {
 @Test void liveSpeedAnimatesUvEvenWhenRenderStateWasNotExtracted() {
  var a=RtGlintTransform.matrix(10000,.5,8);var b=RtGlintTransform.matrix(11000,.5,8);
  assertNotEquals(a.m30(),b.m30());assertNotEquals(a.m31(),b.m31());
  assertEquals(RtGlintTransform.matrix(10000,0,8),RtGlintTransform.matrix(11000,0,8));
  assertEquals(8,a.m00()/Math.cos(.17453292),1e-5);
 }
}
