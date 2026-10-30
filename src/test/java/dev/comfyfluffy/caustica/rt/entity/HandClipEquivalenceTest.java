package dev.comfyfluffy.caustica.rt.entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HandClipEquivalenceTest {
 @Test void finalPixelsMatchHandProjectionAcrossPitchYawAndOffAxisWorldEffects() {
  for(int pitch=-85;pitch<=85;pitch+=17)for(int yaw=-180;yaw<180;yaw+=36) {
   Matrix4f view=new Matrix4f().rotateX((float)Math.toRadians(pitch)).rotateY((float)Math.toRadians(yaw));
   Matrix4f hand=new Matrix4f().setPerspective(1.2f,16f/9,.05f,1000,true);
   Matrix4f world=new Matrix4f().setPerspective(.95f,16f/9,.05f,4096,true)
     .rotateZ(.09f).rotateX(.04f).translate(.025f,-.035f,.01f);
   Vector3f raw=new Matrix4f(view).invert().transformPosition(.3f,-.2f,-.7f,new Vector3f());
   for(float scale:new float[]{.2f,.5f,1f,2f}) {
    Matrix4f map=HandProjection.toWorld(world,hand,view,7,-2,11).scale(scale);
    Vector3f mapped=map.transformProject(raw,new Vector3f()).sub(7,-2,11);
    Vector3f actual=new Matrix4f(world).mul(view).transformProject(mapped,new Vector3f());
    Vector3f expected=new Matrix4f(hand).mul(view).transformProject(new Vector3f(raw).mul(scale),new Vector3f());
    assertEquals(expected.x,actual.x,2e-4);assertEquals(expected.y,actual.y,2e-4);
    assertEquals(expected.z,actual.z,2e-4);
   }
  }
 }
 @Test void inverseTransposePlaneRemainsOrthogonalToTransformedFace() {
  Matrix4f world=new Matrix4f().setPerspective(1,1.7f,.05f,4000,true).rotateX(.07f).translate(.02f,0,0);
  Matrix4f hand=new Matrix4f().setPerspective(1.2f,1.7f,.05f,1000,true);
  Matrix4f map=HandProjection.toWorld(world,hand,new Matrix4f(),3,4,5).scale(.5f);
  Vector4f plane=new Matrix4f(map).invert().transpose().transform(new Vector4f(0,0,1,.7f));
  for(float x:new float[]{-.4f,0,.4f})for(float y:new float[]{-.2f,.2f}) {
   Vector3f p=map.transformProject(x,y,-.7f,new Vector3f());
   assertEquals(0,plane.x*p.x+plane.y*p.y+plane.z*p.z+plane.w,1e-4);
  }
 }
}
