package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.rt.material.RtLabPbr;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ItrpLookMathTest {
 @Test void kelvinMatchesReferenceExpressionAndNeutralPoint() {
  assertArrayEquals(new float[]{.5f,.5f,.5f},ItrpLookMath.torchColor(6500));
  for(float kelvin:new float[]{1000,3000,4500,6500,10000,12000}) {
   var c=ItrpLookMath.torchColor(kelvin);double x=kelvin-6500;
   double xc=Math.pow(Math.abs(x),1.1)*Math.signum(x);
   double[] coeff={.1,.4,.98};
   for(int i=0;i<3;i++) assertEquals(Math.exp(xc*.00045*Math.pow(coeff[i],.9))/Math.exp(xc*.00017)*.5,c[i],2e-6);
  }
  var warm=ItrpLookMath.torchColor(3000);assertTrue(warm[0]>warm[1] && warm[1]>warm[2]);
  assertThrows(IllegalArgumentException.class,()->ItrpLookMath.torchColor(Float.NaN));
 }
 @Test void porosityAndSssCategoriesRemainIndependentAtEveryAuthoredByte() {
  for(int b=0;b<=255;b++) {
   float p=RtLabPbr.decodePorosity(b/255f);
   var s=RtLabPbr.decodeSpec(.5f,.04f,b/255f,1,1,1,1);
   assertTrue(p>=0 && p<=1);
   if(b<=64){assertEquals(b/64f,p,1e-6);assertEquals(0,s.sss());}
   else {assertEquals(0,p);assertEquals((b-65)/190f,s.sss(),1e-6);}
  }
 }
 @Test void absorptionCannotCreateLightOrReverseDepthOrdering() {
  for(double k:new double[]{.25,.04,.01})for(double d:new double[]{0,.01,1,10,100}) {
   double t=Math.exp(-k*d);assertTrue(t>=0 && t<=1);assertTrue(Math.exp(-k*(d+1))<=t);
  }
  for(double p:new double[]{0,.1,.5,1})for(double w:new double[]{0,.1,.5,1}) {
   double wet=Math.max(0,Math.min(1,w*(1.8-p)-p*.2));
   double albedo=1-wet*Math.sqrt(p)*.4;assertTrue(albedo>=.6 && albedo<=1);
  }
 }
 @Test void finiteAtmosphereColumnMatchesIndependentNumericalIntegrationAndHorizonLimit() {
  for(double y:new double[]{-1,-.01,-1e-10,0,1e-10,.01,1})for(double depth:new double[]{0,.001,1,10}) {
   double x=-.5*y*depth;
   double analytic=.14*depth*(Math.abs(x)<1e-3?1+x*(.5+x/6):Math.expm1(x)/x);
   double numerical=0;int intervals=20000;
   for(int i=0;i<intervals;i++)numerical+=.14*Math.exp(-.5*y*depth*(i+.5)/intervals)*depth/intervals;
   assertEquals(numerical,analytic,Math.max(1e-10,Math.abs(numerical)*1e-7));
   assertTrue(Double.isFinite(analytic) && analytic>=0);
  }
 }

}
