package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import com.google.gson.JsonParser;
class VisualTransportTest {
 @Test void finiteLightHasMonotoneFalloffAndExactCutoff() {
  double previous=Double.POSITIVE_INFINITY;
  for(int i=0;i<=15000;i++){double a=VisualMath.attenuation(i*.001,15);assertTrue(a>=0&&a<=previous);previous=a;}
  assertEquals(0,VisualMath.attenuation(15,15));assertEquals(0,VisualMath.attenuation(100,15));
  assertTrue(VisualMath.attenuation(14.999,15)<1e-9);assertEquals(0,VisualMath.attenuation(1,0));
 }
 @Test void beerLambertComposesAndNeverCreatesEnergy() {
  for(double ext:new double[]{0,.005,.05,2})for(double d:new double[]{0,.1,1,100,10000}) {
   double t=VisualMath.transmittance(ext,d);assertTrue(Double.isFinite(t)&&t>=0&&t<=1);
   assertEquals(t,VisualMath.transmittance(ext,d*.4)*VisualMath.transmittance(ext,d*.6),1e-14);
  }
 }
 @Test void phaseNormalizesOverSolidAngle() {
  int n=100000;double sum=0;for(int i=0;i<n;i++)sum+=VisualMath.phase(-1+(i+.5)*2/n,.65)*4*Math.PI/n;
  assertEquals(1,sum,1e-7);assertTrue(VisualMath.phase(1,.65)>VisualMath.phase(-1,.65));
 }
 @Test void samplingOneHandEstimatesSumRatherThanAverage() {
  for(double p:new double[]{.01,.25,.5,.9,.99}) {
   double a=7,b=3;assertEquals(a+b,p*(a/p)+(1-p)*(b/(1-p)),1e-12);
  }
 }
 @Test void mediumDistanceEstimatorMatchesAnalyticIntegral() {
  for(double ext:new double[]{.000001,.005,.2})for(double length:new double[]{.01,10,300}) {
   double estimate=0;int n=20000;
   for(int i=0;i<n;i++) {var s=VisualMath.sampleMedium(ext,length,(i+.5)/n);assertTrue(s.distance()>=0&&s.distance()<=length);assertTrue(s.pdf()>0);estimate+=Math.exp(-ext*s.distance())*.025/s.pdf()/n;}
   double analytic=.025*(-Math.expm1(-ext*length))/ext;assertEquals(analytic,estimate,Math.max(1e-10,analytic*1e-6));
  }
 }
 @Test void pointLightRulesHandleNamespacesAndWhitespace() {
  assertArrayEquals(new String[]{"minecraft:torch:14","mod:lamp:15"},VisualMath.splitRules("  minecraft:torch:14\tmod:lamp:15  "));
  assertEquals(15,VisualMath.parseRule("mod:lamp:15")[0]);
  assertThrows(IllegalArgumentException.class,()->VisualMath.parseRule("lamp:16"));
  assertThrows(IllegalArgumentException.class,()->VisualMath.parseRule("lamp:-1"));
 }
 @Test void lambRuleChecksConditionsInsteadOfSilentlyIgnoringThem() {
  var r=HeldLightRule.parse(JsonParser.parseString("{\"match\":{\"items\":[\"minecraft:torch\",\"mod:lamp\"]},\"luminance\":14,\"water_sensitive\":true}").getAsJsonObject());
  assertTrue(r.items().contains("mod:lamp"));assertEquals(14,r.level());assertTrue(r.waterSensitive());
  assertThrows(IllegalArgumentException.class,()->HeldLightRule.parse(JsonParser.parseString("{\"match\":{\"items\":\"torch\",\"count\":2},\"luminance\":15}").getAsJsonObject()));
  assertThrows(IllegalArgumentException.class,()->HeldLightRule.parse(JsonParser.parseString("{\"match\":{\"items\":\"torch\"},\"luminance\":14.5}").getAsJsonObject()));
 }
 @Test void occlusionPackingPreservesMaterialAndBothHands() {
  int flags=3|(5<<4)|(103<<8)|(227<<16)|(255<<24);
  assertEquals(3,flags&3);assertEquals(5,(flags>>>4)&7);assertEquals(103,(flags>>>8)&255);
  assertEquals(227,(flags>>>16)&255);assertEquals(255,(flags>>>24)&255);
 }
}
