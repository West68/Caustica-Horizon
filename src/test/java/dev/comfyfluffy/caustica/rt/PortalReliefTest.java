package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.nio.file.*;
import dev.comfyfluffy.caustica.rt.material.PortalMaterials;
class PortalReliefTest {
 @Test void onlyPortalSurfacesReceiveSpecialEmission(){
  assertEquals(32,PortalMaterials.flags("minecraft:textures/entity/end_portal.png"));
  assertEquals(32,PortalMaterials.flags("minecraft:textures/environment/end_sky.png"));
  assertEquals(64,PortalMaterials.flags("minecraft:block/nether_portal"));
  assertEquals(0,PortalMaterials.flags("minecraft:block/end_portal_frame"));
  assertEquals(0,PortalMaterials.flags("minecraft:block/05rd_clearblock_bedrock_3"));
 }
 @Test void lightColorFollowsEmittingTexelsRatherThanUniversalWarmTint(){
  var cyan=HeldLightColors.brightColor(List.of(0xff00ffff,0xff202020,0x00ff0000),new float[]{1,.5f,.2f});
  assertTrue(cyan[0]<.001);assertEquals(1,cyan[1]);assertEquals(1,cyan[2]);
  var red=HeldLightColors.brightColor(List.of(0xffff0000),new float[]{1,1,1});
  assertArrayEquals(new float[]{1,0,0},red);
  assertArrayEquals(new float[]{.2f,.3f,.4f},HeldLightColors.brightColor(List.of(),new float[]{.2f,.3f,.4f}));
 }
 @Test void explicitZeroF0DoesNotAcquireSchlickGrazingReflection() throws Exception {
  String s=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertTrue(s.contains("if(max(f0.r,max(f0.g,f0.b))<=0.0)return float3(0)"));
  assertTrue(s.contains("max(F0.r,max(F0.g,F0.b))<=0.0 ? 0.0"));
  for(int i=0;i<=100;i++){double cosine=i/100.,n=1;
   double sin2=(1-cosine*cosine)/(n*n),ct=Math.sqrt(Math.max(0,1-sin2));
   if(cosine>0){double r=(cosine-n*ct)/(cosine+n*ct);assertEquals(0,r*r,1e-20);}}
 }
 @Test void wetSpecularKeepsReconstructedReliefNormalAndDepth() throws Exception {
  String s=Files.readString(Path.of("shaders/world/world.rchit.slang"));
  assertTrue(s.contains("surface.reliefDepth=hit.depth"));assertTrue(s.contains("puddle*=smoothstep(.005,.07,surface.reliefDepth)"));
  assertTrue(s.contains("n=normalize(n+rippleWorld)"));assertFalse(s.contains("lerp(n,geometricUp,film"));
 }
 @Test void externalEndNoiseAddressLayoutIsBoundedAndNullGuarded() throws Exception {
  String shader=Files.readString(Path.of("shaders/world/end_environment.slang"));
  String loader=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/ItrpCloudNoise.java"));
  assertEquals(81936,(4100+128*128)*Integer.BYTES);
  assertTrue(shader.contains("4100+p.y*128+p.x"));
  assertTrue(loader.contains("dst.put(4096,0x454e4431)"));
  assertTrue(loader.contains("ctx.createBuffer(81936L"));
  assertFalse(shader.contains("noiseAddr!=0 && ConstPtr"));
  assertTrue(shader.contains("if(noiseAddr!=0)sampleValue=endSourceNoise"));
 }
}
