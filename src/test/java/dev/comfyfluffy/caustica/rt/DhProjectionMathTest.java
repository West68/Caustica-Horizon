package dev.comfyfluffy.caustica.rt;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhProjectionMathTest {
 @Test void premultipliedFadePreservesAlphaOverEnergyInsteadOfBlackEdges() {
  java.util.Random random=new java.util.Random(22026);
  double sourceGamma=.8,foreground=Math.pow(sourceGamma,2.2),background=.1;
  for(double alpha:new double[]{.05,.25,.5,1}) {
   double stored=sourceGamma*alpha;
   assertEquals(foreground,Math.pow(stored/alpha,2.2),1e-12);
   double sum=0;int count=100000;
   for(int i=0;i<count;i++)sum+=random.nextDouble()<alpha?foreground:background;
   assertEquals(alpha*foreground+(1-alpha)*background,sum/count,.004);
  }
 }

 @Test void dhRowMajorMatricesRoundTripProjectionAtLargeCameraCoordinates() {
  Matrix4f vp=new Matrix4f().perspective((float)Math.toRadians(75),16f/9,8,10000,true)
          .rotateX(.3f).rotateY(.8f);
  float[] rows=new float[16];new Matrix4f(vp).transpose().get(rows);
  Matrix4f decoded=DhProjectionMath.fromRows(rows,new Matrix4f());
  assertTrue(vp.equals(decoded,1e-6f));
  Matrix4f inverse=new Matrix4f(decoded).invert();
  Vector3f relative=new Vector3f(-200,80,-500),clip=new Vector3f();
  decoded.transformProject(relative,clip);inverse.transformProject(clip,clip);
  assertEquals(relative.x,clip.x,.003);assertEquals(relative.y,clip.y,.003);assertEquals(relative.z,clip.z,.003);
  // No float conversion of the absolute world anchor is involved.
  double camera=29_999_999.25;assertEquals(camera+relative.x,camera+(double)clip.x,.003);
 }
 @Test void rasterJitterMatchesCausticaRayJitterExactly() {
  Matrix4f base=new Matrix4f().perspective(1.1f,1.6f,.05f,10000,true);
  for(int w:new int[]{640,853,1280,1920}) {
   float jx=.31f,jy=-.24f;int h=w*9/16;
   Matrix4f shifted=DhProjectionMath.jittered(base,jx,jy,w,h,new Matrix4f());
   Vector3f point=new Vector3f(.3f,.2f,-100),a=new Vector3f(),b=new Vector3f();
   base.transformProject(point,a);shifted.transformProject(point,b);
   assertEquals(a.x-2*jx/w,b.x,1e-7);assertEquals(a.y-2*jy/h,b.y,1e-7);
  }
 }
 @Test void dhNearFarDepthIsNeverComparedDirectlyWithMinecraftDepth() {
  for(boolean zeroToOne:new boolean[]{false,true}) {
   Matrix4f lod=new Matrix4f().perspective(1f,1.5f,16,8192,zeroToOne);
   Matrix4f inverse=new Matrix4f(lod).invert();
   Matrix4f mc=new Matrix4f().perspective(1f,1.5f,.05f,10000,true);
   Vector3f world=new Vector3f(10,3,-200),ndc=new Vector3f(),reconstructed=new Vector3f();
   lod.transformProject(world,ndc);inverse.transformProject(ndc,reconstructed);
   Vector3f a=new Vector3f(),b=new Vector3f();mc.transformProject(world,a);mc.transformProject(reconstructed,b);
   assertEquals(a.z,b.z,1e-6);assertTrue(reconstructed.z < -199);
  }
 }
}
