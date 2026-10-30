package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.client.DhLodCapture;
import dev.comfyfluffy.caustica.rt.entity.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhPackedQuadTest {
 private static DhLodCapture.SectionSnapshot source(int d,boolean water,int origin){
  var q=new DhLodCapture.LodQuad((short)origin,(short)origin,(short)origin,(short)16,(short)24,0xff336699,(short)65535,(byte)0,(byte)15,(byte)0,d,water,water);
  return new DhLodCapture.SectionSnapshot(1,6,0,0,List.of(q),water?1:0,1,-64);
 }
 @Test void sourceFacesWaterEdgesAndMaterialsAreLossless(){
  for(int origin:new int[]{0,100,30000})for(int d=0;d<6;d++)for(boolean water:new boolean[]{false,true}){
   var s=source(d,water,origin);var legacy=ByteBuffer.allocate((int)DhQuadMesh.bytes(1)).order(ByteOrder.nativeOrder());DhQuadMesh.write(legacy,s);
   var packed=ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());DhPackedQuads.write(packed,s);
   int axis=d<2?1:d<4?2:0;
   for(int a=0;a<3;a++){
    float lo=Float.POSITIVE_INFINITY,hi=Float.NEGATIVE_INFINITY;
    for(int v=0;v<4;v++){float x=legacy.getFloat(v*12+a*4);lo=Math.min(lo,x);hi=Math.max(hi,x);}
    float mn=packed.getFloat(a*4),mx=packed.getFloat(12+a*4);
    if(a==axis){assertTrue(mn<lo);assertTrue(mx>hi);assertEquals(lo,(mn+mx)*.5f,0.0f);}
    else{assertEquals(lo,mn);assertEquals(hi,mx);}
   }
   assertEquals(0xff336699,packed.getInt(24));int meta=packed.getInt(28);
   assertEquals(65535,meta&65535);assertEquals(d,(meta>>>16)&7);assertEquals(water,(meta&(1<<19))!=0);
  }
  assertEquals(32_000_000,DhPackedQuads.bytes(1_000_000));
  assertEquals(200_000_000,DhQuadMesh.bytes(1_000_000));
 }
 // Independent Moller-Trumbore reference checks exact rectangle intersections against the old triangles.
 private static double triangle(double[] o,double[] dir,float[] v,int a,int b,int c){
  double[] e1=new double[3],e2=new double[3],p=new double[3],t=new double[3],q=new double[3];
  for(int i=0;i<3;i++){e1[i]=v[3*b+i]-v[3*a+i];e2[i]=v[3*c+i]-v[3*a+i];t[i]=o[i]-v[3*a+i];}
  cross(dir,e2,p);double det=dot(e1,p);if(Math.abs(det)<1e-12)return Double.POSITIVE_INFINITY;
  double u=dot(t,p)/det;if(u<0||u>1)return Double.POSITIVE_INFINITY;
  cross(t,e1,q);double w=dot(dir,q)/det;if(w<0||u+w>1)return Double.POSITIVE_INFINITY;
  double hit=dot(e2,q)/det;return hit>=.001&&hit<=1000?hit:Double.POSITIVE_INFINITY;
 }
 private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
 private static void cross(double[] a,double[] b,double[] c){c[0]=a[1]*b[2]-a[2]*b[1];c[1]=a[2]*b[0]-a[0]*b[2];c[2]=a[0]*b[1]-a[1]*b[0];}
 @Test void exactRectangleHitsAgreeWithTriangleReferenceIncludingMisses(){
  var random=new Random(1234);
  for(int d=0;d<6;d++){
   var s=source(d,false,100);float[] v=DhQuadMesh.vertices(s.quads().getFirst());
   var b=ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());DhPackedQuads.write(b,s);int axis=d<2?1:d<4?2:0;
   for(int trial=0;trial<1000;trial++){
    double[] o={90+random.nextDouble()*50,90+random.nextDouble()*50,90+random.nextDouble()*50};
    double[] dir={random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5};
    if(trial==0)dir[axis]=0;
    double expected=Math.min(triangle(o,dir,v,0,1,2),triangle(o,dir,v,0,2,3));
    double t=((b.getFloat(axis*4)+b.getFloat(12+axis*4))*.5-o[axis])/dir[axis];
    boolean hit=Double.isFinite(t)&&t>=.001&&t<=1000;
    for(int a=0;a<3;a++)if(a!=axis){double p=o[a]+dir[a]*t;hit&=p>=b.getFloat(a*4)&&p<=b.getFloat(12+a*4);}
    assertEquals(Double.isFinite(expected),hit,"face "+d+" ray "+trial);
    if(hit)assertEquals(expected,t,1e-8);
   }
  }
 }
}
