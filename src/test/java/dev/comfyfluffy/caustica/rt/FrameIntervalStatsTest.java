package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FrameIntervalStatsTest {
 @Test void p99DoesNotReplaceSlowestOnePercentMean() {
  long[] frames=new long[1000];java.util.Arrays.fill(frames,10_000_000);
  java.util.Arrays.fill(frames,990,1000,100_000_000);
  var result=FrameIntervalStats.summarize(frames,frames.length);
  assertEquals(10,result.p99Millis());assertEquals(10,result.onePercentLowFps());
  assertEquals(100_000_000,frames[999]);
 }
 @Test void longStallsRemainVisibleAndCallerStorageStaysUnchanged() {
  long[] frames=new long[100];java.util.Arrays.fill(frames,10_000_000);frames[0]=6_000_000_000L;
  var result=FrameIntervalStats.summarize(frames,frames.length);
  assertEquals(10,result.p99Millis());assertEquals(1.0/6,result.onePercentLowFps(),1e-10);
  assertEquals(6_000_000_000L,frames[0]);
 }
 @Test void emptyAndSmallSamplesAreDefined() {
  assertEquals(0,FrameIntervalStats.summarize(new long[0],0).onePercentLowFps());
  assertEquals(50,FrameIntervalStats.summarize(new long[]{20_000_000},1).onePercentLowFps());
 }
}
