package dev.comfyfluffy.caustica.rt.terrain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.util.*;
class TerrainPrimitiveStorageTest {
 @Test void everyFieldIncludingNanPayloadAndSignedZeroSurvivesExactly() {
  var random=new Random(47381);
  for(int trial=0;trial<40;trial++) {
   float[] unique=new float[12*7];for(int i=0;i<unique.length;i++)unique[i]=Float.intBitsToFloat(random.nextInt());
   unique[0]=Float.intBitsToFloat(0x7fc12345);unique[1]=-0f;
   float[] triangles=new float[12*300];
   for(int n=0;n<300;n++)System.arraycopy(unique,random.nextInt(7)*12,triangles,n*12,12);
   var encoded=TerrainPrimitiveStorage.encode(triangles);assertTrue(encoded.usesIndex());assertTrue(encoded.byteSize()<triangles.length*4);
   var bytes=ByteBuffer.wrap(encoded.indexed()).order(ByteOrder.LITTLE_ENDIAN);
   for(int n=0;n<300;n++) {
    int offset=bytes.getInt(n*4);assertEquals(0,offset%16);
    for(int field=0;field<12;field++)assertEquals(Float.floatToRawIntBits(triangles[n*12+field]),bytes.getInt(offset+field*4));
   }
  }
 }
 @Test void uniqueRecordsUseOriginalFormatAndNeverIncreaseGpuMemory() {
  float[] input=new float[1200];for(int i=0;i<input.length;i++)input[i]=i;
  var encoded=TerrainPrimitiveStorage.encode(input);assertFalse(encoded.usesIndex());assertEquals(input.length*4,encoded.byteSize());
 }
 @Test void allSixUvCoordinatesRemainBitExactIncludingNonAtlasFractions() {
  float[] data=new float[600];float[] uv={.1234567f,-.00000001f,1.2345678f,.999999f,-0f,Float.intBitsToFloat(0x7fc54321)};
  for(int i=0;i<100;i++)System.arraycopy(uv,0,data,i*6,6);
  var encoded=TerrainPrimitiveStorage.encodeUvs(data);assertTrue(encoded.usesIndex());
  var bytes=ByteBuffer.wrap(encoded.indexed()).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<100;i++)for(int field=0;field<6;field++)assertEquals(Float.floatToRawIntBits(uv[field]),bytes.getInt(bytes.getInt(i*4)+field*4));
 }
 @Test void invalidTriangleRecordsAreRejectedBeforeUpload() { assertThrows(IllegalArgumentException.class,()->TerrainPrimitiveStorage.encode(new float[13])); }
}
