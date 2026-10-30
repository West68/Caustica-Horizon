package dev.comfyfluffy.caustica.rt.entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtTriangleEffectsTest {
 @Test void customTrianglesHaveNoDegenerateQuadTailAndCarryVertexAlpha(){
  var c=new RtEntityCapture();
  c.addTriangleVertex(0,0,0,0x00FFFFFF,0,0,0,0,1);
  c.addTriangleVertex(1,0,0,0x7FFFFFFF,1,0,0,0,1);
  c.addTriangleVertex(0,1,0,0xFFFFFFFF,0,1,0,0,1);
  assertEquals(9,c.verts.size());assertEquals(6,c.uvList.size());assertEquals(3,c.idx.size());assertEquals(12,c.prim.size());assertEquals(1,c.alphaBuckets.size());
  assertEquals(512|1024,Float.floatToRawIntBits(c.prim.getFloat(9)));assertEquals(0xFF7F00,Float.floatToRawIntBits(c.prim.getFloat(10)));
  assertArrayEquals(new int[]{0,1,2},c.idx.toIntArray());
  assertEquals(0xFFFFFF,Float.floatToRawIntBits(c.prim.getFloat(3)));
  assertEquals(0xFFFFFF,Float.floatToRawIntBits(c.prim.getFloat(11)));
  assertTrue(Float.isFinite(c.prim.getFloat(3)));
  c.addTriangleVertex(0,0,1,-1,0,0,0,0,1);c.addTriangleVertex(1,0,1,-1,1,0,0,0,1);c.addTriangleVertex(0,1,1,-1,0,1,0,0,1);
  assertArrayEquals(new int[]{0,1,2,3,4,5},c.idx.toIntArray());assertEquals(24,c.prim.size());
  c.requireCompleteQuads("triangle test");assertEquals(6,c.packGeometry().indices().size());
 }
 @Test void independentTriangleColoursAreStoredWithoutNanPayloads(){
  var c=new RtEntityCapture();c.currentAlphaBucket=dev.comfyfluffy.caustica.rt.accel.RtAccel.ENTITY_BUCKET_OPAQUE;
  c.addTriangleVertex(0,0,0,0xFFFF0000,0,0,0,0,1);
  c.addTriangleVertex(1,0,0,0x8000FF00,1,0,0,0,1);
  c.addTriangleVertex(0,1,0,0xFF0000FF,0,1,0,0,1);
  assertEquals(0x00FF00,Float.floatToRawIntBits(c.prim.getFloat(3)));
  assertEquals(0x0000FF,Float.floatToRawIntBits(c.prim.getFloat(11)));
  assertEquals(0xFF80FF,Float.floatToRawIntBits(c.prim.getFloat(10)));
  assertEquals(dev.comfyfluffy.caustica.rt.accel.RtAccel.ENTITY_BUCKET_ANY_HIT,c.alphaBuckets.getInt(0));
  assertEquals(1f,c.prim.getFloat(4));assertEquals(0f,c.prim.getFloat(5));
  assertTrue(Float.isFinite(c.prim.getFloat(3)));
 }
 @Test void uniformModelAlphaIsNotDiscarded(){
  var c=new RtEntityCapture();float[] x={0,1,1,0},y={0,0,1,1},z={0,0,0,0},uv={0,1,1,0};
  c.addDirectQuad(x,y,z,uv,uv,0,0,1,0x80FFFFFF);
  assertEquals(512,Float.floatToRawIntBits(c.prim.getFloat(9)));
  assertEquals(0x808080,Float.floatToRawIntBits(c.prim.getFloat(10)));
  assertEquals(dev.comfyfluffy.caustica.rt.accel.RtAccel.ENTITY_BUCKET_ANY_HIT,c.alphaBuckets.getInt(0));
 }
}
