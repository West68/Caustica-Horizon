package dev.comfyfluffy.caustica.rt;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResizeRetirementTest {
 @Test void oldGenerationsRetireBeforeLargerTargetsAreAllocated() throws Exception {
  String text=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
  String code=text.substring(text.indexOf("private void ensureOutput("),text.indexOf("private void bindDisplayTargets("));
  String[] order={"createCommandEncoder().submit()","beforeInputResourcesDestroyed()","ctx.waitIdle();",
   "releaseFeatureResourcesAfterIdle()","releaseStreamlineInputsAfterIdle()","RtDlssRr.INSTANCE.destroy()",
   "bloomPipeline.setInput(null, false)","destroyGuideImages()","output = ctx.createStorageImage"};
  int previous=code.indexOf("createCommandEncoder().submit()");
  for(String token:order) {int next=code.indexOf(token,previous);assertTrue(next>=previous,token);previous=next;}
 }
 @Test void failedRrReleaseRetainsOwnershipForRetry() throws Exception {
  String text=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtStreamlineRr.java"));
  String code=text.substring(text.indexOf("private void releaseResources("),text.indexOf("private void check("));
  assertTrue(code.indexOf("check(library.freeDlssdResources")<code.indexOf("resourcesCreated = false"));
  assertFalse(code.contains("LOGGER.warn"));
 }
 @Test void descriptorCachesCannotTreatRecycledImageHandlesAsTheOldGeneration() throws Exception {
  String display=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDisplayPipeline.java"));
  String bind=display.substring(display.indexOf("public void setImages("),display.indexOf("public void dispatch("));
  assertFalse(bind.contains("return;")); // Target boundary always rewrites the descriptor, equal handles included.
  String composite=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
  String resize=composite.substring(composite.indexOf("private void ensureOutput("),composite.indexOf("private void bindDisplayTargets("));
  assertTrue(resize.indexOf("exposure.invalidateInput()")<resize.indexOf("destroyGuideImages()"));
  assertTrue(resize.indexOf("hdrCompositePipeline.invalidateImages()")<resize.indexOf("hdrDisplayImage.destroy()"));
  String exposure=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtExposurePipeline.java"));
  assertTrue(exposure.contains("void invalidateInput() { boundColorView=0L; }"));
 }

}
