package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class CloudProjectionTest {
 @Test void cloudIntersectionUsesWorldReceiverAndForwardLight() throws Exception {
  String s=Files.readString(Path.of("shaders/world/itrp_clouds.slang"));
  assertTrue(s.contains("receiver-w.camOffset+w.cloudWorld.xyz"));
  assertTrue(s.contains("itrpCloudDensity(w.cloudNoiseAddr,pos"));
  assertTrue(s.contains("w.cloudLight.w,rain,CLOUD_OCTAVES"));
  for(double lightY:new double[]{.01,.1,.5,1}) {
   double receiverY=64,planeY=940,t=(planeY-receiverY)/lightY;
   assertEquals(planeY,receiverY+t*lightY,1e-9);
   for(double density:new double[]{0,.01,.5,1}) {
    double trans=.08+.92*Math.exp(-density*density*45);assertTrue(trans>=.08&&trans<=1);
   }
  }
 }
}
