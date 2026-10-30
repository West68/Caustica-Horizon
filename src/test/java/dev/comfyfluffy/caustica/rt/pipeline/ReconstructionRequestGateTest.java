package dev.comfyfluffy.caustica.rt.pipeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReconstructionRequestGateTest {
 @Test void failureIsLatchedForUnchangedFramesButNewModelAndQualityMayRecover() {
  var gate=new ReconstructionRequestGate();
  assertTrue(gate.changed(1920,1080,0,0));
  for(int i=0;i<1000;i++)assertFalse(gate.changed(1920,1080,0,0));
  assertTrue(gate.changed(1920,1080,0,6));
  assertFalse(gate.changed(1920,1080,0,6));
  assertTrue(gate.changed(1920,1080,2,6));
  assertFalse(gate.changed(1920,1080,2,6));
  assertTrue(gate.changed(2560,1440,2,6));
  assertFalse(gate.changed(2560,1440,2,6));
  assertTrue(gate.changed(1920,1080,0,0));
 }
}
