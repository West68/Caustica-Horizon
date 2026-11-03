package dev.comfyfluffy.caustica.client;
import java.nio.*;
import java.util.*;
import dev.comfyfluffy.caustica.rt.entity.DhPackedQuads;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhGeometryRevisionTest {
 private static DhLodCapture.SectionSnapshot s(int sky,int block,int x,int color,boolean water){
  var q=new DhLodCapture.LodQuad((short)x,(short)100,(short)0,(short)16,(short)16,color,(short)65535,(byte)1,(byte)sky,(byte)block,1,water,water);
  return new DhLodCapture.SectionSnapshot(6,6,0,0,List.of(q),water?1:0,sky+block+1,-64);
 }
 @Test void bakedSkyLightUpdatesDoNotInvalidateIdenticalHardwareGeometry(){
  var initial=s(0,0,0,0xff336699,false);
  for(int light=1;light<16;light++){
   var updated=s(light,0,0,0xff336699,false);
   var a=ByteBuffer.allocate(32);var b=ByteBuffer.allocate(32);
   DhPackedQuads.write(a,initial);DhPackedQuads.write(b,updated);
   assertArrayEquals(a.array(),b.array());assertTrue(DhLodCapture.sameGeometry(initial,updated));
  }
 }
 @Test void blockLightIsRetainedInMetadataWithoutChangingGeometry(){
  var initial=s(0,0,0,0xff336699,false);
  var updated=s(0,11,0,0xff336699,false);
  var a=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
  var b=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
  DhPackedQuads.write(a,initial);DhPackedQuads.write(b,updated);
  assertArrayEquals(Arrays.copyOf(a.array(),28),Arrays.copyOf(b.array(),28));
  assertEquals(a.getInt(28),b.getInt(28)&~(15<<23));
  assertEquals(11,(b.getInt(28)>>>23)&15);
  assertTrue(DhLodCapture.sameGeometry(initial,updated));
 }
 @Test void realGeometryColorAndWaterChangesStillInvalidateBlas(){
  var a=s(0,0,0,0xff336699,false);
  assertFalse(DhLodCapture.sameGeometry(a,s(0,0,1,0xff336699,false)));
  assertFalse(DhLodCapture.sameGeometry(a,s(0,0,0,0xff336698,false)));
  assertFalse(DhLodCapture.sameGeometry(a,s(0,0,0,0xff336699,true)));
 }
}
