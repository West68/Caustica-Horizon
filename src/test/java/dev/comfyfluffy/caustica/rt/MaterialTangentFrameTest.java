package dev.comfyfluffy.caustica.rt;
import org.joml.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MaterialTangentFrameTest {
 private Vector3f tangentNormal(Vector3f[] p,Vector2f[] uv,Vector3f n,int[] order) {
  int i=order[0],j=order[1],k=order[2];
  Vector2f a=new Vector2f(uv[j]).sub(uv[i]),b=new Vector2f(uv[k]).sub(uv[i]);
  float det=a.x*b.y-a.y*b.x;
  Vector3f e1=new Vector3f(p[j]).sub(p[i]),e2=new Vector3f(p[k]).sub(p[i]);
  Vector3f t=new Vector3f(e1).mul(b.y).sub(new Vector3f(e2).mul(a.y)).div(det);
  t.sub(new Vector3f(n).mul(n.dot(t))).normalize();
  Vector3f bv=new Vector3f(e2).mul(a.x).sub(new Vector3f(e1).mul(b.x)).div(det);
  Vector3f bitangent=new Vector3f(n).cross(t);
  if(bitangent.dot(bv)<0)bitangent.negate();
  return t.mul(.3f).add(bitangent.mul(.6f)).add(new Vector3f(n).mul((float)java.lang.Math.sqrt(.55))).normalize();
 }
 @Test void normalMapIsInvariantUnderTriangleReorderingMirroredUvAndBackfaces() throws Exception {
  Vector3f[] p={new Vector3f(0,0,0),new Vector3f(1,0,0),new Vector3f(0,0,1)};
  int[][] permutations={{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
  for(float mirror:new float[]{1,-1})for(float side:new float[]{1,-1}) {
   Vector2f[] uv={new Vector2f(),new Vector2f(mirror,0),new Vector2f(0,1)};
   Vector3f n=new Vector3f(0,side,0),expected=tangentNormal(p,uv,n,permutations[0]);
   for(int[] permutation:permutations)assertTrue(expected.distance(tangentNormal(p,uv,n,permutation))<1e-6f);
   // Increasing texture V is +world Z on either face, independent of vertex/UV winding.
   assertTrue(expected.z>0);
  }
  String shader=Files.readString(Path.of("shaders/world/world.rchit.slang"));
  assertTrue(shader.contains("dot(B, braw) < 0.0"));
  assertFalse(shader.contains("cross(n, T) * (det < 0.0"));
 }
 @Test void heldBlockReliefMatchesPlacedBlockUnderModelScaleRotationAndMirroredUv() {
  var rotation=new Quaternionf().rotateXYZ(.7f,-.4f,1.2f);
  var view=new Vector3f(.3f,.2f,1).normalize();
  Vector2f baseline=null;
  for(float scale:new float[]{1,.25f,.375f,.75f,2})for(float mirror:new float[]{1,-1}) {
   var u=new Vector3f(scale*mirror,0,0).rotate(rotation);
   var v=new Vector3f(0,scale,0).rotate(rotation);
   var n=new Vector3f(0,0,1).rotate(rotation);
   var ray=new Vector3f(view).rotate(rotation);
   float a=u.dot(u),b=u.dot(v),c=v.dot(v),metric=a*c-b*b;
   float depth=.25f*(float)java.lang.Math.sqrt(java.lang.Math.sqrt(metric));
   assertEquals(.25f*scale,depth,1e-6f);
   var dualU=new Vector3f(u).mul(c).sub(new Vector3f(v).mul(b)).div(metric);
   var dualV=new Vector3f(v).mul(a).sub(new Vector3f(u).mul(b)).div(metric);
   var shift=new Vector2f(ray.dot(dualU),ray.dot(dualV)).mul(depth/ray.dot(n));
   shift.x*=mirror;
   if(baseline==null)baseline=new Vector2f(shift);
   assertTrue(baseline.distance(shift)<1e-6f,"Same tile-relative displacement at every model scale");
   var relief=new Vector3f(n).sub(new Vector3f(dualU).mul(.7f*mirror*depth)).sub(new Vector3f(dualV).mul(.2f*depth)).normalize();
   var expected=new Vector3f(-.175f,-.05f,1).normalize().rotate(rotation);
   assertTrue(expected.distance(relief)<1e-6f,"Same relief normal after rotating the model");
  }
 }
}
