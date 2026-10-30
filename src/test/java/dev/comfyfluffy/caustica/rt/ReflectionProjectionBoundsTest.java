package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class ReflectionProjectionBoundsTest {
 private static double motion(double x,double w,double current,double width,double fallback) {
  if(!Double.isFinite(x)||!Double.isFinite(w)||w<=1e-5)return fallback;
  double result=(x/w-current)*.5*width;
  return Double.isFinite(result)&&Math.abs(result)<=65504 ? result : fallback;
 }
 @Test void cameraPlaneCrossingsCannotBecomeHalfFloatInfinityOrReuseZeroHistory() {
  for(double w:new double[]{0,-1,1e-8,Double.NaN,Double.POSITIVE_INFINITY})
   assertEquals(3840,motion(1,w,0,1920,3840));
  assertEquals(3840,motion(100,1e-4,0,1920,3840));
 }
 @Test void validOffscreenMotionAndStationaryReflectionArePreserved() {
  assertEquals(2880,motion(3,1,0,1920,3840));
  for(double w:new double[]{.001,1,1e6}) assertEquals(0,motion(.25*w,w,.25,1920,3840));
 }
 @Test void bothPrimaryAndReflectedProjectionUseCheckedGuidePath() throws Exception {
  String s=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertTrue(s.contains("checkedMotion(previousClip, currentNdc, size, surfaceMotion)"));
  assertTrue(s.contains("checkedMotion(prevClip, curNdc, size, size * 2.0)"));
  assertFalse(s.contains("prevWaterClip.xy / prevWaterClip.w"));
 }
}
