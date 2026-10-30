package dev.comfyfluffy.caustica.rt.entity;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VertexDisplacementsTest {
 @Test void exactMotionPackingReusesBackingAndTruncatesLogicalLength() {
  FloatArrayList out=new FloatArrayList(100);
  float[] backing=out.elements();
  VertexDisplacements.fill(new float[]{1,2,3,4,5,6},6,new float[]{.5f,3,1,4,4,7},out);
  assertArrayEquals(new float[]{.5f,-1,2,0,0,1,-1,0},out.toFloatArray());
  assertSame(backing,out.elements());
  VertexDisplacements.fill(new float[]{2,4,6},3,new float[]{1,2,3},out);
  assertEquals(4,out.size());assertArrayEquals(new float[]{1,2,3,0},out.toFloatArray());
  assertSame(backing,out.elements());
  VertexDisplacements.fill(new float[0],0,new float[0],out);assertEquals(0,out.size());
 }
 @Test void topologyMismatchFailsBeforeWriting() {
  var out=new FloatArrayList();
  assertThrows(IllegalArgumentException.class,()->VertexDisplacements.fill(new float[3],3,new float[0],out));
 }
}
