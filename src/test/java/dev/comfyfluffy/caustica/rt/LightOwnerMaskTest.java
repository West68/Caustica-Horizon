package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.rt.entity.LightOwnerMask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LightOwnerMaskTest {
 @Test void ownerMetadataReusesReservedTableLaneWithoutGrowingPayload() throws Exception {
  String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java"));
  String hit=java.nio.file.Files.readString(java.nio.file.Path.of("shaders/world/world.rchit.slang"));
  String ray=java.nio.file.Files.readString(java.nio.file.Path.of("shaders/world/world.rgen.slang"));
  assertTrue(source.contains("entity == mc.player"));
  assertTrue(source.contains("entry + 56, (mask & LightOwnerMask.HELD_SHADOW) == 0 ? 1 : 0"));
  assertTrue(hit.contains("if(g.reserved.x!=0u) payload.flags |= PAYLOAD_HELD_OWNER"));
  assertTrue(ray.contains("if((payload.flags & PAYLOAD_HELD_OWNER)!=0u)return"));
 }
 @Test void ownerRetainsCameraAndGeneralShadowsButNotOwnLampShadow(){
  int mask=LightOwnerMask.receiver(255,true);
  assertEquals(3,mask&3);assertEquals(0,mask&4);
  assertEquals(1,LightOwnerMask.receiver(1,true));
  assertEquals(5,LightOwnerMask.receiver(1,false));
  assertEquals(255,LightOwnerMask.receiver(255,false));
 }
}
