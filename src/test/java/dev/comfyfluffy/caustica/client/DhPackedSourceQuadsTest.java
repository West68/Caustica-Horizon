package dev.comfyfluffy.caustica.client;
import java.util.*;
import java.nio.*;
import org.junit.jupiter.api.Test;
import dev.comfyfluffy.caustica.rt.entity.DhPackedQuads;
import static org.junit.jupiter.api.Assertions.*;
class DhPackedSourceQuadsTest {
 private static void add(DhPackedSourceQuads.Builder b,DhLodCapture.LodQuad q){b.add(q.x(),q.y(),q.z(),q.widthEastWest(),q.widthNorthSouthOrHeight(),q.color(),q.textureTileId(),q.materialId(),q.skyLight(),q.blockLight(),q.direction(),q.transparent(),q.water(),q.vegetation());}
 @Test void everySourceBitSurvivesPackingAndGpuUploadDoesNotChange(){
  var r=new Random(4321);var original=new ArrayList<DhLodCapture.LodQuad>();var builder=new DhPackedSourceQuads.Builder(0);
  for(int i=0;i<4096;i++){
   var q=new DhLodCapture.LodQuad((short)(r.nextInt(60000)-30000),(short)r.nextInt(30000),(short)(r.nextInt(60000)-30000),
    (short)(1+r.nextInt(2048)),(short)(1+r.nextInt(2048)),r.nextInt(),(short)r.nextInt(65536),
    (byte)r.nextInt(256),(byte)r.nextInt(16),(byte)r.nextInt(16),i%6,r.nextBoolean(),r.nextBoolean(),r.nextBoolean());
   original.add(q);add(builder,q);
  }
  var packed=builder.build();assertEquals(original,packed);
  var a=ByteBuffer.allocate(4096*32);var b=ByteBuffer.allocate(4096*32);
  DhPackedQuads.write(a,new DhLodCapture.SectionSnapshot(1,6,0,0,original,0,1,-64));
  DhPackedQuads.write(b,new DhLodCapture.SectionSnapshot(1,6,0,0,packed,0,1,-64));
  assertArrayEquals(a.array(),b.array());
  assertThrows(IllegalStateException.class,()->add(builder,original.getFirst()));
  assertThrows(IllegalStateException.class,builder::build);
  assertThrows(UnsupportedOperationException.class,()->packed.set(0,original.getFirst()));
 }
 @Test void packedRevisionComparisonIgnoresOnlyBakedLights(){
  var a=new DhPackedSourceQuads.Builder(1);var b=new DhPackedSourceQuads.Builder(1);var c=new DhPackedSourceQuads.Builder(1);
  a.add((short)0,(short)80,(short)0,(short)16,(short)16,0xff123456,(short)65535,(byte)1,(byte)0,(byte)0,1,false,false,true);
  b.add((short)0,(short)80,(short)0,(short)16,(short)16,0xff123456,(short)65535,(byte)1,(byte)15,(byte)15,1,false,false,true);
  c.add((short)0,(short)80,(short)0,(short)16,(short)16,0xff123457,(short)65535,(byte)1,(byte)0,(byte)0,1,false,false,true);
  var x=a.build();assertTrue(x.sameRtGeometry(b.build()));assertFalse(x.sameRtGeometry(c.build()));
 }
}
