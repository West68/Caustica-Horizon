package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr;

/**
 * After DLSS-RR is (re)created for a new output size, briefly samples GPU timestamps. When VRAM is full at
 * creation time (e.g. growing a window in-world), the driver can place RR's internal resources in system
 * memory, which makes RR alone cost several times the path tracer for as long as that feature lives.
 * If RR is clearly in that state, its resources are released once and recreated (a single idle hitch),
 * up to {@link #MAX_RETRIES} times per configuration. Output, settings and image are unchanged.
 * Disable with {@code -Dcaustica.rr.residencyWatchdog=false}.
 */
public final class RrResidencyWatchdog {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("caustica.rr.residencyWatchdog", "true"));
    private static final int WATCH_FRAMES = 240;
    private static final int WARMUP_FRAMES = 60;
    private static final int NEEDED_SAMPLES = 30;
    private static final int MAX_RETRIES = 3;
    private static final double MIN_SLOW_MS = 10.0;
    private static final double SLOW_FRACTION_OF_TRACE = 0.6;

    private static int framesLeft;
    private static int samples;
    private static int retries;
    private static long lastSample = -1;
    private static final double[] rr = new double[NEEDED_SAMPLES];
    private static final double[] trace = new double[NEEDED_SAMPLES];

    private RrResidencyWatchdog() {
    }

    /** A new size/quality/preset was configured: start a fresh watch with a fresh retry budget. */
    public static void onConfigured(boolean retry) {
        if (!ENABLED) return;
        if (!retry) retries = 0;
        framesLeft = WATCH_FRAMES;
        samples = 0;
        lastSample = -1;
    }

    /** Called once per RT frame. */
    public static void tick() {
        EnhancedGpuTimings.watchTick();
        if (!ENABLED || framesLeft <= 0) return;
        framesLeft--;
        EnhancedGpuTimings.watch(2);
        if (framesLeft > WATCH_FRAMES - WARMUP_FRAMES) return;
        long sample = EnhancedGpuTimings.sampleCount();
        if (sample == lastSample) return;
        lastSample = sample;
        double r = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_RR);
        double t = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_TRACE);
        if (r <= 0.0 || t <= 0.0) return;
        rr[samples] = r;
        trace[samples] = t;
        if (++samples < NEEDED_SAMPLES) return;
        framesLeft = 0;
        double rrMedian = median(rr), traceMedian = median(trace);
        if (rrMedian > Math.max(MIN_SLOW_MS, SLOW_FRACTION_OF_TRACE * traceMedian) && retries < MAX_RETRIES) {
            retries++;
            CausticaMod.LOGGER.warn("DLSS-RR is {} ms vs path tracing {} ms after (re)configuration; its resources "
                    + "are likely outside VRAM. Recreating them (attempt {}/{})",
                    String.format("%.1f", rrMedian), String.format("%.1f", traceMedian), retries, MAX_RETRIES);
            RtStreamlineRr.INSTANCE.requestResourceRecreate();
        } else {
            CausticaMod.LOGGER.info("DLSS-RR residency check: rr={} ms, trace={} ms, retries={}",
                    String.format("%.1f", rrMedian), String.format("%.1f", traceMedian), retries);
        }
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }
}
