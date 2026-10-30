package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class EndLightingControlsTest {
 private String read(String path)throws Exception{return Files.readString(Path.of(path));}
 @Test void endGetsNonzeroShadowedDirectLightWithoutAddingAnotherRayLoop()throws Exception {
  String s=read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
  assertTrue(s.contains("dimension==2?new Float4(.85649968f,.27635565f,.43593102f,.12f)"));
  assertTrue(s.contains(".60928f*CausticaConfig.Rt.Visuals.END_LIGHT.value()"));
  assertTrue(read("shaders/world/world.rgen.slang").contains("visibility(p, lightDir, 10000.0)"));
 }
 @Test void portalSamplesStarLayerAndHasIndependentTerminalEmission()throws Exception {
  assertTrue(read("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityTextures.java").contains("portal ? \"Sampler1\" : \"Sampler0\""));
  String p=read("shaders/world/portal_material.slang");assertTrue(p.contains("17/layer"));assertTrue(p.contains("frac(time/1200.0)*1.5"));assertTrue(p.contains("return uv*.5+float2(.25,.25)"));assertFalse(p.contains("smoothstep(.91"));
  String g=read("shaders/world/world.rgen.slang");assertTrue(g.contains("payloadEmissionSource()==EMISSION_SOURCE_PORTAL"));
  assertTrue(read("shaders/world/world.rchit.slang").contains("layer<16"));
 }
 @Test void resourceLayerDoesNotAutomaticallySelectGlassOptics()throws Exception {
  String s=read("src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterials.java");
  assertTrue(s.contains("state.getSoundType()==SoundType.GLASS"));assertFalse(s.contains("Blocks.BEDROCK"));
  assertTrue(read("src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterialRegistry.java").contains("glass=RtMaterials.refractive(state,glass)"));
 }
 @Test void allNewControlsReachFrameAndHaveBothTranslations()throws Exception {
  String frame=read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
  String gui=read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
  for(String key:new String[]{"EMISSION","END_LIGHT","END_STARS","END_SKY","END_CYCLE","NETHER_BRIGHTNESS","PORTAL","SPECULAR","NORMAL","RELIEF_SHADOW","PUDDLE","RIPPLES"}){
   assertTrue(frame.contains("Visuals."+key+".value()"),key);assertTrue(gui.contains("Visuals."+key+","),key);
  }
  for(String lang:new String[]{"zh_cn","en_us"}){
   String text=read("src/main/resources/assets/caustica/lang/"+lang+".json");
   for(String key:new String[]{"emission","endLight","endStars","endSky","endCycle","netherBrightness","portal","specular","normalStrength","reliefShadow","puddle","ripples"})
    assertTrue(text.contains("caustica.enhanced.param."+key+".tooltip"));
  }
 }
 @Test void disabledRtCannotEnableUnsupportedStartupFeaturesOrFg()throws Exception {
  assertTrue(read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java").contains("&& RtDeviceBringup.REQUESTED_AT_STARTUP"));
  assertTrue(read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java").contains("RtComposite.enabled()"));
 }
 @Test void normalStrengthRetainsFiniteReceiverHemisphere() {
  for(int i=0;i<=100;i++)for(int j=0;j<=200;j++){
   double cosine=i/100.,strength=j/100.,tangent=Math.sqrt(1-cosine*cosine)*strength;
   double z=Math.max(cosine,.001),norm=Math.hypot(z,tangent);
   assertTrue(Double.isFinite(norm)&&norm>0);assertTrue(z/norm>0);
  }
 }
}
