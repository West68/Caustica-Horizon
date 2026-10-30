package dev.comfyfluffy.caustica.rt.pipeline;
import java.lang.foreign.*;
import java.lang.reflect.Method;
import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StreamlineCoordinateTest {
 private MemorySegment constants(Arena arena,boolean present,Matrix4f projection,Matrix4f previous,float dx,float dy,float dz)throws Exception {
  var data=arena.allocate(448,8);
  Method m=RtStreamlineFg.class.getDeclaredMethod("writeConstants",MemorySegment.class,Matrix4fc.class,Matrix4fc.class,Matrix4fc.class,Matrix4fc.class,float.class,float.class,int.class,int.class,double.class,double.class,double.class,float.class,float.class,float.class,boolean.class,boolean.class);
  m.setAccessible(true);m.invoke(RtStreamlineFg.INSTANCE,data,projection,projection,previous,new Matrix4f(),.25f,-.375f,1280,720,12d,70d,-4d,dx,dy,dz,false,present);return data;
 }
 private Matrix4f matrix(MemorySegment data,long offset){float[] a=new float[16];for(int i=0;i<16;i++)a[i]=data.get(ValueLayout.JAVA_FLOAT,offset+i*4L);return new Matrix4f().set(a);}
 @Test void jitterAndMotionScaleReflectExactlyOnce()throws Exception {
  var p=new Matrix4f().perspective(1.2f,16f/9f,.05f,2000f,true);
  try(var a=Arena.ofConfined()) {
   var rr=constants(a,false,p,p,0,0,0);var fg=constants(a,true,p,p,0,0,0);
   assertEquals(-.25f,rr.get(ValueLayout.JAVA_FLOAT,320));assertEquals(.375f,rr.get(ValueLayout.JAVA_FLOAT,324));
   assertEquals(-.25f,fg.get(ValueLayout.JAVA_FLOAT,320));assertEquals(-.375f,fg.get(ValueLayout.JAVA_FLOAT,324));
   assertEquals(1f/1280,fg.get(ValueLayout.JAVA_FLOAT,328));assertEquals(-1f/720,fg.get(ValueLayout.JAVA_FLOAT,332));
  }
 }
 @Test void cameraTranslationAndPresentationReflectionMatchProjectedPoint()throws Exception {
  var p=new Matrix4f().perspective(1.1f,16f/9f,.05f,2000f,true);
  var prev=new Matrix4f(p).rotateY(.08f);var flip=new Matrix4f().scaling(1,-1,1);
  try(var a=Arena.ofConfined()) {
   var raw=constants(a,false,p,prev,.3f,-.1f,.2f);var presented=constants(a,true,p,prev,.3f,-.1f,.2f);
   var clip=new Vector4f(.5f,.2f,-8f,1).mul(p);
   var expected=new Vector4f(.8f,.1f,-7.8f,1).mul(prev);
   var actual=new Vector4f(clip).mul(matrix(raw,192));assertEquals(expected.x/expected.w,actual.x/actual.w,1e-5);assertEquals(expected.y/expected.w,actual.y/actual.w,1e-5);
   var reflected=new Vector4f(clip).mul(flip).mul(matrix(presented,192));
   assertEquals(expected.x/expected.w,reflected.x/reflected.w,1e-5);assertEquals(-expected.y/expected.w,reflected.y/reflected.w,1e-5);
   var roundTrip=new Matrix4f(matrix(presented,192)).mul(matrix(presented,256));assertTrue(roundTrip.equals(new Matrix4f(),1e-4f));
  }
 }
}
