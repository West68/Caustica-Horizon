package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.client.DhLodCapture;
import dev.comfyfluffy.caustica.rt.entity.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhHardwareMeshTest {
 private static DhLodCapture.LodQuad q(int dir,int y,boolean water){return new DhLodCapture.LodQuad((short)0,(short)y,(short)0,(short)64,(short)64,0xff336699,(short)0,(byte)0,(byte)15,(byte)0,dir,water,water);}
 private static DhLodCapture.SectionSnapshot s(List<DhLodCapture.LodQuad> q){return new DhLodCapture.SectionSnapshot(6,6,-30000000,30000000,q,0,7,-64);}
 @Test void allSixFacesHaveCorrectAxesAndExtents(){for(int d=0;d<6;d++){float[] v=DhQuadMesh.vertices(q(d,7,false));assertEquals(12,v.length);int axis=d<2?1:d<4?2:0;for(int i=0;i<4;i++)assertEquals(axis==1?7:0,v[i*3+axis]);}}
 @Test void packedRegionsAreAlignedAndTriangleIndicesStayInsideVertices(){var mesh=s(List.of(q(1,100,false),q(1,101,true)));int n=mesh.quads().size();var b=ByteBuffer.allocate(Math.toIntExact(DhQuadMesh.bytes(n))).order(ByteOrder.nativeOrder());DhQuadMesh.write(b,mesh);assertEquals(0,DhQuadMesh.primOffset(n)%16);for(int i=0;i<n*6;i++)assertTrue(b.getInt((int)DhQuadMesh.indexOffset(n)+4*i)<n*4);assertEquals(100,b.getFloat(4));assertEquals(101-1f/9f,b.getFloat(48+4),1e-5);assertEquals(DhQuadMesh.FLAG,b.getInt((int)DhQuadMesh.primOffset(n)+36));}
 @Test void flatWaterAndSolidRemainSeparateAndCoverEveryCell(){var original=s(List.of(q(1,100,false),q(1,101,true)));var proxy=DhLodProxy.make(original,16);assertEquals(256,proxy.waterQuads());assertEquals(256,proxy.quads().stream().filter(q->q.direction()==1&&!q.water()).count());assertTrue(proxy.quads().size()<=16*16*6);assertEquals(original.minY(),proxy.minY());assertEquals(original.minBlockX(),proxy.minBlockX());assertEquals(original.version(),proxy.version());for(var q:proxy.quads())if(q.water())assertEquals(101,q.y());}
 @Test void underwaterPlantsDoNotBecomeOpaqueSeabedCurtains(){
  var plant=new DhLodCapture.LodQuad((short)0,(short)110,(short)0,(short)64,(short)64,0xff00aa00,(short)2,(byte)13,(byte)15,(byte)0,1,false,false,true);
  var result=DhLodProxy.make(s(List.of(q(1,100,false),plant,q(1,111,true))),16);
  assertEquals(256,result.quads().stream().filter(v->!v.water()&&!v.vegetation()&&v.y()==100).count());
  assertEquals(256,result.quads().stream().filter(DhLodCapture.LodQuad::vegetation).count());
  assertTrue(result.quads().stream().allMatch(v->v.direction()==1),"Flat water/bed must not acquire fake minY walls");
 }
 @Test void textureTileAndFaceSurviveUnsignedPacking(){
  var quad=new DhLodCapture.LodQuad((short)0,(short)12,(short)0,(short)16,(short)16,0xff336699,(short)65535,(byte)0,(byte)15,(byte)0,4,false,false);
  var mesh=s(List.of(quad));var b=ByteBuffer.allocate(Math.toIntExact(DhQuadMesh.bytes(1))).order(ByteOrder.nativeOrder());DhQuadMesh.write(b,mesh);
  int o=(int)DhQuadMesh.primOffset(1);assertEquals(4,b.getInt(o+40));assertEquals(65535,b.getInt(o+44));assertEquals(65535,b.getInt(o+48+44));
 }
 @Test void verticalOnlyGeometryIsNeverInventedAsTerrain(){var source=s(List.of(q(4,0,false)));assertSame(source,DhLodProxy.make(source,16));}
}
