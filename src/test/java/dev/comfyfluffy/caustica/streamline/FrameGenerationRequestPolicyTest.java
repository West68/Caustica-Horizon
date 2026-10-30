package dev.comfyfluffy.caustica.streamline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FrameGenerationRequestPolicyTest {
 @Test void coldVerifiedMfgCanRetainRequestButNeverClaimsSdkCapability() {
  assertTrue(FrameGenerationRequestPolicy.canRequest(2,true,false,0,true));
  assertTrue(FrameGenerationRequestPolicy.canRequest(3,true,false,1,true));
 }
 @Test void fiveAndSixAreRequestableOnTheExternalRoute() {
  assertTrue(FrameGenerationRequestPolicy.canRequest(4,true,false,5,true));
  assertTrue(FrameGenerationRequestPolicy.canRequest(5,true,false,5,true));
 }
 @Test void nativeRequestsRespectReportedMaximumAndFailures() {
  assertFalse(FrameGenerationRequestPolicy.canRequest(2,true,false,1,false));
  assertTrue(FrameGenerationRequestPolicy.canRequest(1,true,false,0,false));
  assertFalse(FrameGenerationRequestPolicy.canRequest(3,false,false,5,true));
  assertFalse(FrameGenerationRequestPolicy.canRequest(3,true,true,5,true));
 }
}
