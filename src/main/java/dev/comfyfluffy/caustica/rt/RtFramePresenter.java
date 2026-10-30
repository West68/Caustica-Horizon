package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;

import it.unimi.dsi.fastutil.longs.LongList;

import net.minecraft.client.Minecraft;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * DLSS Frame Generation present engine (slice 2). Shows more than one image per rendered frame: the
 * generated frame(s), then the real frame.
 *
 * <p>At blitFromTexture TAIL it records all interpolation outputs without acquiring swapchain images.
 * After Minecraft submits the real-frame commands, present HEAD acquires, submits and presents one
 * generated image at a time, then Minecraft presents its already-acquired real image. Thus even 4x
 * generation never requires four simultaneously acquired swapchain images. Acquire semaphore reuse
 * waits for an explicit GPU fence for its previous queue wait.
 *
 * <p>The generated frame is {@link RtDlssFg}'s real DLSSG-interpolated output (via
 * {@link RtComposite#fgInterpolate}); when there's simply no captured RT frame this tick (menu/loading/
 * transition — routine) it falls back to duplicating the real frame for just that one frame (see
 * {@code interpFallbackDuplicate} in the present-rate log), but a genuine FG failure is fatal (see that
 * method's docs) rather than silently duplicating forever. Called from both present paths — the normal SDR
 * {@code blitFromTexture} TAIL, and (via {@code hdrBackbuffer=true}) explicitly from inside the HDR present
 * hook, since the HDR/PQ path cancels {@code blitFromTexture} at HEAD and never reaches the TAIL inject.
 * Gated by {@code caustica.rt.fg} (default off).
 */
public final class RtFramePresenter {
    public static final RtFramePresenter INSTANCE = new RtFramePresenter();

    private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;

    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private GpuFence[] acquireRetirement = new GpuFence[0];
    private VulkanCommandEncoder pendingEncoder;
    private VulkanDevice pendingDevice;
    private LongList pendingImages;
    private long[] pendingImageSemaphores;
    private long[] pendingSources = new long[0];
    private int[] pendingWidths = new int[0], pendingHeights = new int[0];
    private boolean failed;
    private long acceptedExtraPresentCalls;
    private int lastPresentResult;
    public boolean hasFailed() { return failed; }
    public long acceptedExtraPresentCalls() { return acceptedExtraPresentCalls; }
    public int lastPresentResult() { return lastPresentResult; }

    // Generated outputs recorded this frame; swapchain acquisition is deferred until present HEAD.

    private int pendingCount;

    // Present-rate diagnostics: real vs generated vkQueuePresentKHR calls per second, independent of MC's own
    // fps counter (which only counts rendered/simulated frames, not our extra presents).
    private long logWindowStartNs;
    private int realFramesInWindow;
    private int generatedFramesInWindow;
    private int interpOkInWindow;
    private int interpFallbackInWindow;

    private RtFramePresenter() {
    }

    /** Whether FG extra-present should run this frame (enabled, available, in a world). */
    public boolean isActive() {
        return !dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized() && !failed && !RtDlssFg.INSTANCE.hasFailed() && RtDlssFg.enabled() && RtDlssFg.INSTANCE.isAvailable()
                && Minecraft.getInstance().level != null;
    }

    /**
     * Record all generated outputs on Minecraft's encoder. Swapchain acquisition and Y-flipped blits
     * happen one at a time in {@link #flushPendingPresents}, after the interpolation submit.
     * Blits DLSSG's real interpolated output per generated frame, or a duplicate of the real frame when RT
     * simply isn't producing frames this tick (routine). A genuine DLSSG failure latches FG off for the
     * session — see {@link RtComposite#fgInterpolate}.
     *
     * @param hdrBackbuffer whether {@code backbufferView}/{@code srcImage} is the PQ HDR backbuffer
     *     ({@link RtComposite#hdrBackbufferView()}, already UI-composited) rather than the SDR main target —
     *     selects DLSSG's HDR backbuffer format/flag in {@link RtComposite#fgInterpolate}.
     */
    public void prepareExtraFrames(VulkanCommandEncoder enc, VulkanDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long backbufferView, long srcImage, int srcW, int srcH, int generatedCount, boolean hdrBackbuffer) {
        pendingCount = 0;
        if (failed || swapchain == 0L || srcImage == 0L || generatedCount <= 0) {
            return;
        }
        try {
            ensureCapacity(device, swapchainImages.size() + 1, generatedCount);
            pendingEncoder = enc;
            pendingDevice = device;
            pendingImages = swapchainImages;
            pendingImageSemaphores = presentSemaphores;
            for (int i = 0; i < generatedCount; i++) {
                // null = no captured RT frame this tick (menu/loading/transition — routine, not a bug): fall
                // back to duplicating the real frame for just this one frame. A genuine FG failure instead
                // throws, caught below, which disables FG for the session.
                RtImage interp = RtComposite.INSTANCE.fgInterpolate(enc, backbufferView, srcImage,
                        swapW, swapH, i + 1, generatedCount, hdrBackbuffer);
                if (interp != null) {
                    interpOkInWindow++;
                } else {
                    interpFallbackInWindow++;
                }
                long blitSrc = interp != null ? interp.image : srcImage;
                int copyW = Math.min(swapW, interp != null ? interp.width : srcW);
                int copyH = Math.min(swapH, interp != null ? interp.height : srcH);

                // Do not acquire here. MC already owns the real-frame image. Acquiring all
                // generated images before any present can exhaust a triple-buffered swapchain at 4x.
                pendingSources[pendingCount] = blitSrc;
                pendingWidths[pendingCount] = copyW;
                pendingHeights[pendingCount] = copyH;
                pendingCount++;
            }
        } catch (Throwable t) {
            failed = true;
            pendingCount = 0;
            CausticaMod.LOGGER.error("DLSS-FG present-record failed; frame generation disabled", t);
        }
    }

    /**
     * Acquire, blit, submit and present each generated output at present HEAD, after MC has submitted
     * the interpolation outputs. The real frame remains owned by Minecraft and is presented afterward.
     */
    public void flushPendingPresents(long swapchain, VkQueue presentQueue) {
        int presentedThisFrame = 0;
        if (!failed && pendingCount != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (int i = 0; i < pendingCount; i++) {
                    int slot = acquireCursor;
                    GpuFence retired = acquireRetirement[slot];
                    if (retired != null) {
                        if (!retired.awaitCompletion(ACQUIRE_TIMEOUT_NS)) {
                            throw new IllegalStateException("FG acquire semaphore retirement timed out");
                        }
                        retired.close();
                        acquireRetirement[slot] = null;
                    }
                    long acquireSem = acquireSemaphores[slot];
                    IntBuffer imageOut = stack.callocInt(1);
                    int acquired = KHRSwapchain.vkAcquireNextImageKHR(pendingDevice.vkDevice(), swapchain,
                            ACQUIRE_TIMEOUT_NS, acquireSem, 0L, imageOut);
                    if (acquired == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || acquired == VK10.VK_TIMEOUT
                            || acquired == VK10.VK_NOT_READY) break;
                    if (acquired != VK10.VK_SUCCESS && acquired != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                        throw new IllegalStateException("FG vkAcquireNextImageKHR result=" + acquired);
                    }
                    int imageIndex = imageOut.get(0);
                    long presentSem = pendingImageSemaphores[imageIndex];
                    recordBlit(pendingEncoder, pendingSources[i], pendingImages.getLong(imageIndex),
                            pendingWidths[i], pendingHeights[i], acquireSem, presentSem);
                    acquireRetirement[slot] = pendingEncoder.createFence();
                    pendingEncoder.submit();
                    acquireCursor = (slot + 1) % acquireSemaphores.length;
                    // Submit and present each generated image before acquiring the next one.
                    VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
                    present.pWaitSemaphores(stack.longs(presentSem));
                    present.swapchainCount(1);
                    present.pSwapchains(stack.longs(swapchain));
                    present.pImageIndices(stack.ints(imageIndex));
                    lastPresentResult = KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
                    if(lastPresentResult==VK10.VK_SUCCESS || lastPresentResult==KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                        acceptedExtraPresentCalls++; presentedThisFrame++;
                    } else if(lastPresentResult!=KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                        throw new IllegalStateException("FG vkQueuePresentKHR result="+lastPresentResult);
                    }
                }
            } catch (Throwable t) {
                failed = true;
                CausticaMod.LOGGER.error("DLSS-FG present failed; frame generation disabled", t);
            } finally {
                pendingCount = 0;
            }
        }
        if (RtDlssFg.enabled() && CausticaConfig.Rt.FrameStats.ENABLED.value()
                && !dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()) {
            logPresentRate(presentedThisFrame);
        }
    }

    /**
     * Debug diagnostic (2026-07-01): tracks real vs generated {@code vkQueuePresentKHR} calls per second,
     * separate from MC's own fps counter — that counter only reflects simulated/rendered frames
     * (blitFromTexture calls), so it would NOT show an increase from FG's extra presents even if the display
     * is genuinely receiving more frames. Logged only when {@code caustica.rt.fg} is enabled.
     */
    private void logPresentRate(int generatedThisFrame) {
        realFramesInWindow++;
        generatedFramesInWindow += generatedThisFrame;
        long now = System.nanoTime();
        if (logWindowStartNs == 0L) {
            logWindowStartNs = now;
            return;
        }
        long elapsed = now - logWindowStartNs;
        if (elapsed < LOG_INTERVAL_NS) {
            return;
        }
        double seconds = elapsed / 1.0e9;
        double realFps = realFramesInWindow / seconds;
        double totalFps = (realFramesInWindow + generatedFramesInWindow) / seconds;
        CausticaMod.LOGGER.info(
                "[FG legacy-submit-rate] real={} extraPresentCalls={} realFps={} submittedPresentCallsPerSecond={} configuredMultiFrameCount={} "
                        + "interpOk={} interpFallbackDuplicate={}",
                realFramesInWindow, generatedFramesInWindow,
                String.format("%.1f", realFps), String.format("%.1f", totalFps),
                RtDlssFg.INSTANCE.effectiveMultiFrameCount(), interpOkInWindow, interpFallbackInWindow);
        logWindowStartNs = now;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
    }

    private void recordBlit(VulkanCommandEncoder enc, long srcImage, long dstImage, int copyW, int copyH,
            long acquireSem, long presentSem) {
        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Swapchain UNDEFINED -> TRANSFER_DST (stage/access values mirror MC's blitFromTexture).
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Make the source's prior writes visible to the blit read — the world render (duplicate path) or
            // the DLSSG evaluate that wrote the interp image earlier in this same submit (interp path).
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit final frame (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
        }
        // Register on MC's encoder (same order as MC's blitFromTexture): wait on the acquire, run the blit,
        // signal the image's present semaphore. MC's once-per-frame submit() flushes this in one
        // vkQueueSubmit, which is what actually signals presentSem (deferred-submit model).
        enc.waitSemaphore(acquireSem, 0L, 65536L);
        enc.execute(cmd);
        enc.signalSemaphore(presentSem, 0L, org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR);
    }

    private void ensureCapacity(VulkanDevice device, int semaphoreCount, int generatedCount) {
        if (pendingSources.length < generatedCount) {
            pendingSources = new long[generatedCount];
            pendingWidths = new int[generatedCount];
            pendingHeights = new int[generatedCount];
        }
        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        destroy(device);
        acquireSemaphores = new long[semaphoreCount];
        acquireRetirement = new GpuFence[semaphoreCount];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device.vkDevice(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = p.get(0);
            }
        }
        acquireCursor = 0;
    }

    /** Destroy the acquire-semaphore pool (device teardown). */
    public void destroy(VulkanDevice device) {
        for (GpuFence fence : acquireRetirement) {
            if (fence != null) {
                if (!fence.awaitCompletion(ACQUIRE_TIMEOUT_NS)) {
                    throw new IllegalStateException("FG semaphore retirement timed out during destroy");
                }
                fence.close();
            }
        }
        acquireRetirement = new GpuFence[0];
        for (long sem : acquireSemaphores) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
        pendingCount = 0;
    }
}
