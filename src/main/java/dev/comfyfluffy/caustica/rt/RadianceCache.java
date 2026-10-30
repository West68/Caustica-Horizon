package dev.comfyfluffy.caustica.rt;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/**
 * GPU storage and Resolve pass of the world radiance cache (shaders/world/radiance_cache*.slang).
 * Per frame: a sparse Update trace records paths, Resolve blends them, and the main trace terminates
 * eligible indirect paths with the cached radiance. Everything lives in device-local buffers addressed
 * through 64-bit device addresses; nothing is read back.
 */
public final class RadianceCache {
    private static final String SHADER = "/caustica/rt/radiance_cache_resolve.comp.spv";
    private static final int PUSH_BYTES = 48;
    /** Main-pass marker for the sparse Update dispatch (must match RC_UPDATE_MARKER in world.rgen.slang). */
    public static final int UPDATE_MARKER = 0xCAC4E000;

    public final int capacity;
    public final RtBuffer entries, accum, resolved, meta;
    private final RtContext ctx;
    private final long pipelineLayout, pipeline;
    private boolean clearPending = true;
    private Object lastLevel;
    private boolean destroyed;

    private RadianceCache(RtContext ctx, int capacity, RtBuffer entries, RtBuffer accum, RtBuffer resolved,
            RtBuffer meta, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.capacity = capacity;
        this.entries = entries;
        this.accum = accum;
        this.resolved = resolved;
        this.meta = meta;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RadianceCache create(RtContext ctx) {
        int log2 = Math.max(16, Math.min(24, Integer.getInteger("caustica.rt.radianceCacheLog2", 20)));
        int capacity = 1 << log2;
        int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer entries = ctx.createBuffer(capacity * 4L, usage, false, "radiance cache entries");
        RtBuffer accum = ctx.createBuffer(capacity * 16L, usage, false, "radiance cache accumulation");
        RtBuffer resolved = ctx.createBuffer(capacity * 16L, usage, false, "radiance cache resolved");
        RtBuffer meta = ctx.createBuffer(capacity * 4L, usage, false, "radiance cache age");
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES));
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out), "vkCreatePipelineLayout(radiance cache)");
            long layout = out.get(0);
            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, out),
                    "vkCreateComputePipelines(radiance cache)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "radiance cache resolve");
            CausticaMod.LOGGER.info("Radiance cache: {} entries, {} MiB", capacity, (capacity * 40L) >> 20);
            return new RadianceCache(ctx, capacity, entries, accum, resolved, meta, layout, pipeline);
        }
    }

    /** Wipe the cache before the next Update when the world or dimension changed. */
    public void observeLevel(Object level) {
        if (level != lastLevel) {
            lastLevel = level;
            clearPending = true;
        }
    }

    public void requestClear() {
        clearPending = true;
    }

    /** Records the clear (if pending); call before the Update trace. Returns true if a barrier is needed. */
    public boolean recordClearIfPending(VkCommandBuffer cmd) {
        if (!clearPending) return false;
        clearPending = false;
        dispatch(cmd, true);
        return true;
    }

    /**
     * Called for every block change. Entries recorded before the geometry changed (e.g. sunlight in a pocket
     * that was just sealed) would otherwise fade over the full history; a nearby change shortens the history
     * for a moment so the cache re-converges in a few frames.
     */
    public static void noteBlockChange(int x, int y, int z) {
        changeX = x;
        changeY = y;
        changeZ = z;
        changeNanos = System.nanoTime();
    }

    private static volatile int changeX, changeY, changeZ;
    private static volatile long changeNanos = Long.MIN_VALUE / 2;
    private static final long FAST_ADAPT_NANOS = 1_500_000_000L;
    private static final double FAST_ADAPT_RADIUS = 64.0;
    private static final int FAST_ADAPT_FRAMES = 6;

    /** Blend this frame's Update samples into the resolved values; call between Update and main trace. */
    public void recordResolve(VkCommandBuffer cmd, double camX, double camY, double camZ) {
        int frames = Integer.getInteger("caustica.rt.radianceCacheFrames", 32);
        double dx = changeX + .5 - camX, dy = changeY + .5 - camY, dz = changeZ + .5 - camZ;
        if (System.nanoTime() - changeNanos < FAST_ADAPT_NANOS
                && dx * dx + dy * dy + dz * dz < FAST_ADAPT_RADIUS * FAST_ADAPT_RADIUS) {
            frames = Math.min(frames, FAST_ADAPT_FRAMES);
        }
        dispatch(cmd, false, frames);
    }

    private void dispatch(VkCommandBuffer cmd, boolean clear) {
        dispatch(cmd, clear, Integer.getInteger("caustica.rt.radianceCacheFrames", 32));
    }

    private void dispatch(VkCommandBuffer cmd, boolean clear, int maxFrames) {
        try (MemoryStack stack = MemoryStack.stackPush();
                RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, clear ? "radiance cache clear" : "radiance cache resolve")) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putLong(0, entries.deviceAddress).putLong(8, accum.deviceAddress)
                    .putLong(16, resolved.deviceAddress).putLong(24, meta.deviceAddress)
                    .putInt(32, capacity)
                    .putInt(36, maxFrames)
                    .putInt(40, Integer.getInteger("caustica.rt.radianceCacheStaleFrames", 240))
                    .putInt(44, clear ? 1 : 0);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (capacity + 255) / 256, 1, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        VK10.vkDestroyPipeline(ctx.vk(), pipeline, null);
        VK10.vkDestroyPipelineLayout(ctx.vk(), pipelineLayout, null);
        entries.destroy();
        accum.destroy();
        resolved.destroy();
        meta.destroy();
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RadianceCache.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer output = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, output), "vkCreateShaderModule(radiance cache)");
            return output.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
