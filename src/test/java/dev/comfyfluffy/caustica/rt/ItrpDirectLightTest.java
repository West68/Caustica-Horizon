package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class ItrpDirectLightTest {
 @Test void finiteDepthMatchesIndependentIntegral() {
  for(float y:new float[]{.00001f,.01f,.1f,.5f,1f}) for(float rain:new float[]{0,.5f,1}) {
   var actual=ItrpDirectLight.evaluate(y,rain,false);
   // Composite midpoint quadrature independently checks the finite column integral.
   double column=0;int n=8192;
   for(int j=0;j<n;j++)column+=.14*Math.exp(-.5*y*(j+.5)*80/n)*80/n;
   double[] r={.17,.4,.99};
   for(int j=0;j<3;j++)assertEquals(21*Math.min(y*40,1)*Math.exp(-(r[j]+.05+.5*rain)*column)*Math.pow(2,-r[j]*.2),actual[j],2e-5);
  }
 }
 @Test void horizonRainAndNightAreBounded() {
  assertArrayEquals(new float[]{0,0,0},ItrpDirectLight.evaluate(0,0,false));
  var day=ItrpDirectLight.evaluate(.8f,0,false);var wet=ItrpDirectLight.evaluate(.8f,1,false);var moon=ItrpDirectLight.evaluate(.8f,0,true);
  for(int i=0;i<3;i++){assertTrue(wet[i]<day[i]);assertEquals(day[i]*.016,moon[i],1e-6);}
 }
}
