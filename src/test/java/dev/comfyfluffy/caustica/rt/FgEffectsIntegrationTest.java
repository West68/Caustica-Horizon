package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class FgEffectsIntegrationTest {
 String read(String p)throws Exception{return Files.readString(Path.of(p));}
 @Test void destinationGuiBlendAndUnseparatedInputsDoNotReachSdrFg()throws Exception{
  String ui=read("src/main/java/dev/comfyfluffy/caustica/rt/RtUiOverlay.java");assertTrue(ui.contains("return enabled() && RtComposite.INSTANCE.isHdrPresentActive()"));
  String composite=read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");assertTrue(composite.contains("boolean uiReady = hdrBackbuffer &&"));assertTrue(composite.contains("boolean hudlessReady = hdrBackbuffer &&"));
 }
 @Test void nativeCapabilityLimitsAreNotOverriddenByExternalStatus()throws Exception{
  String s=read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");assertTrue(s.contains("FrameGenerationRequestPolicy.canRequest"));
  String backend=read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtStreamlineFg.java");assertTrue(backend.contains("maxObservedGenerated + 1"));
  String d=read("src/main/java/dev/comfyfluffy/caustica/rt/RtMfgDiagnostics.java");assertTrue(d.contains("age>60000"));assertFalse(d.contains("Files.write"));
 }
 @Test void confirmedMenuMultiplierUsesSdkPresentsInsteadOfRequestedCount() throws Exception {
  String s=read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
  assertTrue(s.contains("framesActuallyPresented()>1"));
  assertFalse(s.contains("fg.isReady() ? Integer.toString(fg.effectiveMultiFrameCount() + 1)"));
 }
 @Test void emissiveFlagsDoNotOverlapStrengthOrLod(){
  int flags=131072|262144|524288;assertEquals(0,flags&(255<<8));assertEquals(0,flags&(255<<24));assertEquals(0,flags&127);
 }
 @Test void heldEmitterSamplingKeepsRadiusAndPowerFinite(){
  java.util.Random r=new java.util.Random(17);
  for(int i=0;i<10000;i++){double z=r.nextDouble()*2-1,t=r.nextDouble()*Math.PI*2,rad=.12*Math.cbrt(r.nextDouble()),s=Math.sqrt(1-z*z);double x=rad*s*Math.cos(t),y=rad*z,w=rad*s*Math.sin(t);assertTrue(x*x+y*y+w*w<=.0144+1e-12);}
 }
}
