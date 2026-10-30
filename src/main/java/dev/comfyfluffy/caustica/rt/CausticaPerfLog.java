package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import net.minecraft.client.Minecraft;

/**
 * Low-cost performance state recorder. Every line carries the {@code [CAUSTICA-PERF]} prefix so a single
 * test run can be grepped for the whole story.
 *
 * <p>Two rules keep this from becoming the thing it measures: nothing here blocks on the GPU, and nothing
 * is written per frame. State transitions log immediately and are deduplicated, so a resize storm produces
 * one line per distinct state rather than one per callback. Continuous values are sampled on a fixed
 * interval from existing counters that are already maintained for the HUD.
 *
 * <p>Real and displayed rates are kept apart on purpose. {@code realFps} is wall-clock real frames per
 * second. The generator's own per-present figure is reported verbatim and any displayed estimate derived
 * from it is labelled as derived, never presented as a measurement.
 */
public final class CausticaPerfLog {

    /** Sampling period for continuous values; well above any frame time but frequent enough to see drift. */
    private static final long SAMPLE_INTERVAL_NANOS = 2_000_000_000L;

    private static long windowStartNanos;
    private static int realFramesInWindow;
    private static long lastSampleNanos;
    private static String lastStateLine = "";
    private static int targetRebuildCount;
    private static boolean lastInWorld;
    private static boolean gpuTimingHintLogged;
    /** Fixed-size ring of real frame intervals; P99 and slowest-1%-mean FPS are reported independently. */
    private static final int FRAME_RING = 2048;
    private static final long[] frameIntervals = new long[FRAME_RING];
    private static int frameRingIndex;
    private static int frameRingCount;
    private static long lastFrameNanos;
    private static long lastPresentedFrames;

    private CausticaPerfLog() {
    }

    /** Emit a state line, skipping it when nothing actually changed. */
    private static void state(String message) {
        if (message.equals(lastStateLine)) {
            return;
        }
        lastStateLine = message;
        CausticaMod.LOGGER.info("[CAUSTICA-PERF] {}", message);
    }

    /** One completed real frame, used for the interval percentiles. Cheap: one clock read and a ring write. */
    public static void frameCompleted() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            long delta = now - lastFrameNanos;
            // Preserve real hitches; explicit world transitions reset the ring below.
            if (delta > 0L) {
                frameIntervals[frameRingIndex] = delta;
                frameRingIndex = (frameRingIndex + 1) % FRAME_RING;
                if (frameRingCount < FRAME_RING) frameRingCount++;
            }
        }
        lastFrameNanos = now;
    }

    /** Forget intervals across a world change so the menu/loading cadence does not pollute the sample. */
    public static void resetFrameIntervals() {
        frameRingIndex = 0;
        frameRingCount = 0;
        lastFrameNanos = 0L;
        lastPresentedFrames = 0L;
    }

    private static FrameIntervalStats.Result intervalStats() {
        return frameRingCount < 20 ? new FrameIntervalStats.Result(0,0)
                : FrameIntervalStats.summarize(frameIntervals,frameRingCount);
    }


    /** Called after the render targets were rebuilt for a new size or quality. */
    public static void targetsRebuilt(int displayW, int displayH, int renderW, int renderH,
                                      boolean rrEnabled, int rrQuality) {
        targetRebuildCount++;
        state("targets rebuilt #" + targetRebuildCount
                + " display=" + displayW + "x" + displayH
                + " render=" + renderW + "x" + renderH
                + " rr=" + (rrEnabled ? "on" : "off") + "/" + rrQuality
                + " swapchain=" + swapchainSummary()
                + " rrFeature=" + rrFeatureSummary());
    }

    /** Called when the Streamline swapchain was configured. */
    public static void swapchainConfigured(int width, int height, int format, int imageCount,
                                           boolean plugin, long generation) {
        state("swapchain configured gen=" + generation
                + " " + width + "x" + height + " fmt=" + format + " images=" + imageCount
                + " plugin=" + plugin + " " + swapchainSummary());
    }

    /** Called when the DLSS-G option tuple actually changes. */
    public static void fgOptions(String detail) {
        state("fg options " + detail);
    }

    /** Called when fusion of the frame generator changed availability. */
    public static void fgUnavailable(String reason) {
        state("fg unavailable: " + reason);
    }

    private static String swapchainSummary() {
        try {
            StreamlineSwapchainCoordinator c = StreamlineSwapchainCoordinator.INSTANCE;
            return "proxy[min=" + c.requestedNativeMinImageCount()
                    + ",visible=" + c.proxyVisibleImageCount()
                    + ",app=" + c.applicationImageCount() + "]";
        } catch (Throwable t) {
            return "proxy[n/a]";
        }
    }

    private static String rrFeatureSummary() {
        try {
            return RtDlssRr.INSTANCE.featureSizeSummary();
        } catch (Throwable t) {
            return "n/a";
        }
    }

    /**
     * Per-frame hook. Counts real frames for the wall-clock rate and samples the rest on an interval.
     * Calling this is a couple of field updates per frame; it never touches the GPU.
     */
    public static void tick() {
        // Console appenders can synchronously backpressure the render thread (confirmed in JFR).
        // Keep transition diagnostics, but continuous benchmark output must be explicitly requested.
        if (!Boolean.getBoolean("caustica.rt.perfLog")) return;
        long now = System.nanoTime();
        realFramesInWindow++;
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
            lastSampleNanos = now;
            return;
        }

        // World transitions explain most of the large step changes in cost, so treat them as events.
        boolean inWorld = Minecraft.getInstance().level != null;
        if (inWorld != lastInWorld) {
            lastInWorld = inWorld;
            CausticaMod.LOGGER.info("[CAUSTICA-PERF] world {}", inWorld ? "entered" : "left");
        }

        if (!gpuTimingHintLogged) {
            gpuTimingHintLogged = true;
            if (!EnhancedGpuTimings.enabled()) {
                CausticaMod.LOGGER.info("[CAUSTICA-PERF] per-pass GPU timings are off; "
                        + "add -Dcaustica.rt.gpuTimings=true (or caustica.rt.gpuTimings in the config) to record them");
            }
        }

        if (now - lastSampleNanos < SAMPLE_INTERVAL_NANOS) {
            return;
        }
        lastSampleNanos = now;

        double seconds = (now - windowStartNanos) / 1.0e9;
        int realFramesAtSample = realFramesInWindow;
        double realFps = seconds > 0.0 ? realFramesInWindow / seconds : 0.0;
        realFramesInWindow = 0;
        windowStartNanos = now;

        StringBuilder sb = new StringBuilder(420);
        sb.append("sample windowSec=").append(String.format("%.1f", seconds));
        sb.append(" realFrames=").append(realFramesAtSample);
        sb.append(" realFps=").append(String.format("%.1f", realFps));
        sb.append(" realFrameMs=").append(String.format("%.2f", realFps > 0.0 ? 1000.0 / realFps : 0.0));
        // P99 and reciprocal mean of the slowest 1% use the same interval window.
        var intervalStats = intervalStats();
        double p99Millis = intervalStats.p99Millis();
        sb.append(" frameMsP99=").append(p99Millis > 0.0 ? String.format("%.2f", p99Millis) : "n/a");
        sb.append(" onePercentLowFps=").append(p99Millis > 0.0 ? String.format("%.1f", intervalStats.onePercentLowFps()) : "n/a");
        sb.append(" intervals=").append(frameRingCount);

        boolean streamline = StreamlineRuntime.initialized();
        sb.append(" backend=").append(streamline ? "streamline" : "ngx");

        try {
            RtStreamlineFg fg = RtStreamlineFg.INSTANCE;
            // framesActuallyPresented is the runtime's per-submit figure from the most recent
            // slDLSSGGetState sample, not a cumulative counter. Never multiply it by an interval count.
            int perSubmit = fg.framesActuallyPresented();
            int generated = fg.generatedFramesLastPresent();
            sb.append(" fgRequestedGenerated=").append(fg.configuredMultiFrameCount());
            sb.append(" fgEffectiveGenerated=").append(fg.effectiveMultiFrameCount());
            sb.append(" fgSubmittedGenerated=").append(fg.nativeSubmittedMultiFrameCount());
            sb.append(" fgMaxSupported=").append(fg.multiFrameCountMax());
            sb.append(" fgMode=").append(fg.nativeSubmittedMode());
            sb.append(" fgStatus=").append(fg.runtimeStatus());
            sb.append(" fgActive=").append(fg.isActive());
            sb.append(" fgStateKnown=").append(fg.currentStateKnown());
            sb.append(" statePresentedPerSubmit=").append(perSubmit);
            sb.append(" stateGeneratedPerSubmit=").append(generated);
            // Explicitly a derivation: real render rate times frames the runtime reports per submit.
            // Measured on this build: slDLSSGGetState returns the frames presented per application frame,
            // not a running total, even when queried every 500 ms (it returns 2 at 2x on every sample).
            // The official formula is therefore realFps * numFramesActuallyPresented; it is labelled an
            // estimate because the host cannot see the pacer's actual scan-out.
            int perPresent = Math.max(1, perSubmit);
            sb.append(" displayedEstFps=").append(String.format("%.1f", realFps * perPresent));
            sb.append(" framesPerPresent=").append(perPresent);
            sb.append(" swapchainImages=").append(fg.swapchainImageCount());
            sb.append(" swapchain=").append(fg.swapchainWidth()).append('x').append(fg.swapchainHeight());
            sb.append(" fifoPresent=").append(fg.physicalFifoPresent());
            sb.append(" queueMode=").append(fg.effectiveQueueMode());
            sb.append(" inputSlots=").append(fg.inputSlotCount());
        } catch (Throwable t) {
            sb.append(" fg[n/a:").append(t.getClass().getSimpleName()).append(']');
        }

        // GPU/CPU timings: reports n/a where a value genuinely cannot be observed.
        sb.append(' ').append(EnhancedGpuTimings.summary());
        sb.append(" gpuTimingsValid=").append(EnhancedGpuTimings.hasSamples());

        sb.append(" streamline=").append(streamline);
        sb.append(" dh[").append(dev.comfyfluffy.caustica.client.DhLodCapture.stats()).append("]");
        sb.append(" rrFeature=").append(rrFeatureSummary());
        sb.append(" rebuilds=").append(targetRebuildCount);
        sb.append(" proxy=").append(swapchainSummary());

        CausticaMod.LOGGER.info("[CAUSTICA-PERF] {}", sb);
    }
}
