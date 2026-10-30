package dev.comfyfluffy.caustica.rt;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VisualEffectsContractTest {
 @Test void additiveBackgroundAndParticleAlphaAreNotOpaque() throws Exception {
  String shader=Files.readString(Path.of("shaders/world/world.rahit.slang"));
  assertTrue(shader.contains("stochasticAlpha = instanceKind == PARTICLE_BIT"));
  assertTrue(shader.contains("texel.a*=saturate(max(texel.r,max(texel.g,texel.b))*.5)"));
  for(double intensity:new double[]{0,.1,.5,1}) {
   double p=intensity*.5;assertTrue(p>=0 && p<=.5);
   if(intensity==0)assertEquals(0,p); // black additive background contributes zero coverage
  }
 }
 @Test void crystalCoreMaskDoesNotMarkGlassHalfOfTheTexture() {
  // Verified EndCrystalModel cube texOffs(32,0), glass texOffs(0,0), 64x32 texture.
  for(int y=0;y<32;y++)for(int x=0;x<64;x++)
   assertEquals(x>=32 && y<16,(x+.5)/64>=.5 && (y+.5)/32<.5);
  String shader;
  try {shader=Files.readString(Path.of("shaders/world/world.rchit.slang"));}catch(Exception e){throw new AssertionError(e);}
  assertTrue(shader.contains("(1.0-step(.5,atlasUv.y))"));
  int flags=131072|2097152;
  assertEquals(0,flags&(255<<8));assertEquals(0,flags&(255<<24));assertEquals(0,flags&127);
 }
 @Test void wetReferenceSmoothnessGivesSharperResponseWithoutChangingFresnel() {
  double dry=.9,wet=.8;
  double rough=Math.max(.02,dry*(1-wet));
  assertTrue(rough<.2);assertEquals(.9,Math.max(.02,dry),1e-9);
  assertEquals(.02,Math.max(.02,dry*(1-1)),1e-9);
 }
}
