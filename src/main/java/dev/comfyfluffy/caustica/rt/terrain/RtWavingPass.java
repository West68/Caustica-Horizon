package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.SectionGeom;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

/**
 * Per-frame waving of nearby foliage sections: one compute dispatch per section rewrites its BLAS input
 * positions (and per-vertex motion) from the rest copy, then each section BLAS is refit in place before the
 * TLAS build. Sections leaving the range, or all of them when waving is switched off, get one last pass
 * that returns them to rest with zero motion.
 */
public final class RtWavingPass {
    private static final String SHADER = "/caustica/rt/waving.comp.spv";
    private static final int PUSH_BYTES = 80;
    private static RtWavingPass instance;
    private static boolean failed;

    private final RtContext ctx;
    private final long layout, pipeline;
    private final long startNanos = System.nanoTime();
    private float prevTime = Float.NaN;
    private final List<RtAccel.PreparedBlas> refits = new ArrayList<>();

    private RtWavingPass(RtContext ctx, long layout, long pipeline) {
        this.ctx = ctx;
        this.layout = layout;
        this.pipeline = pipeline;
    }

    /**
     * Record this frame's waving work into {@code cmd}; must precede the TLAS build. Returns the number of
     * sections refit (0 records nothing).
     */
    public static int record(RtContext ctx, VkCommandBuffer cmd, RtTerrain terrain,
                             double camX, double camY, double camZ, float rain) {
        RtWaving.publish(camX, camY, camZ);
        if (terrain == null || failed) return 0;
        terrain.syncWaveData(camX, camY, camZ);
        if (instance == null || instance.ctx != ctx) {
            try {
                instance = create(ctx);
            } catch (Throwable t) {
                failed = true;
                CausticaMod.LOGGER.error("Waving foliage pass unavailable", t);
                return 0;
            }
        }
        return instance.recordFrame(cmd, terrain, camX, camY, camZ, rain);
    }

    private int recordFrame(VkCommandBuffer cmd, RtTerrain terrain, double camX, double camY, double camZ, float rain) {
        float speed = 1f;
        float time = (float) (((System.nanoTime() - startNanos) * 1e-9) % 3600.0) * speed;
        float before = Float.isNaN(prevTime) || time < prevTime ? time : prevTime;
        prevTime = time;
        boolean enabled = RtWaving.enabled();
        float strength = enabled ? CausticaConfig.Rt.Visuals.WAVING_PLANTS.value() : 0f;
        float range = RtWaving.range();
        refits.clear();
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "waving foliage")) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            boolean bound = false;
            for (SectionGeom g : terrain.waveSections()) {
                RtSectionTable.WaveGeom wave = g.wave;
                double distance = RtWaving.distanceToSection(g.sx >> 4, g.sy >> 4, g.sz >> 4);
                boolean inRange = enabled && distance < range;
                // Data sections reach range + 40 blocks, but only in-range ones are waved. The motion buffer
                // is allocated uninitialized and the hit shader reads it for every data section, so each one
                // gets a single rest pass (strength 0: rest positions, zero motion) when it first appears.
                // Garbage motion vectors made DLSS RR smear black blocks into the grass.
                if (!inRange && !g.waveActive && g.waveInitialized) continue;
                g.waveActive = inRange;
                g.waveInitialized = true;
                if (!bound) {
                    // Earlier frames' traces (hit shaders read the motion, rays the BLAS) and refits must finish
                    // before this frame overwrites positions/motion and refits the same BLAS in place.
                    barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
                                    | VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                            VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            VK_ACCESS_2_SHADER_WRITE_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
                    VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                    bound = true;
                }
                // Wrap the origin so world-space noise keeps float precision far from 0,0 (period 8192).
                float ox = (float) Math.floorMod(g.sx, 8192), oz = (float) Math.floorMod(g.sz, 8192);
                RtSectionBuilder.WaveBuffers b = wave.buffers();
                push.putLong(0, b.rest().deviceAddress).putLong(8, b.weights().deviceAddress)
                        .putLong(16, wave.positions().deviceAddress).putLong(24, b.disp().deviceAddress)
                        .putFloat(32, ox).putFloat(36, g.sy).putFloat(40, oz).putInt(44, b.vertexCount())
                        .putFloat(48, (float) (camX - g.sx)).putFloat(52, (float) (camY - g.sy)).putFloat(56, (float) (camZ - g.sz))
                        .putFloat(60, time).putFloat(64, before).putFloat(68, rain)
                        .putFloat(72, inRange ? strength : 0f).putFloat(76, range);
                VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (b.vertexCount() + 127) / 128, 1, 1);
                refits.add(wave.blas().terrainRefit(b.refitScratch()[0]));
            }
            if (refits.isEmpty()) return 0;
            // Positions → AS build input; motion → hit shaders.
            barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_SHADER_READ_BIT);
            RtAccel.recordBlasBuilds(ctx, cmd, refits);
            // Refit writes → the TLAS build reading these BLAS.
            barrier(cmd, stack, VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            int n = refits.size();
            refits.clear();
            return n;
        }
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier2KHR(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private static RtWavingPass create(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES));
            check(VK10.vkCreatePipelineLayout(ctx.vk(), layoutInfo, null, out), "vkCreatePipelineLayout(waving)");
            long layout = out.get(0);
            long module = loadModule(ctx, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, info, null, out), "vkCreateComputePipelines(waving)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "waving foliage");
            return new RtWavingPass(ctx, layout, pipeline);
        }
    }

    private static long loadModule(RtContext ctx, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RtWavingPass.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer output = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(ctx.vk(), info, null, output), "vkCreateShaderModule(waving)");
            return output.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
