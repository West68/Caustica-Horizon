package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Timestamp maths for the GPU profiler. A queue may expose fewer than 64 valid bits, so every span must
 * survive a counter wrap; a naive subtraction would produce a negative or absurdly large duration.
 */
class GpuTimingSpanTest {

    @Test
    void fullWidthDeltaIsPlainSubtraction() {
        assertEquals(1_000L, EnhancedGpuTimings.timestampDelta(5_000L, 6_000L, -1L));
    }

    @Test
    void thirtyTwoBitCounterWrapsForward() {
        long mask = (1L << 32) - 1L;
        // 4_294_967_296 - 296 is 296 ticks before the wrap; 6_000 is 6_000 ticks after it.
        assertEquals(6_296L, EnhancedGpuTimings.timestampDelta(4_294_967_000L, 6_000L, mask));
    }

    @Test
    void fortyBitCounterWrapsForward() {
        long mask = (1L << 40) - 1L;
        long wrap = 1L << 40;
        assertEquals(250L, EnhancedGpuTimings.timestampDelta(wrap - 100L, 150L, mask));
    }

    @Test
    void deltaNeverExceedsMask() {
        long mask = (1L << 32) - 1L;
        long delta = EnhancedGpuTimings.timestampDelta(123L, 456L, mask);
        assertTrue(delta >= 0L && delta <= mask);
    }

    @Test
    void millisUseTimestampPeriod() {
        // 1_000 ticks at a 2.5 ns period is 2_500 ns.
        assertEquals(0.0025, EnhancedGpuTimings.toMillis(1_000L, 2.5f), 1.0e-9);
        // A 1 ns period is the common desktop case.
        assertEquals(1.0, EnhancedGpuTimings.toMillis(1_000_000L, 1.0f), 1.0e-9);
    }
    @Test
    void cpuFrameAverageAccumulatesDurationsAndDoesNotCountAnEndTwice() throws Exception {
        var setting = dev.comfyfluffy.caustica.CausticaConfig.Rt.Diagnostics.GPU_TIMINGS;
        boolean previous = setting.value();
        var total = EnhancedGpuTimings.class.getDeclaredField("cpuFrameNanos");
        var start = EnhancedGpuTimings.class.getDeclaredField("cpuFrameStartedNanos");
        var count = EnhancedGpuTimings.class.getDeclaredField("cpuFrameCount");
        total.setAccessible(true); start.setAccessible(true); count.setAccessible(true);
        long oldTotal=total.getLong(null), oldStart=start.getLong(null), oldCount=count.getLong(null);
        try {
            setting.set(true);
            total.setLong(null,20_000_000L); count.setLong(null,1);
            start.setLong(null,System.nanoTime()-20_000_000L);
            EnhancedGpuTimings.cpuFrameEnd();
            assertTrue(total.getLong(null)>=40_000_000L);
            assertEquals(2,count.getLong(null));
            assertEquals(0,start.getLong(null));
            EnhancedGpuTimings.cpuFrameEnd();
            assertEquals(2,count.getLong(null));
            EnhancedGpuTimings.summary();
            assertEquals(0,total.getLong(null));
            assertEquals(0,count.getLong(null));
        } finally {
            setting.set(previous); total.setLong(null,oldTotal);
            start.setLong(null,oldStart); count.setLong(null,oldCount);
        }
    }
}
