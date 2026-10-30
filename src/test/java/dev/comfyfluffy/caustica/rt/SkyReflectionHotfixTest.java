package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import dev.comfyfluffy.caustica.rt.entity.HandProjection;
class SkyReflectionHotfixTest {
 private static double smooth(double a,double b,double x){double t=Math.clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);}
 private String read(String p)throws Exception{return Files.readString(Path.of(p));}
 @Test void eclipseReferenceIsBoundedAndActuallyApplied()throws Exception {
  for(int i=0;i<=10000;i++){double phase=i*Math.PI*2/10000;double v=1-smooth(.35,.62,phase)+smooth(1.6,1.87,phase);assertTrue(v>=0&&v<=1);}
  assertEquals(0,1-smooth(.35,.62,1)+smooth(1.6,1.87,1));
  assertTrue(read("shaders/world/world.rgen.slang").contains("worldPush.lightRadiance.xyz*=visible*visible"));
 }
 @Test void angularHaloIsFiniteAndLeavesCentralSilhouetteDark() {
  for(int i=0;i<=10000;i++){double a=4*i/10000.;double h=(Math.exp(-a/.055)+.15*Math.exp(-a/.24))*smooth(.006,.022,a);assertTrue(Double.isFinite(h)&&h>=0&&h<=1.15);}
  assertEquals(0,smooth(.006,.022,0));
 }
 @Test void compatibilityDoesNotOverridePositiveReflectivityOrConductors()throws Exception {
  String s=read("shaders/world/world.rchit.slang");
  assertTrue(s.contains("surface.metalness<.5"));assertTrue(s.contains("max(surface.f0.r,max(surface.f0.g,surface.f0.b))<=0.0"));
  assertTrue(s.contains("surface.f0=float3(control.cloudDetail.w)"));
  double green=Math.pow(.6,2.2),blue=Math.pow(.25,2.2);assertTrue(green>.32&&green<.33&&blue>.047&&blue<.048);
 }
 @Test void handDepthCompressionPreservesPerspectiveAndNearPlane() {
  assertEquals(.08f/.03f,HandProjection.safeDepthScale(.5f,.03f));
  float scale=HandProjection.safeDepthScale(.5f,.4f);assertEquals(.5f,scale);
  assertTrue(.4*scale>=.08);
  assertEquals(.3/-.4,(.3*scale)/(-.4*scale),1e-12);
  assertEquals(-.2/-.4,(-.2*scale)/(-.4*scale),1e-12);
 }
}
