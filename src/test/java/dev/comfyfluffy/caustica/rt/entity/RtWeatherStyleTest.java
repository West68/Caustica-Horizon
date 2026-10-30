package dev.comfyfluffy.caustica.rt.entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtWeatherStyleTest {
 @Test void rainIsNeutralThinAndWindDisplacedWithoutLeakingIntoOrdinaryParticles() {
  var out=new RtEntityCapture();var p=new RtParticleCapture(out);
  p.rainStyle(true);p.weatherStyle(true,100,64,100,10);
  for(int i=0;i<4;i++)p.addVertex(i%2,2,i/2).setColor(-1).setUv(0,0);
  p.flush();
  int flags=Float.floatToRawIntBits(out.prim.getFloat(9));
  int alpha=Float.floatToRawIntBits(out.prim.getFloat(10))&255;
  assertTrue((flags&32768)!=0);assertTrue(alpha>0 && alpha<64);
  assertNotEquals(0,out.verts.getFloat(0));
  p.rainStyle(false);p.weatherStyle(false,0,0,0,0);
  for(int i=0;i<4;i++)p.addVertex(i%2,2,i/2).setColor(-1).setUv(0,0);
  p.flush();assertEquals(0,out.verts.getFloat(12));
  assertEquals(0,Float.floatToRawIntBits(out.prim.getFloat(24+9))&32768);
 }
}
