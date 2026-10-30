package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GpuTimingWindowTest {
 @Test void completedSamplesUseFrameFloorAndDoNotIncludePriorWindow() {
  var w=new GpuTimingWindow(2);w.begin(20);w.add(19,0,90);w.add(20,0,2);w.add(21,0,4);
  assertEquals(2,w.count(0));assertEquals(3,w.mean(0));assertTrue(Double.isNaN(w.mean(1)));
  w.end();w.add(22,0,100);assertEquals(2,w.count(0));
  w.begin(30);assertEquals(0,w.count(0));w.add(30,0,Double.NaN);w.add(30,0,-1);assertEquals(0,w.count(0));
 }
}
