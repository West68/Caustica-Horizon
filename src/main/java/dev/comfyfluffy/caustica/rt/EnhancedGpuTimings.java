package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.util.Locale;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/**
 * GPU and CPU frame timings for the composite and present paths.
 *
 * <p>A single query pool holds {@link #POINTS} timestamps per slot and a ring of {@link #SLOTS} slots,
 * one slot per rendered frame. A slot is reused only after its previous results have been read, and the
 * read uses {@code VK_QUERY_RESULT_WITH_AVAILABILITY_BIT} <em>without</em> {@code VK_QUERY_RESULT_WAIT_BIT},
 * so neither the render thread nor the GPU is ever stalled by profiling. {@code vkDeviceWaitIdle} is never
 * called here; a slot whose results are not ready is dropped and counted instead.
 *
 * <p>What this can and cannot measure: timestamps cover commands recorded on this device's graphics queue.
 * Streamline's generated frames are evaluated and presented inside the driver, so their GPU cost is not
 * observable through this pool and is reported as {@code n/a} rather than zero. The present-path spans
 * cover the host's own hudless copy, UI composite, Y-flipped blit and layout transition.
 *
 * <p>Off by default. Timestamp writes force a queue-level serialisation point at each mark, so the cost is
 * small but not zero; enable with {@code -Dcaustica.rt.gpuTimings=true} or the config key
 * {@code caustica.rt.gpuTimings}.
 */
public final class EnhancedGpuTimings {

    // Composite marks, all recorded into one command buffer on the graphics queue.
    public static final int P_FRAME_START = 0;
    public static final int P_BEFORE_TRACE = 1;
    public static final int P_AFTER_TRACE = 2;
    public static final int P_AFTER_RR = 3;
    public static final int P_AFTER_EXPOSURE = 4;
    public static final int P_AFTER_DISPLAY = 5;
    public static final int P_AFTER_COPY = 6;
    // Present marks, recorded into the present command buffer on the same queue in the same frame.
    public static final int P_PRESENT_START = 7;
    public static final int P_AFTER_HUDLESS = 8;
    public static final int P_AFTER_UI = 9;
    public static final int P_AFTER_BLIT = 10;
    public static final int P_PRESENT_END = 11;
    public static final int POINTS = 12;

    // Spans are the difference between consecutive marks.
    public static final int S_SCENE = 0;          // frame start -> before trace: entity/TLAS preparation
    public static final int S_TRACE = 1;          // path tracing
    public static final int S_RR = 2;             // DLSS-RR, or the fallback upscale
    public static final int S_EXPOSURE = 3;
    public static final int S_DISPLAY = 4;
    public static final int S_COPY = 5;
    public static final int S_COMPOSITE = 6;      // frame start -> after copy
    public static final int S_HUDLESS = 7;        // present start -> after hudless capture
    public static final int S_UI = 8;             // -> after UI composite
    public static final int S_BLIT = 9;           // -> after Y-flipped blit
    public static final int S_PRESENT_TAIL = 10;  // -> present command buffer end
    public static final int SPANS = 11;

    private static final int SLOTS = 24;

    private static boolean initialised;
    private static boolean disabled;
    private static long pool;
    private static RtContext ctx;
    private static float periodNanos = 1.0f;
    private static int validBits = 64;
    private static long validMask = -1L;
    private static boolean sync2;

    private static int openSlot = -1;
    private static int nextSlot;
    private static final long[] slotFrame = new long[SLOTS];
    private static final boolean[] slotUsed = new boolean[SLOTS];

    private static final double[] spanMs = new double[SPANS];
    private static final boolean[] spanValid = new boolean[SPANS];
    private static final boolean[] readyScratch = new boolean[POINTS];
    private static final long[] timeScratch = new long[POINTS];
    private static final GpuTimingWindow benchmark = new GpuTimingWindow(SPANS);
    private static long samples;
    private static long dropped;
    private static long lastSampleNanos;
    private static String lastError = "";

    // CPU accumulators, reset whenever a sample is emitted.
    private static long cpuCompositeRecordNanos;
    private static long cpuCompositeSubmitNanos;
    private static long cpuAcquireNanos;
    private static long cpuPresentNanos;
    private static long cpuFrameNanos;
    private static long cpuFrameStartedNanos;
    private static long cpuFrameCount;

    // Short-lived internal watch (RR residency watchdog) independent of the user-facing diagnostic toggle.
    private static volatile int watchFrames;

    private EnhancedGpuTimings() {
    }

    /** Keep timestamps on for at least {@code frames} more frames. */
    public static void watch(int frames) {
        if (frames > watchFrames) watchFrames = frames;
    }

    /** One rendered frame elapsed for the internal watch. */
    public static void watchTick() {
        if (watchFrames > 0) watchFrames--;
    }

    /** Latest completed span in ms, or -1 if the last collected slot lacked it. */
    public static double latestSpanMs(int span) {
        return spanValid[span] ? spanMs[span] : -1.0;
    }

    public static long sampleCount() {
        return samples;
    }

    public static void beginBenchmarkWindow(long frame) { benchmark.begin(frame); }

    public static String endBenchmarkWindow() {
        benchmark.end();
        String[] names={"scene","pt","rrOrUpscale","exposure","display","copy","composite","hudless","ui","yflip","presentTail"};
        StringBuilder text=new StringBuilder("scope=mean of completed GPU timestamp samples for this measurement; unresolved tail excluded; composite includes its subspans; generated-frame GPU time unavailable\n");
        for(int i=0;i<SPANS;i++)text.append(names[i]).append("MeanMs=").append(benchmark.mean(i)).append(" samples=").append(benchmark.count(i)).append('\n');
        return text.toString();
    }

    /** True when the diagnostic was requested; every hook is a single field read when false. */
    public static boolean enabled() {
        if (disabled) {
            return false;
        }
        if (watchFrames > 0) {
            return true;
        }
        try {
            return CausticaConfig.Rt.Diagnostics.GPU_TIMINGS.value();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void ensureInitialised(RtContext context) {
        if (initialised || disabled) {
            return;
        }
        initialised = true;
        ctx = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(ctx.vk().getPhysicalDevice(), props);
            periodNanos = props.limits().timestampPeriod();

            int familyIndex = ctx.device().graphicsQueue().queueFamilyIndex();
            var count = stack.ints(0);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(ctx.vk().getPhysicalDevice(), count, null);
            int families = count.get(0);
            if (familyIndex < 0 || familyIndex >= families) {
                throw new IllegalStateException("graphics queue family " + familyIndex + " outside 0.." + families);
            }
            VkQueueFamilyProperties.Buffer all = VkQueueFamilyProperties.calloc(families, stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(ctx.vk().getPhysicalDevice(), count, all);
            int bits = all.get(familyIndex).timestampValidBits();
            if (bits <= 0) {
                throw new IllegalStateException("graphics queue family " + familyIndex + " has no timestamp support");
            }
            validBits = Math.min(bits, 64);
            validMask = validBits >= 64 ? -1L : (1L << validBits) - 1L;
            if (!(periodNanos > 0.0f)) {
                throw new IllegalStateException("timestampPeriod is " + periodNanos);
            }
            // Prefer sync2 when the device actually exposes it; vkCmdWriteTimestamp is the legal fallback.
            try {
                sync2 = ctx.vk().getCapabilities().vkCmdWriteTimestamp2KHR != 0L;
            } catch (Throwable unavailable) {
                sync2 = false;
            }
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(SLOTS * POINTS);
            var out = stack.mallocLong(1);
            RtContext.check(VK10.vkCreateQueryPool(ctx.vk(), info, null, out), "gpu timestamp query pool");
            pool = out.get(0);
        } catch (Throwable t) {
            disabled = true;
            lastError = t.toString();
            CausticaMod.LOGGER.warn("GPU timings unavailable: {}", lastError);
            return;
        }
        CausticaMod.LOGGER.info(
                "GPU timings enabled: {} slots x {} points, period={}ns, validBits={}, readback=non-blocking",
                SLOTS, POINTS, periodNanos, validBits);
    }

    /**
     * Record one timestamp. {@code point == P_FRAME_START} opens a new slot and retires the previous frame;
     * any other point is ignored unless it belongs to the frame that currently owns the open slot. Passing
     * the frame serial lets a present mark from a skipped frame be discarded instead of landing in the wrong
     * slot.
     */
    public static void mark(VkCommandBuffer cmd, int point, long frameSerial) {
        if (!enabled()) {
            return;
        }
        if (point < 0 || point >= POINTS) {
            return;
        }
        if (!initialised && !disabled) {
            RtContext context = RtContext.currentOrNull();
            if (context == null) {
                return;
            }
            ensureInitialised(context);
        }
        if (disabled || pool == 0L) {
            return;
        }
        try {
            if (point == P_FRAME_START) {
                retireOpenSlot();
                openSlot = nextSlot;
                nextSlot = (nextSlot + 1) % SLOTS;
                collect(openSlot);
                VK10.vkCmdResetQueryPool(cmd, pool, openSlot * POINTS, POINTS);
                slotFrame[openSlot] = frameSerial;
                slotUsed[openSlot] = true;
            } else if (openSlot < 0 || slotFrame[openSlot] != frameSerial) {
                return;
            }
            long query = (long) openSlot * POINTS + point;
            if (sync2) {
                KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd,
                        KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, pool, (int) query);
            } else {
                VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, pool, (int) query);
            }
        } catch (Throwable t) {
            disabled = true;
            lastError = t.toString();
            CausticaMod.LOGGER.warn("GPU timings disabled after a query failure: {}", lastError);
        }
    }

    /** Mark the frame's slot as complete; its results are read on a later frame. */
    private static void retireOpenSlot() {
        openSlot = -1;
    }

    private static void collect(int slot) {
        if (!slotUsed[slot]) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var data = stack.mallocLong(POINTS * 2);
            int rc = VK10.vkGetQueryPoolResults(ctx.vk(), pool, slot * POINTS, POINTS, data, 16,
                    VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (rc != VK10.VK_SUCCESS && rc != VK10.VK_NOT_READY) {
                dropped++;
                slotUsed[slot] = false;
                return;
            }
            boolean[] ready = readyScratch;
            long[] time = timeScratch;
            boolean any = false;
            for (int p = 0; p < POINTS; p++) {
                ready[p] = data.get(p * 2 + 1) != 0L;
                time[p] = data.get(p * 2);
                any |= ready[p];
            }
            if (!any) {
                dropped++;
                return;
            }
            applySpan(S_SCENE, time, ready, P_FRAME_START, P_BEFORE_TRACE);
            applySpan(S_TRACE, time, ready, P_BEFORE_TRACE, P_AFTER_TRACE);
            applySpan(S_RR, time, ready, P_AFTER_TRACE, P_AFTER_RR);
            applySpan(S_EXPOSURE, time, ready, P_AFTER_RR, P_AFTER_EXPOSURE);
            applySpan(S_DISPLAY, time, ready, P_AFTER_EXPOSURE, P_AFTER_DISPLAY);
            applySpan(S_COPY, time, ready, P_AFTER_DISPLAY, P_AFTER_COPY);
            applySpan(S_COMPOSITE, time, ready, P_FRAME_START, P_AFTER_COPY);
            applySpan(S_HUDLESS, time, ready, P_PRESENT_START, P_AFTER_HUDLESS);
            applySpan(S_UI, time, ready, P_AFTER_HUDLESS, P_AFTER_UI);
            applySpan(S_BLIT, time, ready, P_AFTER_UI, P_AFTER_BLIT);
            applySpan(S_PRESENT_TAIL, time, ready, P_AFTER_BLIT, P_PRESENT_END);
            for(int span=0;span<SPANS;span++)if(spanValid[span])benchmark.add(slotFrame[slot],span,spanMs[span]);
            samples++;
            lastSampleNanos = System.nanoTime();
            slotUsed[slot] = false;
        } catch (Throwable t) {
            dropped++;
            slotUsed[slot] = false;
        }
    }

    private static void applySpan(int span, long[] time, boolean[] ready, int from, int to) {
        if (!ready[from] || !ready[to]) {
            spanValid[span] = false;
            return;
        }
        spanMs[span] = toMillis(timestampDelta(time[from], time[to], validMask), periodNanos);
        spanValid[span] = true;
    }

    /**
     * Tick difference between two timestamps, wrap-safe for queues whose {@code timestampValidBits} is
     * narrower than 64. Exposed for tests: a 32-bit counter wraps roughly every four seconds at a 1 ns
     * period, so a naive subtraction would report a huge or negative span.
     */
    static long timestampDelta(long from, long to, long mask) {
        return (to - from) & mask;
    }

    static double toMillis(long ticks, float periodNanos) {
        return ticks * (double) periodNanos / 1.0e6;
    }

    // --- CPU-side timings -------------------------------------------------------------------------

    public static void cpuCompositeRecord(long nanos) {
        if (enabled()) {
            cpuCompositeRecordNanos += nanos;
        }
    }

    public static void cpuCompositeSubmit(long nanos) {
        if (enabled()) {
            cpuCompositeSubmitNanos += nanos;
        }
    }

    /** Host wait inside vkAcquireNextImageKHR. */
    public static void cpuAcquire(long nanos) {
        if (enabled()) {
            cpuAcquireNanos += nanos;
        }
    }

    /** Host time spent inside vkQueuePresentKHR. */
    public static void cpuPresent(long nanos) {
        if (enabled()) {
            cpuPresentNanos += nanos;
        }
    }

    public static void cpuFrameStart() {
        if (enabled()) {
            cpuFrameStartedNanos = System.nanoTime();
        }
    }

    public static void cpuFrameEnd() {
        if (enabled() && cpuFrameStartedNanos != 0L) {
            cpuFrameNanos += System.nanoTime() - cpuFrameStartedNanos;
            cpuFrameStartedNanos = 0L;
            cpuFrameCount++;
        }
    }

    // --- Reporting ---------------------------------------------------------------------------------

    /** One line of GPU and CPU figures for the perf log. Resets the CPU accumulators. */
    public static String summary() {
        if (!enabled()) {
            return "gpuTimings=off";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("gpu");
        if (samples == 0L) {
            sb.append("[no-sample");
            if (!lastError.isEmpty()) {
                sb.append(" error=").append(lastError);
            }
            sb.append(']');
        } else {
            sb.append("[samples=").append(samples);
            sb.append(" dropped=").append(dropped);
            sb.append(" bits=").append(validBits);
            appendSpan(sb, "scene", S_SCENE);
            appendSpan(sb, "pt", S_TRACE);
            appendSpan(sb, "rrOrUpscale", S_RR);
            appendSpan(sb, "exposure", S_EXPOSURE);
            appendSpan(sb, "display", S_DISPLAY);
            appendSpan(sb, "copy", S_COPY);
            appendSpan(sb, "composite", S_COMPOSITE);
            appendSpan(sb, "hudless", S_HUDLESS);
            appendSpan(sb, "ui", S_UI);
            appendSpan(sb, "yflip", S_BLIT);
            appendSpan(sb, "presentTail", S_PRESENT_TAIL);
            // Streamline evaluates and presents generated frames inside the driver; no host query can see it.
            sb.append(" generatedFrameGpu=n/a");
            sb.append(']');
        }
        sb.append(" cpu[");
        long frames = Math.max(1L, cpuFrameCount);
        sb.append("frames=").append(cpuFrameCount);
        appendMicros(sb, "composite", cpuCompositeRecordNanos / frames);
        appendMicros(sb, "submit", cpuCompositeSubmitNanos / frames);
        appendMicros(sb, "acquire", cpuAcquireNanos / frames);
        appendMicros(sb, "present", cpuPresentNanos / frames);
        appendMicros(sb, "frame", cpuFrameNanos / frames);
        sb.append(']');
        cpuCompositeRecordNanos = 0L;
        cpuCompositeSubmitNanos = 0L;
        cpuAcquireNanos = 0L;
        cpuPresentNanos = 0L;
        cpuFrameNanos = 0L;
        cpuFrameCount = 0L;
        return sb.toString();
    }

    private static void appendSpan(StringBuilder sb, String name, int span) {
        sb.append(' ').append(name).append('=');
        if (spanValid[span]) {
            sb.append(String.format(Locale.ROOT, "%.3f", spanMs[span]));
        } else {
            sb.append("n/a");
        }
    }

    private static void appendMicros(StringBuilder sb, String name, long nanos) {
        sb.append(' ').append(name).append('=')
                .append(String.format(Locale.ROOT, "%.3f", nanos / 1000.0));
    }

    public static boolean hasSamples() {
        return samples > 0L;
    }

    /** Release the pool at device teardown. Safe to call without an active device. */
    public static void shutdown() {
        if (pool != 0L && ctx != null) {
            try {
                VK10.vkDestroyQueryPool(ctx.vk(), pool, null);
            } catch (Throwable ignored) {
                // Device already gone; nothing to free.
            }
        }
        pool = 0L;
        cpuFrameStartedNanos = cpuFrameNanos = cpuFrameCount = 0L;
        initialised = false;
        disabled = false;
        openSlot = -1;
        nextSlot = 0;
        java.util.Arrays.fill(slotUsed, false);
        samples = 0L;
        dropped = 0L;
        lastError = "";
    }
}
