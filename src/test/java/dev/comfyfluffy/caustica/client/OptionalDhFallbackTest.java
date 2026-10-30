package dev.comfyfluffy.caustica.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OptionalDhFallbackTest {
 static final class OlderSection { private Object levelWrapper; }
 static final class NewerSection { private Object clientLevelWrapper; }
 @Test void supportsBothInstalledSectionAbis() throws Exception {
  assertEquals("levelWrapper",DhLodCapture.levelWrapperField(OlderSection.class).getName());
  assertEquals("clientLevelWrapper",DhLodCapture.levelWrapperField(NewerSection.class).getName());
  assertThrows(NoSuchFieldException.class,()->DhLodCapture.levelWrapperField(Object.class));
 }
 @Test void unavailableCaptureSelectionCanBeFilteredAndSorted() {
  var sections=DhLodCapture.activeSections(new Object());
  assertDoesNotThrow(()->{sections.removeIf(s->true);sections.sort((a,b)->0);sections.clear();});
  assertTrue(sections.isEmpty());
 }
 @Test void repeatedUnchangedBuildsKeepTheExistingGpuVersion() {
  var q=new DhLodCapture.LodQuad((short)0,(short)1,(short)0,(short)1,(short)1,0xff123456,(short)0,(byte)0,(byte)15,(byte)0,1,false,false);
  var a=new DhLodCapture.SectionSnapshot(1,6,0,0,java.util.List.of(q),0,1,-64);
  var repeated=new DhLodCapture.SectionSnapshot(1,6,0,0,java.util.List.of(q),0,2,-64);
  var changed=new DhLodCapture.SectionSnapshot(1,6,0,0,java.util.List.of(q),0,3,0);
  assertTrue(DhLodCapture.sameGeometry(a,repeated));
  assertFalse(DhLodCapture.sameGeometry(a,changed));
  assertFalse(DhLodCapture.sameGeometry(null,repeated));
 }
 @Test void failureAndEpochFallbackNeverSwallowRasterHands() throws Exception {
  var c=VanillaRenderController.INSTANCE;
  String[] fields={"rtActive","baseReady","projectionCaptured","worldSkipped"};
  boolean[] old=new boolean[fields.length];
  try {
   for(int i=0;i<fields.length;i++){var f=VanillaRenderController.class.getDeclaredField(fields[i]);f.setAccessible(true);old[i]=f.getBoolean(c);f.setBoolean(c,true);}
   assertTrue(c.shouldCaptureRtFirstPerson());
   for(String name:fields){var f=VanillaRenderController.class.getDeclaredField(name);f.setAccessible(true);f.setBoolean(c,false);assertFalse(c.shouldCaptureRtFirstPerson(),name);f.setBoolean(c,true);}
  } finally {for(int i=0;i<fields.length;i++){var f=VanillaRenderController.class.getDeclaredField(fields[i]);f.setAccessible(true);f.setBoolean(c,old[i]);}}
 }
}
