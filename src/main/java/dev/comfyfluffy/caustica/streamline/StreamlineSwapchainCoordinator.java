package dev.comfyfluffy.caustica.streamline;

import com.mojang.blaze3d.systems.GpuSurface;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg;
import net.minecraft.client.Minecraft;

import java.util.Collection;

/** Coordinates DLSS-G plugin ownership with Minecraft's existing surface reconfiguration transaction. */
public final class StreamlineSwapchainCoordinator {
    public static final StreamlineSwapchainCoordinator INSTANCE = new StreamlineSwapchainCoordinator();

    private final WindowTransitionEpoch windowTransition=new WindowTransitionEpoch();
    private boolean configuring;
    private boolean configured;
    private boolean pluginForSwapchain;
    private boolean physicalFifo;
    private boolean vsyncRequested;
    private boolean mailboxSupported;
    private boolean mailboxVsyncCompatibility;
    private GpuSurface.PresentMode requestedPresentMode;
    private GpuSurface.PresentMode presentMode;
    private int width;
    private int height;
    private int format;
    private int imageCount;
    private long generation;
    private boolean reconfigureRequested;
    private StreamlineRuntime.SwapchainTrace nativeSwapchain = StreamlineRuntime.SwapchainTrace.unknown();

    private StreamlineSwapchainCoordinator() {
    }

    /** Request Minecraft's normal, render-thread surface recreation rather than replacing its lifecycle. */
    public void requestReconfigure() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        reconfigureRequested = true;
        minecraft.execute(minecraft::invalidateSurfaceConfiguration);
    }

    /** Stop interpolation before app-owned window manipulation, not only after Vulkan recreation begins. */
    public void windowChanging() {
        if(!StreamlineRuntime.initialized())return;
        windowTransition.changed();
        if(com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())
            RtStreamlineFg.INSTANCE.suspendForWindowManipulation();
    }
    public boolean windowTransitionPending() { return windowTransition.pending(); }

    /** Reconcile config/TOML changes that did not originate in the options screen. */
    public void synchronizeRequestedState() {
        if (!configured || configuring || reconfigureRequested) {
            return;
        }
        if(windowTransition.pending()) { requestReconfigure(); return; }
        boolean desiredPlugin = CausticaConfig.Rt.Fg.requested()
                && (!isVsyncRequested() || mailboxSupported);
        // Fixed 2x/3x/4x is an option change on the same proxy. Recreating the entire swapchain here
        // needlessly retires NGX, input slots and their allocations for every menu multiplier click.
        // applyOptionsForPresent fingerprints the submitted count; submitFrame resets interpolation history.
        // Off/On and actual surface changes still use the fully quiesced recreation transaction.
        if (desiredPlugin != pluginForSwapchain) {
            requestReconfigure();
        }
    }

    /** Called at configure HEAD, before Minecraft waits and destroys its old swapchain. */
    public void configureStarting() {
        windowTransition.configurationStarted();
        configuring = true;
        configured = false;
        reconfigureRequested = false;
        nativeSwapchain = StreamlineRuntime.SwapchainTrace.unknown();
        // Submit recorded app commands before destroying views/semaphores or unloading the FG proxy.
        // A queue/device-idle wait only covers submitted work, not MC's still-recorded encoder batches.
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        if (device != null) device.createCommandEncoder().submit();
        RtStreamlineFg.INSTANCE.suspendForSwapchainChange();
        var context = dev.comfyfluffy.caustica.rt.RtContext.currentOrNull();
        if (context != null) {
            context.waitIdle();
            RtStreamlineFg.INSTANCE.releaseFeatureResourcesAfterIdle();
            dev.comfyfluffy.caustica.rt.RtComposite.INSTANCE.releaseStreamlineInputsAfterIdle();
        }
    }

    /**
     * Preserve Minecraft's VSync option while keeping Vulkan DLSS-G on a supported presentation path.
     *
     * <p>Streamline 2.12 cannot run Vulkan DLSS-G on a FIFO swapchain. Use MAILBOX when VSync is
     * requested: MAILBOX remains tear-free because display replacement occurs at vblank and it owns
     * presentation cadence without an application-side frame limiter.
     * A surface without MAILBOX stays on the requested FIFO mode and DLSS-G fails closed.</p>
     */
    public GpuSurface.Configuration normalizeConfiguration(GpuSurface.Configuration configuration,
            Collection<GpuSurface.PresentMode> supportedPresentModes) {
        requestedPresentMode = configuration.presentMode();
        vsyncRequested = isVsyncConfiguration(configuration);
        mailboxSupported = supportedPresentModes.contains(GpuSurface.PresentMode.MAILBOX);
        mailboxVsyncCompatibility = false;
        if (!CausticaConfig.Rt.Fg.requested() || !vsyncRequested
                || !mailboxSupported) {
            presentMode = configuration.presentMode();
            return configuration;
        }
        mailboxVsyncCompatibility = true;
        presentMode = GpuSurface.PresentMode.MAILBOX;
        CausticaMod.LOGGER.info(
                "DLSS-G VSync compatibility: requested {} -> MAILBOX (no application frame limiter)",
                configuration.presentMode());
        return new GpuSurface.Configuration(configuration.width(), configuration.height(), presentMode);
    }

    /** Called after the old swapchain is destroyed and immediately before replacement creation. */
    public boolean prepareReplacement(GpuSurface.Configuration configuration) {
        physicalFifo = isVsyncConfiguration(configuration);
        presentMode = configuration.presentMode();
        boolean desiredPlugin = CausticaConfig.Rt.Fg.requested() && !physicalFifo;
        // On the initial Off swapchain, capture adapter support while DLSS-G is still loaded from slInit;
        // the feature is deliberately unloaded immediately below to remove disabled-present overhead.
        RtStreamlineFg.INSTANCE.probeAvailabilityOnce();
        boolean prepared = StreamlineRuntime.prepareSwapchain(desiredPlugin);
        pluginForSwapchain = desiredPlugin && prepared;
        if (desiredPlugin && !prepared) {
            CausticaMod.LOGGER.warn("DLSS-G swapchain proxy could not be enabled; replacement remains native");
        }
        return pluginForSwapchain;
    }

    /** Called only after Minecraft has successfully created and enumerated the replacement swapchain. */
    public void configured(GpuSurface.Configuration configuration, int actualWidth, int actualHeight,
            int nativeFormat, int buffers) {
        configuring = false;
        configured = true;
        windowTransition.configurationCompleted();
        // The successfully created physical swapchain is authoritative. Streamline receives the visible
        // render target separately through its extent-only backbuffer tag.
        width = actualWidth;
        height = actualHeight;
        format = nativeFormat;
        imageCount = buffers;
        generation++;
        nativeSwapchain = StreamlineRuntime.nativeSwapchainTrace();
        RtStreamlineFg.INSTANCE.onSwapchainConfigured(width, height, format, imageCount, vsyncRequested,
                physicalFifo, pluginForSwapchain, generation);
        CausticaMod.LOGGER.info(
                "Streamline swapchain generation {}: {}x{}, format={}, applicationImages={}, plugin={}, requestedPresentMode={}, normalizedPresentMode={}, vsyncRequested={}, mailboxVsyncCompatibility={}, nativePresentMode={} (value={}), requestedNativeMinImages={}, proxyVisibleImages={}, nativeCreateResult={}, nativeProxyDispatch={}, nativeSwapchain={}",
                generation, width, height, format, imageCount, pluginForSwapchain, requestedPresentMode, presentMode, vsyncRequested,
                mailboxVsyncCompatibility, nativeSwapchain.presentMode(), nativeSwapchain.presentModeValue(),
                nativeSwapchain.minImageCount(), nativeSwapchain.imageCount(), nativeSwapchain.createResult(),
                nativeSwapchain.proxyDispatch(), nativeSwapchain.handleHex());
        if (mailboxVsyncCompatibility && nativeSwapchain.presentModeKnown()
                && !"MAILBOX".equals(nativeSwapchain.presentMode())) {
            CausticaMod.LOGGER.error(
                    "DLSS-G MAILBOX VSync proof failed: requested MAILBOX but native proxy observed {} (value={})",
                    nativeSwapchain.presentMode(), nativeSwapchain.presentModeValue());
        }
    }

    public void configureFailed() {
        configuring = false;
        configured = false;
        RtStreamlineFg.INSTANCE.onSwapchainConfigurationFailed();
    }

    public void closing() {
        configured = false;
        configuring = false;
        RtStreamlineFg.INSTANCE.suspendForSwapchainChange();
    }

    public boolean configured() {
        return configured;
    }

    /** SL returns non-presentable virtual images. Its pacer consumes them as transfer sources and
     * transitions/presents the physical swapchain itself. Native images still use PRESENT_SRC. */
    public int clientImageFinalLayout() {
        return configured && pluginForSwapchain ? org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                : org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    }

    public boolean configuring() {
        return configuring;
    }

    public long generation() {
        return generation;
    }

    public boolean vsyncRequested() {
        return vsyncRequested;
    }

    public boolean mailboxVsyncCompatibility() {
        return mailboxVsyncCompatibility;
    }

    public boolean mailboxSupported() {
        return mailboxSupported;
    }

    public String presentMode() {
        return presentMode == null ? "unknown" : presentMode.name();
    }

    public String requestedPresentMode() {
        return requestedPresentMode == null ? "unknown" : requestedPresentMode.name();
    }

    public String normalizedPresentMode() {
        return presentMode();
    }

    public String nativePresentMode() {
        return nativeSwapchain.presentMode();
    }

    public int nativePresentModeValue() {
        return nativeSwapchain.presentModeValue();
    }

    public boolean nativePresentModeKnown() {
        return nativeSwapchain.presentModeKnown();
    }

    /** Requested minimum in the intercepted native create info; not a physical-image enumeration. */
    public int requestedNativeMinImageCount() {
        return nativeSwapchain.minImageCount();
    }

    /** Image count returned through the Streamline proxy to the application. */
    public int proxyVisibleImageCount() {
        return nativeSwapchain.imageCount();
    }

    public int applicationImageCount() {
        return imageCount;
    }

    public int nativeCreateResult() {
        return nativeSwapchain.createResult();
    }

    public boolean nativeProxyDispatch() {
        return nativeSwapchain.proxyDispatch();
    }

    public String nativeSwapchainHandle() {
        return nativeSwapchain.handleHex();
    }

    private static boolean isVsyncRequested() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.options != null && minecraft.options.enableVsync().get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isVsyncConfiguration(GpuSurface.Configuration configuration) {
        return configuration.presentMode() == GpuSurface.PresentMode.FIFO
                || configuration.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
    }
}
