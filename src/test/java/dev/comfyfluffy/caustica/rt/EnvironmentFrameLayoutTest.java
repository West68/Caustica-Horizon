package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import java.nio.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
class EnvironmentFrameLayoutTest {
 @Test void newLightsRemainBdaFrameFieldsAndDoNotExpandPushConstants() throws Exception {
  var components=WorldPushData.class.getRecordComponents();Class<?>[] types=new Class<?>[components.length];Object[] values=new Object[components.length];
  for(int i=0;i<components.length;i++) {
   types[i]=components[i].getType();Class<?> type=types[i];
   if(type==long.class)values[i]=13L;else if(type==int.class)values[i]=7;
   else if(type.getSimpleName().equals("Matrix4fc"))values[i]=new Matrix4f();
   else if(type==WorldPushData.Int4.class)values[i]=new WorldPushData.Int4(9,10,11,12);
   else if(type==WorldPushData.Float3.class)values[i]=new WorldPushData.Float3(1,2,3);
   else if(type==WorldPushData.Float2.class)values[i]=new WorldPushData.Float2(1,2);
   else if(type==WorldPushData.Float4.class)values[i]=new WorldPushData.Float4(i,i+.25f,i+.5f,i+.75f);
   else if(type==WorldPushData.BreakEntry[].class)values[i]=new WorldPushData.BreakEntry[]{new WorldPushData.BreakEntry(new WorldPushData.Int4(1,2,3,4))};
   else fail("Unexamined ABI field "+components[i]);
  }
  var frame=WorldPushData.class.getDeclaredConstructor(types).newInstance(values);
  ByteBuffer bytes=ByteBuffer.allocateDirect(WorldPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());frame.write(bytes);
  assertEquals(1056,WorldPushData.BYTE_SIZE);assertEquals(40,WorldPushConstantsData.BYTE_SIZE);
  String[] fields={"mainLightPosition","mainLightColor","offLightPosition","offLightColor","environment","mediumParams","dimensionFog","visualLight","surfaceControl","dimensionControl"};
  for(int i=0;i<fields.length;i++) {
   var v=(WorldPushData.Float4)WorldPushData.class.getMethod(fields[i]).invoke(frame);
   assertEquals(v.x(),bytes.getFloat(512+i*16));assertEquals(v.w(),bytes.getFloat(524+i*16));
  }
  assertEquals(13L,bytes.getLong(688));
  var origin=frame.torchGuideOrigin();
  assertEquals(origin.x(),bytes.getFloat(704));assertEquals(origin.w(),bytes.getFloat(716));
  assertEquals(1,bytes.getInt(720));assertEquals(4,bytes.getInt(732));assertEquals(13L,bytes.getLong(80));
 }
}
