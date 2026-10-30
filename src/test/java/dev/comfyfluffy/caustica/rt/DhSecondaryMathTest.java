package dev.comfyfluffy.caustica.rt;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
/** CPU reference identities only; does not execute a GPU shader or verify its visual output. */
class DhSecondaryMathTest {
 private boolean plane(float a,float b,float[] range) {
  if(Math.abs(b)<1e-12)return a>=0;
  float t=-a/b;if(b>0)range[0]=Math.max(range[0],t);else range[1]=Math.min(range[1],t);
  return range[0]<=range[1];
 }
 private float[] clip(Matrix4f projection,Vector3f origin,Vector3f direction,boolean negativeZ) {
  Vector4f a=projection.transform(new Vector4f(origin,1));
  Vector4f b=projection.transform(new Vector4f(new Vector3f(direction).mul(1000).add(origin),1)).sub(a);
  float[] r={.03f/1000,1};
  if(!plane(a.w-1e-4f,b.w,r)||!plane(a.w+a.x,b.w+b.x,r)||!plane(a.w-a.x,b.w-b.x,r)
    ||!plane(a.w+a.y,b.w+b.y,r)||!plane(a.w-a.y,b.w-b.y,r)
    ||!plane(negativeZ?a.w+a.z:a.z,negativeZ?b.w+b.z:b.z,r)||!plane(a.w-a.z,b.w-b.z,r))return null;
  return r;
 }
 private Matrix4f projection(boolean zero,boolean reverse) {
  Matrix4f p=new Matrix4f().perspective((float)Math.toRadians(80),1.6f,.1f,1000,zero);
  if(reverse) {
   // clip z' = w-z (zero-to-one), or -z (negative-one-to-one).
   Matrix4f flip=new Matrix4f().m22(-1).m32(zero?1:0);p=flip.mul(p);
  }
  return p;
 }
 @Test void frustumClippingAndPlaneReconstructionMatchAnalyticRayHitsInAllDepthConventions() {
  var random=new Random(714);
  for(boolean zero:new boolean[]{true,false})for(boolean reverse:new boolean[]{true,false}) {
   Matrix4f p=projection(zero,reverse),inverse=new Matrix4f(p).invert();
   for(int i=0;i<1000;i++) {
    Vector3f origin=new Vector3f(random.nextFloat(-2,2),random.nextFloat(-1,1),random.nextFloat(-1,.2f));
    Vector3f dir=new Vector3f(random.nextFloat(-.3f,.3f),random.nextFloat(-.2f,.2f),-1).normalize();
    float[] range=clip(p,origin,dir,!zero);assertNotNull(range);
    float expected=(-100-origin.z)/dir.z;
    assertTrue(expected>=range[0]*1000 && expected<=range[1]*1000);
    Vector3f point=new Vector3f(dir).mul(expected).add(origin);
    Vector4f projected=p.transform(new Vector4f(point,1));projected.div(projected.w);
    Vector4f restored=inverse.transform(new Vector4f(projected.x,projected.y,projected.z,1));restored.div(restored.w);
    assertEquals(expected,new Vector3f(restored.x,restored.y,restored.z).sub(origin).dot(dir),.025);
    float rayDepth=projected.z,front;
    Vector4f f=p.transform(new Vector4f(new Vector3f(dir).mul(expected*.8f).add(origin),1));front=f.z/f.w;
    assertTrue((front-rayDepth)*(reverse?-1:1)<0); // front-to-back crossing sign
   }
  }
 }
 private Vector3f unproject(Matrix4f inverse,float x,float y,float z) {
  Vector4f v=inverse.transform(new Vector4f(x,y,z,1));v.div(v.w);return new Vector3f(v.x,v.y,v.z);
 }
 private float planeDepth(Matrix4f projection,Matrix4f inverse,float x,float y,Vector3f normal,float offset,boolean zero) {
  Vector3f a=unproject(inverse,x,y,zero?0:-1),b=unproject(inverse,x,y,1),delta=b.sub(a);
  float t=(-offset-normal.dot(a))/normal.dot(delta);
  Vector4f clip=projection.transform(new Vector4f(delta.mul(t).add(a),1));return clip.z/clip.w;
 }
 @Test void screenLinearSecantIntersectionMatchesTiltedWorldPlanesWithoutLinearDepthAssumptions() {
  for(boolean zero:new boolean[]{true,false})for(boolean reverse:new boolean[]{true,false}) {
   Matrix4f p=projection(zero,reverse),inverse=new Matrix4f(p).invert();
   Vector3f origin=new Vector3f(-2,.5f,-2),dir=new Vector3f(.1f,-.08f,-1).normalize();
   Vector3f normal=new Vector3f(.2f,.1f,1).normalize();float offset=80;
   float expected=(-offset-normal.dot(origin))/normal.dot(dir);
   float[] range=clip(p,origin,dir,!zero);assertNotNull(range);
   Vector4f a=p.transform(new Vector4f(new Vector3f(dir).mul(range[0]*1000).add(origin),1));a.div(a.w);
   Vector4f b=p.transform(new Vector4f(new Vector3f(dir).mul(range[1]*1000).add(origin),1));b.div(b.w);
   float da=a.z-planeDepth(p,inverse,a.x,a.y,normal,offset,zero);
   float db=b.z-planeDepth(p,inverse,b.x,b.y,normal,offset,zero);
   float t=-da/(db-da);assertTrue(t>=0 && t<=1);
   Vector4f hit=new Vector4f(a).lerp(b,t);
   Vector3f actual=unproject(inverse,hit.x,hit.y,planeDepth(p,inverse,hit.x,hit.y,normal,offset,zero));
   assertEquals(expected,new Vector3f(actual).sub(origin).dot(dir),.03);
   assertTrue(new Vector3f(dir).mul(expected).add(origin).distance(actual)<.03);
  }
 }
 @Test void raysOutsideTheAvailableViewRemainMisses() {
  Matrix4f p=projection(true,true);
  assertNull(clip(p,new Vector3f(100,0,-1),new Vector3f(1,0,0),false));
  assertNull(clip(p,new Vector3f(0,0,1),new Vector3f(0,0,1),false));
 }
 @Test void shaderWorkAndScopeRemainBoundedAndNoNewHardwareRaysAreAdded() throws Exception {
  String helper=Files.readString(Path.of("shaders/world/dh_secondary.slang"));
  assertTrue(helper.contains("i<=12"));assertTrue(helper.contains("refine<3"));
  assertFalse(helper.contains("TraceRay"));assertFalse(helper.contains("while("));
  String shader=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertTrue(shader.contains("bounce==1 && payload.hitT<0.0"));
  assertTrue(shader.contains("L+=throughput*dhRadiance"));
  // Hand receiver shadows use the same bounded DH fallback, while primary rays stay excluded.
  assertTrue(shader.contains("hObj.IsMiss() && (mask==CULL_SECONDARY || mask==CULL_HAND_WORLD)"));
 }
}
