package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class RrGuideContractTest {
 @Test void roughSurfaceFallbackIncludesCameraMotion() throws Exception {
  // A camera translation of 0.1 at z=2 and focal length 1000px moves a static point 50px.
  double previousX=.1,currentX=0,z=2,focal=1000;
  assertEquals(50,(previousX-currentX)/z*focal);
  String shader=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  int start=shader.indexOf("float2 specularReflectionMotion(");
  assertTrue(shader.substring(start,shader.indexOf("float3 n =",start)).contains("return surfaceMotion;"));
  assertTrue(shader.contains("if ((worldPush.flags & 2048u) != 0u && !gv_psr)")); // PSR mirrors reuse the primary motion
  assertTrue(Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java")).contains("if (rrPath && !RtDlssRr.srOnly()) flags |= 2048"));
 }
 @Test void waterDepthAndMotionUseSameSurfaceUnderCameraTranslation() throws Exception {
  // Water and refracted floor share a current pixel, but not translational parallax.
  double currentWaterX=1, waterDepth=2, floorX=3, floorDepth=6, cameraShift=.1;
  assertEquals(currentWaterX/waterDepth,floorX/floorDepth);
  double waterMotion=(currentWaterX+cameraShift)/waterDepth-currentWaterX/waterDepth;
  double floorMotion=(floorX+cameraShift)/floorDepth-floorX/floorDepth;
  assertNotEquals(waterMotion,floorMotion,1e-6);
  // Reconstructing a water-depth point with floor motion leaves a world-space error.
  assertEquals(0,(currentWaterX/waterDepth+waterMotion)*waterDepth-(currentWaterX+cameraShift),1e-12);
  assertTrue(Math.abs((currentWaterX/waterDepth+floorMotion)*waterDepth-(currentWaterX+cameraShift))>.06);
  String shader=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertTrue(shader.contains("float4(gv_hitCamRel + worldPush.camDelta, 1.0)"));
 }
}
