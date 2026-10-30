package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.streamline.StreamlineAbi;
import dev.comfyfluffy.caustica.streamline.StreamlineLibrary;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.vulkan.VK10;

/** Streamline-owned DLSS-G, Reflex, PCL, and per-present frame-token controller. */
public final class RtStreamlineFg {
    public static final RtStreamlineFg INSTANCE = new RtStreamlineFg();

    // These values are the Streamline 2.12 sl::PCLMarker ABI. Slot 6 is deprecated and must not be emitted.
    public static final int PCL_SIMULATION_START = 0;
    public static final int PCL_SIMULATION_END = 1;
    public static final int PCL_RENDER_SUBMIT_START = 2;
    public static final int PCL_RENDER_SUBMIT_END = 3;
    public static final int PCL_PRESENT_START = 4;
    public static final int PCL_PRESENT_END = 5;
    public static final int PCL_TRIGGER_FLASH = 7;

    private static final int VIEWPORT = 0;
    private static final int RESULT_OK = 0;
    private static final int RESULT_WARN_OUT_OF_VRAM = 39; // Streamline 2.14.1 sl_result.h
    private long nextOptionsBudgetRetry, nextStateBudgetRetry;
    private int lastOptionsResult, lastStateResult;

    private void invalidateGeneration() {
        generatedFramesConfirmed = false;
        lastGeneratedFrameCount = 0;
        framesActuallyPresented = 0;
        optionsEnabled = false;
        forceResetNextSubmission = true;
    }

    private void budgetWarning(boolean state, int result) {
        int previous = state ? lastStateResult : lastOptionsResult;
        if (state) {
            lastStateResult = result;
            nextStateBudgetRetry = System.nanoTime() + 1_000_000_000L;
            dlssgStateKnown = false;
        } else {
            lastOptionsResult = result;
            nextOptionsBudgetRetry = System.nanoTime() + 1_000_000_000L;
        }
        invalidateGeneration();
        unavailableReason = "DLSS-G " + (state ? "GetState" : "SetOptions")
                + " returned eWarnOutOfVRAM (0x27); valid output unconfirmed, retry pending";
        submissionStatus = unavailableReason;
        if (previous != result) CausticaMod.LOGGER.warn(unavailableReason);
    }

    private static final int RESOURCE_COUNT = 5;

    private final AtomicInteger frameIndex = new AtomicInteger();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f normalizedProjection = new Matrix4f();
    private final Matrix4f clipToPrev = new Matrix4f();
    private final Matrix4f prevToClip = new Matrix4f();
    private final Matrix4f matrixScratch = new Matrix4f();
    private final Matrix4f clipYReflection = new Matrix4f().scaling(1.0f, -1.0f, 1.0f);
    private final Matrix4f cameraToWorld = new Matrix4f();
    private final Vector3f cameraRight = new Vector3f();
    private final Vector3f cameraUp = new Vector3f();
    private final Vector3f cameraForward = new Vector3f();

    private boolean probed;
    private boolean dlssgSupported;
    private boolean reflexSupported;
    private boolean pclSupported;
    private boolean dlssgFailed;
    private boolean pluginForSwapchain;
    private boolean logicalVsyncRequested;
    private boolean physicalFifoPresent;
    private boolean frameInputsSubmitted;
    private boolean featureResourcesMayExist;
    private boolean optionsEnabled;
    private int lastAppliedOffFlags = Integer.MIN_VALUE;
    private boolean flashIndicatorDriverControlled;
    private boolean triggerFlashPending;
    private boolean dlssgStateKnown;
    private long frameToken;
    private boolean renderStartedForToken;
    private int currentFrameIndex = -1;
    private long swapchainGeneration;
    private long lastSubmittedSwapchainGeneration = -1L;
    private boolean forceResetNextSubmission = true;
    private int swapchainWidth;
    private int swapchainHeight;
    private int swapchainFormat;
    private int swapchainImageCount;
    /** Fingerprint of the option set currently live in the runtime; MIN_VALUE forces a re-apply. */
    private long lastAppliedOptionsFingerprint = Long.MIN_VALUE;
    /** slDLSSGGetState blocks the present thread; sample it periodically instead of every present. */
    /** slDLSSGGetState is synchronised with the present thread; sample on wall time so a 4x-6x present
     * rate does not multiply the number of blocking queries. */
    private static final long STATE_QUERY_INTERVAL_NANOS = 500_000_000L;
    private long nextStateQueryNanos;
    private long nextReflexQueryNanos;
    /** Sum of sampled SDK state fields for diagnostics; not an independently measured present counter. */
    private long presentedFramesAccumulated;
    private int consecutiveInactiveGenerationSamples;
    /** Highest number of generated frames the runtime has actually reported presenting. */
    private int maxObservedGenerated;
    private int lastRenderWidth;
    private int lastRenderHeight;
    private int lastHudlessFormat;
    private boolean lastUiAlphaValid;
    private int multiFrameCountMax;
    private int loggedClampedCount = Integer.MIN_VALUE;
    private int minWidthOrHeight;
    private int runtimeStatus;
    private boolean vsyncSupportAvailable;
    private int framesActuallyPresented;
    private int maxFramesActuallyPresented;
    private int lastSubmittedGeneratedFrameCount;
    private int lastFrameInputGeneratedCount = -1;
    private int lastSubmittedMode;
    private long successfulOptionsSubmissions;
    private boolean generatedFramesConfirmed;
    private int lastGeneratedFrameCount;
    private long loggedSubmissionGeneration = -1L;
    private int loggedMaxSupportFor = -1;
    private int loggedMaxSupportRequest = -1;
    private long loggedOptionsGeneration = -1L;
    private int submittedPresentsWithoutGeneration;
    private boolean lastSubmissionReset;
    private long inputsProcessingFence;
    private long inputsProcessingFenceValue;
    private final DlssgInputSlotRing inputSlots = new DlssgInputSlotRing();
    private boolean queueFallback;
    private String queueFallbackReason = "";
    private long timelineWaitCount;
    private long timelineWaitTotalNanos;
    private long timelineWaitMaximumNanos;
    private long timelineWaitFailures;
    private long estimatedVramUsage;
    private String featureVersion = "Unknown";
    private int lastReflexMode = Integer.MIN_VALUE;
    private int lastReflexLimit = Integer.MIN_VALUE;
    private String unavailableReason = "Not probed";
    private String submissionStatus = "No frame submitted";

    private RtStreamlineFg() {
    }

    /** Backward-compatible requested gate used by existing render seams. */
    public static boolean enabled() {
        return requested();
    }

    public static boolean requested() {
        return CausticaConfig.Rt.Fg.requested();
    }

    public boolean isAvailable() {
        return dlssgSupported && !dlssgFailed && pluginForSwapchain && !physicalFifoPresent;
    }

    public boolean frameInputsSubmitted() { return frameInputsSubmitted; }

    public boolean hasFailed(){return dlssgFailed;}
    public boolean isSupported() {
        return dlssgSupported && !dlssgFailed;
    }

    public boolean isActive() {
        return isAvailable() && optionsEnabled && dlssgStateKnown && runtimeStatus == 0
                && generatedFramesConfirmed && lastGeneratedFrameCount > 0;
    }

    public String unavailableReason() {
        if (physicalFifoPresent && requested()) {
            return "DLSS-G VSync compatibility requires MAILBOX on this Vulkan surface";
        }
        if (dlssgSupported && !pluginForSwapchain) {
            return requested()
                    ? "Waiting for the Streamline swapchain reconfiguration"
                    : "Supported; choose a generated-frame mode to enable";
        }
        return unavailableReason;
    }

    public int multiFrameCountMax() {
        return multiFrameCountMax;
    }

    public int effectiveMultiFrameCount() {
        // Explicit isolated SDK experiment only: request a fixed count without falsifying support/max.
        // Production GUI submits fixed requests and reports the runtime result without inventing support.
        int experiment=Integer.getInteger("caustica.rt.fg.experimentalGeneratedFrames",0);
        if (experiment>=2 && experiment<=5) return experiment;
        int selected = selectMultiFrameCount(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value(), multiFrameCountMax);
        return refusedGeneratedCap > 0 ? Math.min(selected, refusedGeneratedCap) : selected;
    }
    /**
     * Set once the runtime refuses a multiplier: official drivers (for example 4x on an RTX 40 without an
     * unlocker, or 5x/6x on an RTX 50) reject counts above their maximum, which used to switch frame
     * generation off for the whole session. Fall back to the reported maximum, then to 2x.
     */
    private int refusedGeneratedCap;

    public int configuredMultiFrameCount() {
        return Math.clamp(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value(), 1, 5);
    }

    public int nativeSubmittedMultiFrameCount() {
        return lastSubmittedGeneratedFrameCount;
    }

    public int nativeSubmittedMode() {
        return lastSubmittedMode;
    }

    public long successfulOptionsSubmissions() {
        return successfulOptionsSubmissions;
    }

    public boolean logicalVsyncRequested() {
        return logicalVsyncRequested;
    }

    public boolean physicalFifoPresent() {
        return physicalFifoPresent;
    }

    public int inputSlotCount() {
        return inputSlots.count();
    }

    /** Sum of sampled SDK state fields, reset at swapchain changes; not a hardware present counter. */
    public long presentedFramesAccumulated() {
        return presentedFramesAccumulated;
    }

    public int swapchainImageCount() {
        return swapchainImageCount;
    }

    public int swapchainWidth() {
        return swapchainWidth;
    }

    public int swapchainHeight() {
        return swapchainHeight;
    }

    public int activeInputSlot() {
        return inputSlots.active();
    }

    public int acquiredApplicationImage() {
        return inputSlots.acquiredApplicationImage();
    }

    public String requestedQueueMode() {
        return diagnosticQueueOverride();
    }

    public String effectiveQueueMode() {
        return useNoClientQueues() ? "no-client-queues" : "block-presenting-queue";
    }

    public String queuePolicyReason() {
        if (queueFallback) {
            return queueFallbackReason;
        }
        String override = diagnosticQueueOverride();
        if (!"auto".equals(override)) {
            return "Forced diagnostic mode";
        }
        if (logicalVsyncRequested) {
            return "MAILBOX VSync requires Streamline-owned presenting-queue pacing";
        }
        return "Streamline-owned presenting-queue synchronization (safe default)";
    }

    public boolean queueFallbackActive() {
        return queueFallback;
    }

    public String queueFallbackReason() {
        return queueFallbackReason;
    }

    public long timelineWaitCount() {
        return timelineWaitCount;
    }

    public long timelineWaitTotalNanos() {
        return timelineWaitTotalNanos;
    }

    public long timelineWaitMaximumNanos() {
        return timelineWaitMaximumNanos;
    }

    public long timelineWaitFailures() {
        return timelineWaitFailures;
    }

    public long lastInputFence() {
        return inputsProcessingFence;
    }

    public long lastInputFenceValue() {
        return inputsProcessingFenceValue;
    }

    public String inputSlotRetirements() {
        return inputSlots.snapshot();
    }

    public boolean capabilityStateValid() {
        return dlssgSupported && multiFrameCountMax > 0;
    }

    public boolean currentStateKnown() {
        return dlssgStateKnown;
    }

    public int runtimeStatus() {
        return runtimeStatus;
    }

    /** Streamline's official DLSS-G VSync/RSync capability, distinct from physical Vulkan MAILBOX. */
    public boolean vsyncSupportAvailable() {
        return vsyncSupportAvailable;
    }

    public int framesActuallyPresented() {
        return framesActuallyPresented;
    }

    public int maxFramesActuallyPresented() {
        return maxFramesActuallyPresented;
    }

    public int generatedFramesLastPresent() {
        return lastGeneratedFrameCount;
    }

    public boolean hasGeneratedFrames() {
        return generatedFramesConfirmed;
    }

    public String submissionStatus() {
        return submissionStatus;
    }

    public int outputWidth() {
        return swapchainWidth;
    }

    public int outputHeight() {
        return swapchainHeight;
    }

    public long estimatedVramUsage() {
        return estimatedVramUsage;
    }

    public String featureVersion() {
        return featureVersion;
    }

    public boolean dynamicMfgSupported() {
        return stateDynamicMfgSupported;
    }

    public static String dynamicMfgLimitation() {
        return "Dynamic Multi Frame Generation requires D3D12; Caustica uses fixed 2x-6x on Vulkan";
    }

    public boolean reflexAvailable() {
        return reflexSupported;
    }

    public boolean flashIndicatorDriverControlled() {
        return flashIndicatorDriverControlled;
    }

    public int reflexIntervalUs() {
        return Math.max(0, lastReflexLimit);
    }

    private boolean stateDynamicMfgSupported;

    /** Earliest per-loop point: obtain the token, apply pacing, and begin PCL simulation markers. */
    public void beginSimulationFrame() {
        renderStartedForToken = false;
        frameInputsSubmitted = false;
        frameToken = 0L;
        currentFrameIndex = -1;
        if (!StreamlineRuntime.initializeForVulkan()) {
            return;
        }
        probeAvailabilityOnce();
        StreamlineLibrary library = StreamlineRuntime.library();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tokenOut = arena.allocate(ValueLayout.JAVA_LONG);
            int requestedFrameIndex = frameIndex.getAndIncrement();
            frameToken = library.beginFrame(requestedFrameIndex, tokenOut);
            if (frameToken == 0L) {
                CausticaMod.LOGGER.warn("Streamline did not return a frame token: {}", library.lastError());
                return;
            }
            currentFrameIndex = requestedFrameIndex;
            applyReflexOptions(library, arena);
            if (reflexSupported) {
                warnResult(library.reflexSleep(frameToken), "slReflexSleep");
            }
            if (triggerFlashPending) {
                marker(PCL_TRIGGER_FLASH);
                triggerFlashPending = false;
            }
            marker(PCL_SIMULATION_START);
        } catch (Throwable throwable) {
            CausticaMod.LOGGER.warn("Streamline frame begin failed", throwable);
            frameToken = 0L;
        }
    }

    /** Compatibility alias while call sites migrate. */
    public void beginFrame() {
        beginSimulationFrame();
    }

    /** Ensure synchronous redraws that bypass normal simulation still receive their own present token. */
    public void ensurePresentToken() {
        if (frameToken == 0L) {
            beginSimulationFrame();
            marker(PCL_SIMULATION_END);
            marker(PCL_RENDER_SUBMIT_START);
        }
    }

    /** Each redraw owns a token, including screenshot redraws without a simulation tick or present. */
    public void beginRenderFrame() {
        if (frameToken != 0L && renderStartedForToken) {
            // Streamline rejects different common constants for the same token/viewport. A synchronous
            // redraw has its own jitter/history state, so it must not reuse the previous render's token.
            beginSimulationFrame();
            marker(PCL_SIMULATION_END);
        }
        ensurePresentToken();
        renderStartedForToken = true;
    }

    /** The one Streamline frame token shared by render-time RR and present-time Frame Generation. */
    long frameTokenForFeature() {
        ensurePresentToken();
        return frameToken;
    }

    public void marker(int marker) {
        if (frameToken == 0L || !pclSupported || !StreamlineRuntime.initialized()) {
            return;
        }
        warnResult(StreamlineRuntime.library().pclSetMarker(marker, frameToken), "slPCLSetMarker(" + marker + ")");
    }

    /** Preserve clicks received during GLFW event polling until this loop's Streamline token exists. */
    public void triggerFlash() {
        if (!flashIndicatorDriverControlled) {
            return;
        }
        if (frameToken != 0L) {
            marker(PCL_TRIGGER_FLASH);
        } else {
            triggerFlashPending = true;
        }
    }

    /** Probe selected-device support for all three required Streamline features. */
    public void probeAvailabilityOnce() {
        if (probed || !StreamlineRuntime.initialized()) {
            return;
        }
        VulkanDevice device;
        try {
            if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend()
                    instanceof VulkanDevice vulkanDevice)) {
                return;
            }
            device = vulkanDevice;
        } catch (Throwable unavailableDevice) {
            return;
        }
        probed = true;
        long physicalDevice = device.vkDevice().getPhysicalDevice().address();
        int dlssgResult = StreamlineRuntime.supportsFeature(StreamlineRuntime.FEATURE_DLSS_G, physicalDevice);
        String dlssgReason = dlssgResult == RESULT_OK ? "" : StreamlineRuntime.lastError();
        dlssgSupported = dlssgResult == RESULT_OK;
        reflexSupported = StreamlineRuntime.supportsFeature(StreamlineRuntime.FEATURE_REFLEX, physicalDevice) == RESULT_OK;
        pclSupported = StreamlineRuntime.supportsFeature(StreamlineRuntime.FEATURE_PCL, physicalDevice) == RESULT_OK;
        unavailableReason = dlssgSupported ? "" : dlssgReason;
        if (dlssgSupported) {
            queryFeatureMetadata();
        }
        CausticaMod.LOGGER.info("Streamline support: DLSS-G={}, Reflex={}, PCL={}{}", dlssgSupported,
                reflexSupported, pclSupported, dlssgSupported ? "" : " (" + unavailableReason + ")");
        refreshReflexState();
    }

    public void onSwapchainConfigured(int width, int height, int format, int imageCount,
            boolean userVsyncRequested, boolean fifoPresent, boolean pluginEnabled, long generation) {
        lastAppliedOptionsFingerprint = Long.MIN_VALUE;
        nextStateQueryNanos = 0L;
        loggedClampedCount = Integer.MIN_VALUE;
        maxObservedGenerated = 0;
        presentedFramesAccumulated = 0L;
        swapchainWidth = width;
        swapchainHeight = height;
        swapchainFormat = format;
        swapchainImageCount = imageCount;
        logicalVsyncRequested = userVsyncRequested;
        physicalFifoPresent = fifoPresent;
        pluginForSwapchain = pluginEnabled;
        swapchainGeneration = generation;
        dev.comfyfluffy.caustica.rt.CausticaPerfLog.swapchainConfigured(width, height, format, imageCount,
                pluginEnabled, generation);
        resetInputSlots(Math.max(0, imageCount));
        queueFallback = "safe".equals(diagnosticQueueOverride());
        queueFallbackReason = queueFallback ? "Forced blocking queue diagnostic" : "";
        forceResetNextSubmission = true;
        lastFrameInputGeneratedCount = -1;
        dlssgStateKnown = false;
        vsyncSupportAvailable = false;
        optionsEnabled = false;
        lastAppliedOffFlags = Integer.MIN_VALUE;
        generatedFramesConfirmed = false;
        lastGeneratedFrameCount = 0;
        loggedSubmissionGeneration = -1L;
        framesActuallyPresented = 0;
        lastSubmittedMode = 0;
        lastSubmittedGeneratedFrameCount = 0;
        loggedOptionsGeneration = -1L;
        submittedPresentsWithoutGeneration = 0;
        submissionStatus = "Waiting for normalized frame inputs";
        frameInputsSubmitted = false;
        if (pluginEnabled) {
            dlssgFailed = false;
            probed = false;
            probeAvailabilityOnce();
        }
        // slDLSSGGetState is present-thread synchronized. Defer the first query until after this
        // swapchain's first real Present instead of querying while no PRESENT marker exists yet.
    }

    public void onSwapchainConfigurationFailed() {
        lastAppliedOptionsFingerprint = Long.MIN_VALUE;
        optionsEnabled = false;
        lastAppliedOffFlags = Integer.MIN_VALUE;
        generatedFramesConfirmed = false;
        lastGeneratedFrameCount = 0;
        pluginForSwapchain = false;
        forceResetNextSubmission = true;
        unavailableReason = "Swapchain configuration failed";
        submissionStatus = unavailableReason;
    }

    /** Only in the swapchain teardown transaction, after MC submit, input retirement and device idle.
     * eOff with retained menu resources and disabling plugin callbacks are not slFreeResources. */
    public void releaseFeatureResourcesAfterIdle() {
        if (!StreamlineRuntime.initialized() || !pluginForSwapchain || !featureResourcesMayExist) return;
        int result = StreamlineRuntime.library().freeDlssgResources(VIEWPORT);
        if (result != RESULT_OK) {
            throw new IllegalStateException("DLSS-G resource retirement failed: " + StreamlineRuntime.lastError());
        }
        featureResourcesMayExist = false;
        invalidateGeneration();
        lastAppliedOptionsFingerprint = Long.MIN_VALUE;
        lastAppliedOffFlags = Integer.MIN_VALUE;
        dlssgStateKnown = false;
        nextStateQueryNanos = 0L;
        CausticaMod.LOGGER.info("[DLSS-G] slFreeResources completed after device idle; internal FG allocations retired");
    }

    /** Must run before Minecraft begins any swapchain mutation. */
    public void suspendForWindowManipulation() {
        frameInputsSubmitted=false;
        lastAppliedOptionsFingerprint=Long.MIN_VALUE;
        setOff(true); // Keep resources alive until the ordinary quiesced swapchain teardown.
        invalidateGeneration();
    }

    public void suspendForSwapchainChange() {
        lastAppliedOptionsFingerprint = Long.MIN_VALUE;
        drainInputSlots("swapchain change");
        setOff(false);
        frameInputsSubmitted = false;
        frameToken = 0L;
    }

    /** Must run before Caustica writes or destroys any previously tagged frame input. */
    public boolean beforeFrameInputs() {
        if (inputSlots.prepared() || !useNoClientQueues()) {
            inputSlots.markPrepared();
            return true;
        }
        if (!inputSlots.hasActive()) {
            return false;
        }
        int activeInputSlot = inputSlots.active();
        if (!inputSlots.pending(activeInputSlot)) {
            inputSlots.markPrepared();
            return true;
        }
        if (!inputSlots.valid(activeInputSlot)) {
            return enterBlockingQueueFallback("Streamline returned no completion fence/value for input slot "
                    + activeInputSlot);
        }
        int result = waitForInputSlot(activeInputSlot);
        if (result != RESULT_OK) {
            timelineWaitFailures++;
            return enterBlockingQueueFallback("Timeline wait failed for input slot " + activeInputSlot + ": "
                    + StreamlineRuntime.lastError());
        }
        inputSlots.markPrepared();
        return true;
    }

    /** Drain every outstanding tagged-input retirement before their owning image ring is destroyed. */
    public void beforeInputResourcesDestroyed() {
        drainInputSlots("input resource destruction");
    }

    /** Associate the next frame's tagged resources with the application-visible acquired image. */
    public void onImageAcquired(int imageIndex) {
        inputSlots.acquire(imageIndex);
    }

    /** Called immediately before the one real proxy present. */
    public void beforePresent() {
        if(dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator.INSTANCE.windowTransitionPending())
            suspendForWindowManipulation();
        ensurePresentToken();
        marker(PCL_RENDER_SUBMIT_END);
        if (!frameInputsSubmitted) {
            clearFrameTags();
            forceResetNextSubmission = true;
            submittedPresentsWithoutGeneration = 0;
        }
        // Options take effect on the next Present. Publish them before PRESENT_START so the PCL boundary,
        // Streamline's present hook, and the proxy present all observe the same interpolation mode.
        applyOptionsForPresent();
        marker(PCL_PRESENT_START);
    }

    /** Called immediately after the one real proxy present. */
    public void afterPresent() {
        marker(PCL_PRESENT_END);
        if (pluginForSwapchain && dlssgSupported) {
            // This query is synchronized against the present thread, so issuing it on every present serialises
            // the CPU with the generator each frame for a value nothing consumes more often than the HUD.
            // Sample it a few times a second instead; a settings or swapchain change resets the countdown.
            long now = System.nanoTime();
            if (now >= nextStateQueryNanos) {
                nextStateQueryNanos = now + STATE_QUERY_INTERVAL_NANOS;
                refreshState();
            }
        }
        // This only reads the driver-controlled flash indicator; it is not part of the present contract.
        // It used to be an FFI call on every present, which at 4x-6x is 4-6x more often for a value that
        // changes at most once per settings change.
        if (reflexSupported && System.nanoTime() >= nextReflexQueryNanos) {
            nextReflexQueryNanos = System.nanoTime() + 500_000_000L;
            refreshReflexState();
        }
        int apiError = StreamlineAbi.pollApiError(StreamlineRuntime.library());
        if (apiError == -1000001004 || apiError == 1000001003) {
            // Surface resize events are recovered by the swapchain coordinator.
            invalidateGeneration();
            dlssgStateKnown = false;
            submissionStatus = "Waiting for swapchain recreation after resize";
        } else if (apiError != 0) {
            invalidateGeneration();
            dlssgFailed = true;
            optionsEnabled = false;
            unavailableReason = "Streamline present API error (VkResult " + apiError + ")";
            CausticaMod.LOGGER.error(unavailableReason);
        }
        frameToken = 0L;
        currentFrameIndex = -1;
        frameInputsSubmitted = false;
    }

    /** Attach one valid real frame's constants and tags before the intercepted present. */
    public boolean submitFrame(long commandBuffer, int colorWidth, int colorHeight, int colorFormat,
            int renderWidth, int renderHeight, RtImage depth, RtImage motion, RtImage hudless,
            int hudlessFormat, RtImage uiAlpha,
            Matrix4fc projection, Matrix4fc currentViewProjection, Matrix4fc previousViewProjection,
            Matrix4fc viewRotation, float jitterX, float jitterY,
            double cameraX, double cameraY, double cameraZ,
            float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ, boolean reset) {
        ensurePresentToken();
        // A present token describes exactly one application frame. Some surface paths can reach the
        // submission seam more than once; never overwrite that token's constants or tags.
        if (frameInputsSubmitted) {
            return true;
        }
        if (!canSubmit(colorWidth, colorHeight, commandBuffer, depth, motion, hudless)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            StreamlineLibrary library = StreamlineRuntime.library();
            MemorySegment constants = StreamlineAbi.allocate(arena, StreamlineAbi.CONSTANTS_SIZE);
            int generatedFrameCount = submittedGeneratedCount();
            boolean effectiveReset = reset || forceResetNextSubmission
                    || generatedFrameCount != lastFrameInputGeneratedCount
                    || lastSubmittedSwapchainGeneration != swapchainGeneration;
            writeConstants(constants, projection, currentViewProjection, previousViewProjection, viewRotation,
                    jitterX, jitterY, renderWidth, renderHeight, cameraX, cameraY, cameraZ,
                    cameraDeltaX, cameraDeltaY, cameraDeltaZ, effectiveReset, true);
            check(library.setConstants(frameToken, VIEWPORT, constants), "slSetConstants");

            MemorySegment resources = StreamlineAbi.allocate(arena,
                    StreamlineAbi.RESOURCE_DESC_SIZE * RESOURCE_COUNT);
            writeResource(resources, 0, depth, VK10.VK_FORMAT_R32_SFLOAT, renderWidth, renderHeight, 0, true);
            writeResource(resources, 1, motion, VK10.VK_FORMAT_R16G16_SFLOAT, renderWidth, renderHeight, 1, true);
            int contentWidth = hudless.width;
            int contentHeight = hudless.height;
            writeResource(resources, 2, hudless, hudlessFormat, contentWidth, contentHeight, 2, true);
            boolean uiValid = uiAlpha != null && uiAlpha.width == contentWidth && uiAlpha.height == contentHeight;
            writeResource(resources, 3, uiAlpha, VK10.VK_FORMAT_R32_SFLOAT,
                    contentWidth, contentHeight, 69, uiValid);
            // Exclusive fullscreen can expose a physical swapchain one row taller than Minecraft's visible
            // render target (for example 3840x2161 versus 3840x2160). Streamline explicitly supports an
            // extent-only backbuffer tag for this case: FG processes the visible subrect and copies the
            // non-content row unchanged. The backbuffer resource pointer is intentionally null because the
            // proxy swapchain already owns and identifies the presented image.
            writeResource(resources, 4, 0L, 0L, 0L, 0,
                    contentWidth, contentHeight, 53, 0, false);
            StreamlineAbi.bytes(resources).putInt(
                    StreamlineAbi.RESOURCE_DESC_SIZE * 4 + 60, 0); // Default lifecycle for extent-only tag.
            check(library.tagResources(frameToken, VIEWPORT, resources, RESOURCE_COUNT, commandBuffer),
                    "slSetTagForFrame");

            lastRenderWidth = renderWidth;
            lastRenderHeight = renderHeight;
            lastHudlessFormat = hudlessFormat;
            lastUiAlphaValid = uiValid;
            lastSubmittedSwapchainGeneration = swapchainGeneration;
            lastFrameInputGeneratedCount = generatedFrameCount;
            forceResetNextSubmission = false;
            lastAppliedOffFlags = Integer.MIN_VALUE;
            submissionStatus = generatedFramesConfirmed
                    ? "Runtime confirmed generated presentation" : "Submitted; waiting for generated present";
            frameInputsSubmitted = true;
            featureResourcesMayExist = true;
            lastSubmissionReset = effectiveReset;
            if (loggedSubmissionGeneration != swapchainGeneration) {
                loggedSubmissionGeneration = swapchainGeneration;
                CausticaMod.LOGGER.info(
                        "DLSS-G staged first world frame for swapchain {}: mode={}, generated={}, render={}x{}, output={}x{}, ui={}, queue={}, reset={}",
                        swapchainGeneration, CausticaConfig.Rt.Fg.mode(), effectiveMultiFrameCount(),
                        renderWidth, renderHeight, colorWidth, colorHeight, uiValid,
                        effectiveQueueMode(), effectiveReset);
            }
            return true;
        } catch (Throwable throwable) {
            dlssgFailed = true;
            optionsEnabled = false;
            unavailableReason = "DLSS-G frame submission failed: " + throwable.getMessage();
            CausticaMod.LOGGER.error(unavailableReason, throwable);
            return false;
        }
    }

    private boolean canSubmit(int colorWidth, int colorHeight, long commandBuffer, RtImage depth,
            RtImage motion, RtImage hudless) {
        if(dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator.INSTANCE.windowTransitionPending()) {
            submissionStatus="Waiting for window transition to complete";
            return false;
        }
        if (!requested()) {
            submissionStatus = "Disabled by frame-generation mode";
            return false;
        }
        if (shouldSuspendForMenu()) {
            submissionStatus = "Suspended for an open Minecraft screen";
            return false;
        }
        if (!isAvailable()) {
            submissionStatus = unavailableReason();
            return false;
        }
        if (commandBuffer == 0L || frameToken == 0L) {
            submissionStatus = "Waiting for the Streamline frame token";
            return false;
        }
        if (!StreamlineSwapchainCoordinator.INSTANCE.configured()) {
            submissionStatus = "Waiting for the Streamline swapchain";
            return false;
        }
        if (colorWidth != swapchainWidth || colorHeight != swapchainHeight) {
            submissionStatus = "Present size " + colorWidth + "x" + colorHeight
                    + " does not match swapchain " + swapchainWidth + "x" + swapchainHeight;
            return false;
        }
        if (depth == null || motion == null || hudless == null) {
            submissionStatus = "Waiting for depth, motion, and hudless inputs";
            return false;
        }
        if (hudless.width <= 0 || hudless.height <= 0
                || hudless.width > colorWidth || hudless.height > colorHeight) {
            submissionStatus = "Hudless input " + hudless.width + "x" + hudless.height
                    + " is outside swapchain " + colorWidth + "x" + colorHeight;
            return false;
        }
        if (minWidthOrHeight > 0 && Math.min(colorWidth, colorHeight) < minWidthOrHeight) {
            unavailableReason = "Output resolution is below DLSS-G minimum " + minWidthOrHeight;
            submissionStatus = unavailableReason;
            return false;
        }
        if (runtimeStatus != 0) {
            submissionStatus = statusDescription(runtimeStatus);
        }
        return runtimeStatus == 0;
    }

    private void applyReflexOptions(StreamlineLibrary library, Arena arena) {
        int mode = requested() || CausticaConfig.Rt.Reflex.ENABLED.value()
                ? (CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value() ? 2 : 1) : 0;
        // Streamline owns generated-frame presentation cadence. Any application-side Reflex interval
        // limits rendered frames instead of final displayed frames, so DLSS-G must always remain unlimited.
        int limit = requested() ? 0 : Math.max(0, CausticaConfig.Rt.Reflex.MINIMUM_INTERVAL_US.value());
        if (mode == lastReflexMode && limit == lastReflexLimit) {
            return;
        }
        MemorySegment options = StreamlineAbi.allocate(arena, StreamlineAbi.REFLEX_OPTIONS_SIZE);
        ByteBuffer bytes = StreamlineAbi.bytes(options);
        bytes.putInt(0, mode);
        bytes.putInt(4, limit);
        if (reflexSupported) {
            warnResult(library.setReflexOptions(options), "slReflexSetOptions");
        }
        CausticaMod.LOGGER.info("Reflex options applied: mode={}, interval={}us, dlssg={}",
                mode, limit, requested());
        lastReflexMode = mode;
        lastReflexLimit = limit;
    }

    private void clearFrameTags() {
        if (frameToken == 0L || !StreamlineRuntime.initialized() || !pluginForSwapchain) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment resources = StreamlineAbi.allocate(arena,
                    StreamlineAbi.RESOURCE_DESC_SIZE * RESOURCE_COUNT);
            ByteBuffer bytes = StreamlineAbi.bytes(resources);
            bytes.putInt(56, 0);
            bytes.putInt(56 + StreamlineAbi.RESOURCE_DESC_SIZE, 1);
            bytes.putInt(56 + StreamlineAbi.RESOURCE_DESC_SIZE * 2, 2);
            bytes.putInt(56 + StreamlineAbi.RESOURCE_DESC_SIZE * 3, 69);
            bytes.putInt(56 + StreamlineAbi.RESOURCE_DESC_SIZE * 4, 53);
            warnResult(StreamlineRuntime.library().tagResources(frameToken, VIEWPORT, resources, RESOURCE_COUNT, 0L),
                    "slSetTagForFrame(null)");
        }
    }

    private void setOff(boolean retainResources) {
        forceResetNextSubmission = true;
        if (!StreamlineRuntime.initialized() || !pluginForSwapchain) {
            optionsEnabled = false;
            lastAppliedOffFlags = Integer.MIN_VALUE;
            return;
        }
        // Off is a correctness boundary before window changes; never throttle it for a VRAM warning.
        int flags = retainResources ? 1 << 3 : 0;
        if (!optionsEnabled && lastAppliedOffFlags == flags) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment options = StreamlineAbi.allocate(arena, StreamlineAbi.DLSSG_OPTIONS_SIZE);
            writeOffOptions(options, retainResources);
            int result = StreamlineRuntime.library().setDlssgOptions(VIEWPORT, options);
            if (result == RESULT_WARN_OUT_OF_VRAM) budgetWarning(false, result);
            else { lastOptionsResult = result; warnResult(result, "slDLSSGSetOptions(Off)"); }
            lastAppliedOffFlags = result == RESULT_OK ? flags : Integer.MIN_VALUE;
        } catch (Throwable throwable) {
            lastAppliedOffFlags = Integer.MIN_VALUE;
            CausticaMod.LOGGER.warn("Could not suspend Streamline DLSS-G", throwable);
        }
        optionsEnabled = false;
        submittedPresentsWithoutGeneration = 0;
    }

    /** Apply exactly one DLSS-G option payload before this frame's PRESENT_START marker. */
    private void applyOptionsForPresent() {
        if (!StreamlineRuntime.initialized() || !pluginForSwapchain || !dlssgSupported) {
            optionsEnabled = false;
            return;
        }
        if (System.nanoTime() < nextOptionsBudgetRetry) return;
        // slDLSSGSetOptions is a state-changing call: the runtime re-validates and can reallocate its
        // resources each time it runs. It was being issued on every single present even when nothing had
        // changed, which makes the generator tear down and rebuild its state once per real frame. Skip it
        // while the option tuple is identical - the previously applied set is still live.
        long fingerprint = optionsFingerprint(frameInputsSubmitted, swapchainWidth, swapchainHeight,
                swapchainFormat, lastRenderWidth, lastRenderHeight, lastHudlessFormat, lastUiAlphaValid);
        if (fingerprint == lastAppliedOptionsFingerprint) {
            optionsEnabled = frameInputsSubmitted;
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment options = StreamlineAbi.allocate(arena, StreamlineAbi.DLSSG_OPTIONS_SIZE);
            if (frameInputsSubmitted) {
                writeOptions(options, swapchainWidth, swapchainHeight, swapchainFormat,
                        lastRenderWidth, lastRenderHeight, lastHudlessFormat, lastUiAlphaValid);
            } else {
                writeOffOptions(options, true);
            }
            int result = StreamlineRuntime.library().setDlssgOptions(VIEWPORT, options);
            // A budget warning must not discard a live request or cause allocation retries each
            // second. Actual generation is still established only from GetState and independent presents.
            if (result == RESULT_WARN_OUT_OF_VRAM && lastOptionsResult != result)
                CausticaMod.LOGGER.warn("DLSS-G is over the VRAM budget; retaining options and checking actual generation");
            lastOptionsResult = result;
            if (result != RESULT_OK && result != RESULT_WARN_OUT_OF_VRAM && frameInputsSubmitted) {
                // Only a refusal reaches here: an MFG unlocker that accepts the request is never capped.
                int submitted = StreamlineAbi.bytes(options).getInt(4);
                if (submitted > 1) {
                    int cap = multiFrameCountMax > 0 && multiFrameCountMax < submitted ? multiFrameCountMax : 1;
                    refusedGeneratedCap = cap;
                    lastAppliedOptionsFingerprint = Long.MIN_VALUE;
                    CausticaMod.LOGGER.warn("[DLSS-G] runtime refused {}x ({}); retrying at {}x", submitted + 1, result, cap + 1);
                    return;
                }
            }
            if (result != RESULT_OK && result != RESULT_WARN_OUT_OF_VRAM) {
                lastAppliedOptionsFingerprint = Long.MIN_VALUE;
                invalidateGeneration();
                dlssgFailed = true;
                optionsEnabled = false;
                unavailableReason = "Could not apply present-bound DLSS-G options: " + StreamlineRuntime.lastError();
                CausticaMod.LOGGER.error(unavailableReason);
                return;
            }
            if (frameInputsSubmitted) {
                ByteBuffer submitted = StreamlineAbi.bytes(options);
                lastSubmittedMode = submitted.getInt(0);
                lastSubmittedGeneratedFrameCount = submitted.getInt(4);
                successfulOptionsSubmissions++;
            }
            optionsEnabled = frameInputsSubmitted;
            lastAppliedOptionsFingerprint = fingerprint;
            nextStateQueryNanos = 0L; // validate changed options after this Present, then resume periodic sampling
            dev.comfyfluffy.caustica.rt.CausticaPerfLog.fgOptions("mode=" + effectiveMode()
                    + " mult=" + submittedGeneratedCount() + " requested=" + effectiveMultiFrameCount() + " out=" + swapchainWidth + "x" + swapchainHeight
                    + " render=" + lastRenderWidth + "x" + lastRenderHeight + " images=" + swapchainImageCount);
            lastAppliedOffFlags = frameInputsSubmitted ? Integer.MIN_VALUE : 1 << 3;
            if (frameInputsSubmitted && loggedOptionsGeneration != swapchainGeneration) {
                loggedOptionsGeneration = swapchainGeneration;
                CausticaMod.LOGGER.info(
                        "DLSS-G options bound before PRESENT_START for swapchain {}, frame {}: mode={}, generated={}, output={}x{}",
                        swapchainGeneration, currentFrameIndex, CausticaConfig.Rt.Fg.mode(),
                        effectiveMultiFrameCount(), swapchainWidth, swapchainHeight);
            }
        } catch (Throwable throwable) {
            dlssgFailed = true;
            optionsEnabled = false;
            unavailableReason = "Could not apply present-bound DLSS-G options: " + throwable.getMessage();
            CausticaMod.LOGGER.error(unavailableReason, throwable);
        }
    }

    private void refreshState() {
        if (!StreamlineRuntime.initialized() || !pluginForSwapchain || !dlssgSupported) {
            return;
        }
        if (System.nanoTime() < nextStateBudgetRetry) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = StreamlineAbi.allocate(arena, StreamlineAbi.DLSSG_STATE_SIZE);
            int result = StreamlineRuntime.library().getDlssgState(VIEWPORT, state);
            ByteBuffer bytes = StreamlineAbi.bytes(state);
            if (result == RESULT_WARN_OUT_OF_VRAM && (bytes.getInt(12) <= 0 || bytes.getInt(20) <= 0)) {
                budgetWarning(true, result); // No initialized output: never claim generation from a warning alone.
                return;
            }
            lastStateResult = result;
            if (result != RESULT_OK && result != RESULT_WARN_OUT_OF_VRAM) {
                lastAppliedOptionsFingerprint = Long.MIN_VALUE;
                invalidateGeneration();
                dlssgFailed = true;
                optionsEnabled = false;
                unavailableReason = "Could not query DLSS-G runtime state: " + StreamlineRuntime.lastError();
                CausticaMod.LOGGER.error(unavailableReason);
                return;
            }
            dlssgStateKnown = true;
            runtimeStatus = bytes.getInt(8);
            minWidthOrHeight = bytes.getInt(12);
            framesActuallyPresented = bytes.getInt(16);
            // Accepting slDLSSGSetOptions is not the same as generating frames. With a third-party MFG
            // unlocker present the runtime reported numFramesToGenerateMax=5 and accepted 1..5 while
            // numFramesActuallyPresented stayed at 1 and numFramesGenerated at 0, i.e. nothing was
            // interpolated. Say so instead of letting the UI imply that 6x is running.
            if (framesActuallyPresented > 1) {
                maxObservedGenerated = Math.max(maxObservedGenerated, framesActuallyPresented - 1);
            }
            if (optionsEnabled && framesActuallyPresented <= 1) {
                if (++consecutiveInactiveGenerationSamples == 3) {
                    CausticaMod.LOGGER.warn("[DLSS-G] options accepted (submitted={}, max={}) but the runtime reports no generated frames; interpolation is not happening",
                            lastSubmittedGeneratedFrameCount, multiFrameCountMax);
                }
            } else {
                consecutiveInactiveGenerationSamples = 0;
            }
            // Keep the sampled values for diagnostics. Runtime 310.9.16 returned 2 at 2x even with
            // sparse queries; summing these observations is not a presentation-rate measurement.
            // Use independent ETW presentation capture for benchmark rates.
            if (framesActuallyPresented > 0) {
                presentedFramesAccumulated += framesActuallyPresented;
            }
            vsyncSupportAvailable = bytes.getInt(24) != 0;
            maxFramesActuallyPresented = Math.max(maxFramesActuallyPresented, framesActuallyPresented);
            int generatedNow = optionsEnabled ? Math.max(0, framesActuallyPresented - 1) : 0;
            if (generatedNow > 0) {
                if (!generatedFramesConfirmed) {
                    CausticaMod.LOGGER.info("DLSS-G generated presentation active: {} total frames, {} interpolated",
                            framesActuallyPresented, generatedNow);
                }
                generatedFramesConfirmed = true;
                lastGeneratedFrameCount = generatedNow;
                submittedPresentsWithoutGeneration = 0;
                submissionStatus = "Generated presentation confirmed";
            } else if (optionsEnabled && frameInputsSubmitted) {
                // One old successful interpolation is not proof that current frames are still generated.
                generatedFramesConfirmed = false;
                lastGeneratedFrameCount = 0;
                // Fullscreen-menu detection intentionally suspends generation while this screen is open.
                // Only replace the status when this present actually carried world-frame inputs.
                submissionStatus = "Submitted, but Streamline presented no generated frames";
                submittedPresentsWithoutGeneration++;
                if (submittedPresentsWithoutGeneration == 120) {
                    CausticaMod.LOGGER.warn(
                            "DLSS-G accepted 120 consecutive world presents without interpolation: status={}, presented={}, mode={}, requestedGenerated={}, reset={}",
                            statusDescription(runtimeStatus), framesActuallyPresented,
                            CausticaConfig.Rt.Fg.mode(), effectiveMultiFrameCount(), lastSubmissionReset);
                    logNativeTrace();
                }
            }
            int reportedMultiFrameCountMax = bytes.getInt(20);
            // The maximum is an adapter capability. A newly recreated swapchain can temporarily return an
            // unpopulated state packet; never let that erase a previously authoritative 2x-6x range.
            if (reportedMultiFrameCountMax > 0) {
                multiFrameCountMax = retainReportedMaximum(multiFrameCountMax, reportedMultiFrameCountMax);
            }
            // Report the adapter capability next to the user's request. The request is still submitted as-is so
            // a third-party MFG unlocker can accept it; the runtime, not this code, decides what is possible.
            int requestedGenerated = configuredMultiFrameCount();
            if (multiFrameCountMax > 0
                    && (loggedMaxSupportFor != multiFrameCountMax || loggedMaxSupportRequest != requestedGenerated)) {
                loggedMaxSupportFor = multiFrameCountMax;
                loggedMaxSupportRequest = requestedGenerated;
                if (requestedGenerated > multiFrameCountMax) {
                    CausticaMod.LOGGER.warn("[DLSS-G] requested {} generated frame(s) ({}x) but the runtime reports a maximum of {} ({}x); the excess will be refused unless a compatible MFG unlocker is active",
                            requestedGenerated, requestedGenerated + 1, multiFrameCountMax, multiFrameCountMax + 1);
                } else {
                    CausticaMod.LOGGER.info("[DLSS-G] multiplier {}x is within the reported maximum {}x",
                            requestedGenerated + 1, multiFrameCountMax + 1);
                }
            }
            stateDynamicMfgSupported = bytes.getInt(28) != 0;
            inputsProcessingFence = bytes.getLong(32);
            inputsProcessingFenceValue = bytes.getLong(40);
            if (frameInputsSubmitted && optionsEnabled && useNoClientQueues()
                    && inputSlots.hasActive()) {
                inputSlots.retireActive(inputsProcessingFence, inputsProcessingFenceValue);
            }
            if (runtimeStatus != 0) {
                unavailableReason = statusDescription(runtimeStatus);
                if (optionsEnabled) {
                    setOff(false);
                }
            } else if (dlssgSupported) {
                unavailableReason = "";
            }
        }

    }

    private void logNativeTrace() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment trace = StreamlineAbi.allocate(arena, StreamlineAbi.TRACE_STATE_SIZE);
            if (StreamlineRuntime.library().getTraceState(trace) != RESULT_OK) {
                CausticaMod.LOGGER.warn("Could not query native DLSS-G frame trace: {}", StreamlineRuntime.lastError());
                return;
            }
            ByteBuffer b = StreamlineAbi.bytes(trace);
            CausticaMod.LOGGER.warn(
                    "DLSS-G native frame trace: calls(begin={}, constants={}, tags={}, options={}, states={}, markers={}, acquire={}, present={}); order(event={}, begin={}, constants={}, tags={}, options={}, presentStart={}, proxyPresent={}, presentEnd={}); frame={} marker={} token=0x{} cmd=0x{}; options(mode={}, generated={}, flags=0x{}, color={}x{}, guides={}x{}); resources={}/valid=0x{}/memory=0x{}, backbufferSubrect={}x{}; present(queue=0x{}, swapchain=0x{}, waits={}, chains={}, image={}); state(status=0x{}, presented={})",
                    b.getLong(0), b.getLong(8), b.getLong(16), b.getLong(24), b.getLong(32), b.getLong(40),
                    b.getLong(48), b.getLong(56), b.getLong(96), b.getLong(104), b.getLong(112), b.getLong(120),
                    b.getLong(128), b.getLong(136), b.getLong(144), b.getLong(152),
                    Integer.toUnsignedString(b.getInt(160)), b.getInt(164),
                    Long.toHexString(b.getLong(64)), Long.toHexString(b.getLong(72)), b.getInt(168), b.getInt(172),
                    Integer.toHexString(b.getInt(176)), b.getInt(180), b.getInt(184), b.getInt(188), b.getInt(192),
                    b.getInt(196), Integer.toHexString(b.getInt(200)), Integer.toHexString(b.getInt(232)),
                    b.getInt(224), b.getInt(228),
                    Long.toHexString(b.getLong(80)),
                    Long.toHexString(b.getLong(88)), b.getInt(204), b.getInt(208), Integer.toUnsignedString(b.getInt(212)),
                    Integer.toHexString(b.getInt(216)), b.getInt(220));
            CausticaMod.LOGGER.warn(
                    "DLSS-RR native frame trace: calls(optimal={}, options={}, evaluate={}, free={}); frameToken=0x{} cmd=0x{}; viewport={} mode={} resources={}; result=0x{}",
                    b.getLong(240), b.getLong(248), b.getLong(256), b.getLong(264),
                    Long.toHexString(b.getLong(272)), Long.toHexString(b.getLong(280)),
                    b.getInt(300), b.getInt(288), b.getInt(292), Integer.toHexString(b.getInt(296)));
        } catch (Throwable throwable) {
            CausticaMod.LOGGER.warn("Could not query native DLSS-G frame trace", throwable);
        }
    }

    private int waitForInputSlot(int slot) {
        if (!StreamlineRuntime.initialized()
                || !(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend()
                instanceof VulkanDevice device)) {
            return -1;
        }
        long started = System.nanoTime();
        int result = StreamlineRuntime.library().vkWaitTimeline(device.vkDevice().address(),
                inputSlots.fence(slot), inputSlots.value(slot), -1L);
        long elapsed = Math.max(0L, System.nanoTime() - started);
        timelineWaitCount++;
        timelineWaitTotalNanos += elapsed;
        timelineWaitMaximumNanos = Math.max(timelineWaitMaximumNanos, elapsed);
        if (result == RESULT_OK) {
            clearInputSlot(slot);
        }
        return result;
    }

    private boolean enterBlockingQueueFallback(String reason) {
        if (!StreamlineRuntime.initialized()
                || !(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend()
                instanceof VulkanDevice device)) {
            return false;
        }
        int result = StreamlineRuntime.vkDeviceWaitIdle(
                device.vkDevice(), "DLSSG queue fallback", false);
        if (result != RESULT_OK) {
            dlssgFailed = true;
            unavailableReason = "Could not quiesce for Streamline queue fallback: " + StreamlineRuntime.lastError();
            CausticaMod.LOGGER.error(unavailableReason);
            return false;
        }
        queueFallback = true;
        queueFallbackReason = reason;
        clearAllInputSlots();
        inputSlots.markPrepared();
        forceResetNextSubmission = true;
        CausticaMod.LOGGER.warn("DLSS-G queue parallelism fell back to blocking mode: {}", reason);
        return true;
    }

    private void drainInputSlots(String reason) {
        boolean needsDeviceIdle = false;
        for (int slot = 0; slot < inputSlots.count(); slot++) {
            if (!inputSlots.pending(slot)) {
                continue;
            }
            if (!inputSlots.valid(slot)
                    || waitForInputSlot(slot) != RESULT_OK) {
                needsDeviceIdle = true;
                break;
            }
        }
        if (needsDeviceIdle && StreamlineRuntime.initialized()
                && ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            int result = StreamlineRuntime.vkDeviceWaitIdle(
                    device.vkDevice(), "DLSSG input drain: " + reason, false);
            if (result != RESULT_OK) {
                CausticaMod.LOGGER.error("Could not quiesce Streamline inputs for {}: {}", reason,
                        StreamlineRuntime.lastError());
            }
        }
        clearAllInputSlots();
    }

    private void resetInputSlots(int count) {
        inputSlots.reset(count);
    }

    private void clearAllInputSlots() {
        inputSlots.clearAll();
    }

    private void clearInputSlot(int slot) {
        inputSlots.clear(slot);
    }

    private boolean useNoClientQueues() {
        String override = diagnosticQueueOverride();
        // Safe default: Streamline serializes consumption with the presenting queue (guide15.1).
        // No-client-queues remains an explicit diagnostic requiring a valid retirement fence.
        return !queueFallback && "no-client-queues".equals(override);
    }

    private static String diagnosticQueueOverride() {
        String value = System.getProperty("caustica.streamline.queueParallelism", "auto")
                .trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "safe", "no-client-queues" -> value;
            default -> "auto";
        };
    }

    /** Expensive, user-triggered estimate query; never called from the per-frame state path. */
    public long requestVramEstimate() {
        if (!isAvailable() || swapchainWidth <= 0 || swapchainHeight <= 0) {
            return 0L;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment options = StreamlineAbi.allocate(arena, StreamlineAbi.DLSSG_OPTIONS_SIZE);
            int renderWidth = lastRenderWidth > 0 ? lastRenderWidth : swapchainWidth;
            int renderHeight = lastRenderHeight > 0 ? lastRenderHeight : swapchainHeight;
            int hudlessFormat = lastHudlessFormat != 0
                    ? lastHudlessFormat
                    : dev.comfyfluffy.caustica.rt.RtComposite.INSTANCE.isHdrPresentActive() ? swapchainFormat : VK10.VK_FORMAT_R8G8B8A8_UNORM;
            writeOptions(options, swapchainWidth, swapchainHeight, swapchainFormat,
                    renderWidth, renderHeight, hudlessFormat, lastUiAlphaValid);
            StreamlineAbi.bytes(options).putInt(8, 1 << 2);
            MemorySegment state = StreamlineAbi.allocate(arena, StreamlineAbi.DLSSG_STATE_SIZE);
            if (StreamlineRuntime.library().getDlssgState(VIEWPORT, state, options) == RESULT_OK) {
                estimatedVramUsage = StreamlineAbi.bytes(state).getLong(0);
            }
        }
        return estimatedVramUsage;
    }

    private void queryFeatureMetadata() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = StreamlineAbi.allocate(arena, 24);
            if (StreamlineRuntime.library().getFeatureVersion(StreamlineRuntime.FEATURE_DLSS_G, version) == RESULT_OK) {
                ByteBuffer bytes = StreamlineAbi.bytes(version);
                featureVersion = bytes.getInt(0) + "." + bytes.getInt(4) + "." + bytes.getInt(8)
                        + " / NGX " + bytes.getInt(12) + "." + bytes.getInt(16) + "." + bytes.getInt(20);
            }
            MemorySegment requirements = StreamlineAbi.allocate(arena, 24);
            if (StreamlineRuntime.library().getFeatureRequirements(
                    StreamlineRuntime.FEATURE_DLSS_G, requirements) == RESULT_OK) {
                ByteBuffer bytes = StreamlineAbi.bytes(requirements);
                CausticaMod.LOGGER.info("DLSS-G {} requirements: tags={}, computeQueues={}, graphicsQueues={}, opticalFlowQueues={}",
                        featureVersion, bytes.getInt(8), bytes.getInt(12), bytes.getInt(16), bytes.getInt(20));
            }
        }
    }

    private void refreshReflexState() {
        if (!StreamlineRuntime.initialized() || !reflexSupported) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = StreamlineAbi.allocate(arena, StreamlineAbi.REFLEX_STATE_SIZE);
            if (StreamlineRuntime.library().getReflexState(state) == RESULT_OK) {
                flashIndicatorDriverControlled = StreamlineAbi.bytes(state).getInt(8) != 0;
            }
        }
    }

    /** Cheap identity of the option set; equal means the runtime already holds exactly these values. */
    private long optionsFingerprint(boolean submitting, int colorWidth, int colorHeight, int colorFormat,
            int renderWidth, int renderHeight, int hudlessFormat, boolean uiValid) {
        long h = 1125899906842597L;
        h = h * 31 + (submitting ? 1 : 0);
        h = h * 31 + effectiveMode();
        h = h * 31 + submittedGeneratedCount();
        h = h * 31 + swapchainImageCount;
        h = h * 31 + renderWidth;
        h = h * 31 + renderHeight;
        h = h * 31 + colorWidth;
        h = h * 31 + colorHeight;
        h = h * 31 + colorFormat;
        h = h * 31 + hudlessFormat;
        h = h * 31 + (useNoClientQueues() ? 1 : 0);
        h = h * 31 + ((uiValid && CausticaConfig.Rt.Fg.UI_RECOMPOSITION.value()) ? 1 : 0);
        h = h * 31 + Float.floatToIntBits(CausticaConfig.Rt.Fg.DYNAMIC_TARGET_FPS.value());
        h = h * 31 + (CausticaConfig.Rt.Fg.SHOW_ONLY_INTERPOLATED.value() ? 1 : 0);
        return h;
    }

    /**
     * Generated frames actually submitted, as opposed to the user request.
     *
     * <p>The runtime reports an authoritative ceiling in {@code DLSSGState}. Until that ceiling is known,
     * only one generated frame (2x) is submitted: requesting 3x+ without a known ceiling is what produced
     * repeated and stale generated frames on this hardware. Once the ceiling is known the request is
     * honoured up to it, so a working multi-frame plugin still enables 3x-6x.
     */
    private int submittedGeneratedCount() {
        int requested = effectiveMultiFrameCount();
        int ceiling = multiFrameCountMax > 0 ? multiFrameCountMax : 1;
        // Step up one level at a time, and only after the runtime has actually demonstrated the previous
        // level. bridgeReady is deliberately not the test: the working 26.1.2 setup ran through the NGX
        // provider route with bridgeReady=false and setOptionsCalls=0, so that flag cannot veto a route.
        ceiling = Math.min(ceiling, maxObservedGenerated + 1);
        int submitted = Math.min(requested, ceiling);
        if (submitted != requested && loggedClampedCount != submitted) {
            loggedClampedCount = submitted;
            CausticaMod.LOGGER.warn(
                    "[DLSS-G] requested {} generated frame(s) ({}x) but the runtime ceiling is {} ({}x); submitting {} ({}x)",
                    requested, requested + 1, ceiling, ceiling + 1, submitted, submitted + 1);
        }
        return submitted;
    }

    private void writeOptions(MemorySegment segment, int colorWidth, int colorHeight, int colorFormat,
            int renderWidth, int renderHeight, int hudlessFormat, boolean uiValid) {
        ByteBuffer bytes = StreamlineAbi.bytes(segment);
        int mode = effectiveMode();
        int flags = 0;
        // Caustica explicitly suspends generation for Minecraft screens in canSubmit(). Streamline's
        // pixel-comparison detector is inappropriate for our custom flipped HUD-less/UI resources: a
        // false positive silently suppresses interpolation while DLSSGState still reports eOk.
        if (CausticaConfig.Rt.Fg.SHOW_ONLY_INTERPOLATED.value()
                && !StreamlineRuntime.productionVariant()) {
            flags |= 1;
        }
        bytes.putInt(0, mode);
        bytes.putInt(4, submittedGeneratedCount());
        bytes.putInt(8, flags);
        bytes.putInt(20, swapchainImageCount);
        bytes.putInt(24, renderWidth);
        bytes.putInt(28, renderHeight);
        bytes.putInt(32, colorWidth);
        bytes.putInt(36, colorHeight);
        bytes.putInt(40, colorFormat);
        bytes.putInt(44, VK10.VK_FORMAT_R16G16_SFLOAT);
        bytes.putInt(48, VK10.VK_FORMAT_R32_SFLOAT);
        bytes.putInt(52, hudlessFormat);
        // uiBufferFormat describes kBufferTypeUIColorAndAlpha. Caustica supplies the preferred
        // single-channel kBufferTypeUIAlpha tag, whose R32 format is already in its Resource.
        bytes.putInt(56, 0);
        bytes.putInt(60, useNoClientQueues() ? 1 : 0);
        bytes.putInt(64, uiValid && CausticaConfig.Rt.Fg.UI_RECOMPOSITION.value() ? 1 : 0);
        bytes.putFloat(68, CausticaConfig.Rt.Fg.DYNAMIC_TARGET_FPS.value());
    }

    private static void writeOffOptions(MemorySegment segment, boolean retainResources) {
        ByteBuffer bytes = StreamlineAbi.bytes(segment);
        bytes.putInt(0, 0);
        bytes.putInt(4, 1);
        bytes.putInt(8, retainResources ? 1 << 3 : 0);
    }

    private int effectiveMode() {
        return streamlineModeForVulkan(CausticaConfig.Rt.Fg.mode());
    }

    static int selectMultiFrameCount(int requestedCount, int reportedMaximum) {
        // Preserve the user's experimental request. A reported capability is not permission to silently
        // replace 6X with 2X; SetOptions/GetState provide the actual acceptance or refusal.
        return Math.clamp(requestedCount, 1, 5);
    }

    public int lastOptionsResult() { return lastOptionsResult; }
    public int lastStateResult() { return lastStateResult; }

    static int retainReportedMaximum(int cachedMaximum, int newlyReportedMaximum) {
        return newlyReportedMaximum > 0
                ? Math.clamp(newlyReportedMaximum, 1, 5)
                : Math.clamp(cachedMaximum, 0, 5);
    }

    static int streamlineModeForVulkan(String mode) {
        return switch (mode) {
            case "fixed" -> 1;
            case "auto" -> 1;
            // Streamline Dynamic MFG is D3D12-only. Preserve the selected generated-frame count and run
            // the supported fixed mode until the stale selection is migrated on settings save.
            case "dynamic" -> 1;
            default -> 0;
        };
    }

    private static boolean shouldSuspendForMenu() {
        if (!CausticaConfig.Rt.Fg.FULLSCREEN_MENU_DETECTION.value()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var screen = minecraft == null ? null : minecraft.gui.screen();
        // Chat is a small transparent overlay and is safe for UI recomposition. Other Screen instances
        // cover enough of the world that NVIDIA recommends temporarily turning generation off.
        return screen != null && !(screen instanceof ChatScreen);
    }

    /** Write render-space constants for a local Streamline RR evaluation without changing global frame state. */
    void writeRenderSpaceConstants(MemorySegment segment, Matrix4fc projection, Matrix4fc currentViewProjection,
            Matrix4fc previousViewProjection, Matrix4fc viewRotation, float jitterX, float jitterY,
            int renderWidth, int renderHeight, double cameraX, double cameraY, double cameraZ,
            float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ, boolean reset) {
        writeConstants(segment, projection, currentViewProjection, previousViewProjection, viewRotation,
                jitterX, jitterY, renderWidth, renderHeight, cameraX, cameraY, cameraZ,
                cameraDeltaX, cameraDeltaY, cameraDeltaZ, reset, false);
    }

    private void writeConstants(MemorySegment segment, Matrix4fc projection, Matrix4fc currentViewProjection,
            Matrix4fc previousViewProjection, Matrix4fc viewRotation, float jitterX, float jitterY,
            int renderWidth, int renderHeight, double cameraX, double cameraY, double cameraZ,
            float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ, boolean reset,
            boolean presentSpace) {
        ByteBuffer bytes = StreamlineAbi.bytes(segment);
        // DLSS-RR consumes the raw render-space images. DLSS-G consumes tags pre-flipped for the final Vulkan
        // present, so only the global presentation constants reflect the clip-space transforms across Y.
        if (presentSpace) {
            normalizedProjection.set(clipYReflection).mul(projection);
        } else {
            normalizedProjection.set(projection);
        }
        inverseProjection.set(normalizedProjection).invert();
        // The renderer uses camera-relative positions. Reinsert the camera translation between the current
        // and previous rotations so clipToPrevClip describes the same full motion as the guide vectors.
        clipToPrev.set(previousViewProjection).translate(cameraDeltaX, cameraDeltaY, cameraDeltaZ)
                .mul(matrixScratch.set(currentViewProjection).invert());
        if (presentSpace) {
            matrixScratch.set(clipToPrev);
            clipToPrev.set(clipYReflection).mul(matrixScratch).mul(clipYReflection);
        }
        prevToClip.set(clipToPrev).invert();
        writeMatrix(bytes, 0, normalizedProjection);
        writeMatrix(bytes, 64, inverseProjection);
        writeIdentity(bytes, 128);
        writeMatrix(bytes, 192, clipToPrev);
        writeMatrix(bytes, 256, prevToClip);
        // The ray shader offsets each sample by +jitter in pixel space. Streamline constants describe the
        // projection jitter, which is the inverse transform: -sampleJitter. Presentation-space resources
        // are vertically reflected, so only their projection-jitter Y component changes sign again.
        bytes.putFloat(320, -jitterX);
        bytes.putFloat(324, presentSpace ? jitterY : -jitterY);
        bytes.putFloat(328, 1.0f / Math.max(1, renderWidth));
        bytes.putFloat(332, (presentSpace ? -1.0f : 1.0f) / Math.max(1, renderHeight));
        bytes.putFloat(336, 0.0f);
        bytes.putFloat(340, 0.0f);
        bytes.putFloat(344, (float) cameraX);
        bytes.putFloat(348, (float) cameraY);
        bytes.putFloat(352, (float) cameraZ);
        cameraToWorld.set(viewRotation).invert();
        cameraToWorld.transformDirection(1, 0, 0, cameraRight).normalize();
        cameraToWorld.transformDirection(0, 1, 0, cameraUp).normalize();
        cameraToWorld.transformDirection(0, 0, -1, cameraForward).normalize();
        writeVector(bytes, 356, cameraUp);
        writeVector(bytes, 368, cameraRight);
        writeVector(bytes, 380, cameraForward);
        float near = safePerspectiveNear(projection);
        float far = safePerspectiveFar(projection, near);
        bytes.putFloat(392, near);
        bytes.putFloat(396, far);
        bytes.putFloat(400, safePerspectiveFov(projection));
        bytes.putFloat(404, renderWidth / (float) Math.max(1, renderHeight));
        bytes.putFloat(408, Float.MAX_VALUE);
        bytes.putInt(412, 1);
        bytes.putInt(416, 1);
        bytes.putInt(420, 0);
        bytes.putInt(424, reset ? 1 : 0);
        bytes.putInt(428, 0);
        bytes.putInt(432, 0);
        bytes.putInt(436, 0);
        bytes.putFloat(440, 40.0f);
    }

    private static void writeResource(MemorySegment resources, int index, RtImage image, int format,
            int width, int height, int bufferType, boolean valid) {
        writeResource(resources, index, image == null ? 0L : image.image, image == null ? 0L : image.view,
                image == null ? 0L : image.memory,
                format, width, height, bufferType, image == null ? 0 : image.usage, valid && image != null);
    }

    private static void writeResource(MemorySegment resources, int index, long image, long view, long memory, int format,
            int width, int height, int bufferType, int usage, boolean valid) {
        ByteBuffer bytes = StreamlineAbi.bytes(resources);
        int base = index * StreamlineAbi.RESOURCE_DESC_SIZE;
        bytes.putLong(base, image);
        bytes.putLong(base + 8, view);
        bytes.putLong(base + 16, memory);
        bytes.putInt(base + 24, VK10.VK_IMAGE_LAYOUT_GENERAL);
        bytes.putInt(base + 28, width);
        bytes.putInt(base + 32, height);
        bytes.putInt(base + 36, format);
        bytes.putInt(base + 40, 1);
        bytes.putInt(base + 44, 1);
        bytes.putInt(base + 48, 0);
        bytes.putInt(base + 52, usage);
        bytes.putInt(base + 56, bufferType);
        bytes.putInt(base + 60, 1); // eValidUntilPresent: these images are unchanged until this token presents.
        bytes.put(base + 64, valid ? (byte) 1 : (byte) 0);
    }

    private static void writeMatrix(ByteBuffer bytes, int offset, Matrix4fc matrix) {
        StreamlineAbi.writeRowVectorMatrix(bytes, offset, matrix);
    }

    private static void writeIdentity(ByteBuffer bytes, int offset) {
        writeMatrix(bytes, offset, new Matrix4f());
    }

    private static void writeVector(ByteBuffer bytes, int offset, Vector3f vector) {
        bytes.putFloat(offset, vector.x);
        bytes.putFloat(offset + 4, vector.y);
        bytes.putFloat(offset + 8, vector.z);
    }

    private static float safePerspectiveNear(Matrix4fc projection) {
        try {
            float value = projection.perspectiveNear();
            return Float.isFinite(value) && value > 0.0f ? value : 0.05f;
        } catch (Throwable ignored) {
            return 0.05f;
        }
    }

    private static float safePerspectiveFar(Matrix4fc projection, float near) {
        try {
            float value = projection.perspectiveFar();
            return Float.isFinite(value) && value > near ? value : 4096.0f;
        } catch (Throwable ignored) {
            return 4096.0f;
        }
    }

    private static float safePerspectiveFov(Matrix4fc projection) {
        try {
            float value = projection.perspectiveFov();
            return Float.isFinite(value) && value > 0.0f ? value : (float) Math.toRadians(70.0);
        } catch (Throwable ignored) {
            return (float) Math.toRadians(70.0);
        }
    }

    public void destroy() {
        drainInputSlots("shutdown");

        setOff(false);
        releaseFeatureResourcesAfterIdle();
        frameToken = 0L;
        currentFrameIndex = -1;
        frameInputsSubmitted = false;
        triggerFlashPending = false;
        resetInputSlots(0);
        pluginForSwapchain = false;
        optionsEnabled = false;
        lastAppliedOffFlags = Integer.MIN_VALUE;
        generatedFramesConfirmed = false;
        lastGeneratedFrameCount = 0;
        loggedSubmissionGeneration = -1L;
        loggedOptionsGeneration = -1L;
        submittedPresentsWithoutGeneration = 0;
        submissionStatus = "Destroyed";
        swapchainGeneration = 0L;
        lastSubmittedSwapchainGeneration = -1L;
        forceResetNextSubmission = true;
        dlssgStateKnown = false;
        multiFrameCountMax = 0;
        stateDynamicMfgSupported = false;
    }

    public void resetFailureLatch() {
        dlssgFailed = false;
        runtimeStatus = 0;
        generatedFramesConfirmed = false;
        lastGeneratedFrameCount = 0;
        submissionStatus = "Retrying frame submission";
        unavailableReason = dlssgSupported ? "" : "Not supported";
    }

    public static String statusDescription(int status) {
        if (status == 0) {
            return "OK";
        }
        StringBuilder result = new StringBuilder("DLSS-G runtime status:");
        appendStatus(result, status, 1 << 0, "resolution too low");
        appendStatus(result, status, 1 << 1, "Reflex not detected");
        appendStatus(result, status, 1 << 2, "HDR format unsupported");
        appendStatus(result, status, 1 << 3, "common constants invalid");
        appendStatus(result, status, 1 << 4, "backbuffer index not tracked");
        return result.toString();
    }

    private static void appendStatus(StringBuilder output, int status, int mask, String label) {
        if ((status & mask) != 0) {
            output.append(' ').append(label).append(';');
        }
    }

    private static void check(int result, String operation) {
        if (result != RESULT_OK) {
            throw new IllegalStateException(operation + " failed: " + StreamlineRuntime.lastError()
                    + " (result " + result + ")");
        }
    }

    private static void warnResult(int result, String operation) {
        if (result != RESULT_OK) {
            CausticaMod.LOGGER.warn("{} failed: {} (result {})", operation, StreamlineRuntime.lastError(), result);
        }
    }
}
