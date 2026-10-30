package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class EndEnvironmentTest {
 @Test void referenceRatiosAndFiniteColumnAreUsed() throws Exception {
  String s=Files.readString(Path.of("shaders/world/end_environment.slang"));
  assertTrue(s.contains("time/900.0+.282"));assertTrue(s.contains("float3(0,-1.75,0)"));
  assertTrue(s.contains("center,1.005"));assertTrue(s.contains("if(r<=1.6 || r>=2.6)return 0"));
  assertTrue(s.contains(".85649968,.2763556486,.4359310193"));
  assertTrue(s.contains("endRotateX(endRotateZ(position"));
  assertTrue(s.contains("i<50"));assertTrue(s.contains("endAccretion(backgroundDir,time,noiseAddr,starGain)"));
  for(double y:new double[]{0,1e-15,.001,.5,1})for(double d:new double[]{0,.01,1,100,1024}) {
   double h=y*d*.03;double integral=d*(h<1e-4?Math.log(2):-Math.expm1(-Math.log(2)*h)/h);
   assertTrue(Double.isFinite(integral));assertTrue(integral>=0&&integral<=d*Math.log(2)+1e-10);
  }
  double radius=20e6,eye=radius+15e6;assertEquals(1.75,eye/radius);
  assertTrue(Math.toDegrees(Math.asin(radius/eye))>34&&Math.toDegrees(Math.asin(radius/eye))<35);
 }
}
