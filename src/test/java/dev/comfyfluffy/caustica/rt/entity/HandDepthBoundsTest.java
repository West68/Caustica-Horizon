package dev.comfyfluffy.caustica.rt.entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HandDepthBoundsTest {
 @Test void positiveDepthPosesStayBeyondSafetyPlaneWithoutChangingProjection() {
  for(int i=1;i<=10000;i++) {
   float depth=i/10000f;
   float scale=HandProjection.safeDepthScale(.5f,depth);
   assertTrue(Float.isFinite(scale));
   assertTrue(depth*scale>=.07999999f);
   assertEquals(.3/depth,(.3*scale)/(depth*scale),.001);
   assertEquals(-.2/depth,(-.2*scale)/(depth*scale),.001);
  }
 }
 @Test void emptyAndInvalidDepthDoNotGenerateInfiniteCoordinates() {
  assertEquals(1f,HandProjection.safeDepthScale(.5f,Float.POSITIVE_INFINITY));
  assertEquals(1f,HandProjection.safeDepthScale(.5f,0f));
  assertEquals(1f,HandProjection.safeDepthScale(.5f,-1f));
 }
}
