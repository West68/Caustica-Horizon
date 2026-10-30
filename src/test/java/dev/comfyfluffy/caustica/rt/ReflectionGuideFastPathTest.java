package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class ReflectionGuideFastPathTest {
 @Test void guideMarkerDoesNotChangePayloadLayoutAndDoesNotEnterOrdinaryPaths() throws Exception {
  String gen=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertEquals(1,gen.split("payload.flags \\|= PAYLOAD_REFLECTION_GUIDE",-1).length-1);
  assertTrue(gen.contains("payload.flags = showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u"));
  for(String name:new String[]{"world.rchit.slang","dh_shading.slang"})
   assertTrue(Files.readString(Path.of("shaders/world/"+name)).contains("payload.flags & PAYLOAD_REFLECTION_GUIDE"));
  for(String name:new String[]{"world.rahit.slang","dh.rahit.slang"})
   assertFalse(Files.readString(Path.of("shaders/world/"+name)).contains("PAYLOAD_REFLECTION_GUIDE"));
 }
}
