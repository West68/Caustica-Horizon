package dev.comfyfluffy.caustica.rt;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhBridgeContractTest {
 @Test void optionalShaderWorkIsCompiledOutWithoutDh() throws Exception {
  String shader=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertTrue(shader.contains("#ifdef CAUSTICA_DH"));
  String build=Files.readString(Path.of("build.gradle"));
  assertTrue(build.contains("world_dh.rgen.spv"));assertTrue(build.contains("world_nv_dh.rgen.spv"));
  assertTrue(build.contains("world_nv_noreorder_dh.rgen.spv"));
 }
 @Test void nativeLodIsFreshScopedAndNeverEntersTlas() throws Exception {
  String code=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/client/DistantHorizonsCompat.java"));
  assertTrue(code.contains("capturing=false;"));
  assertTrue(code.contains("api.stateProjection.set(state,oldProjection)"));
  assertTrue(code.contains("if(!cleared)"));
  assertFalse(code.contains("RtAccel"));assertFalse(code.contains("createStorageImage"));
  assertTrue(code.contains("createCommandEncoder().submit();ctx.waitIdle();"));
  String textures=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityTextures.java"));
  assertTrue(textures.contains("slot=capacity-1-externalSlots.size()"));
 }
}
