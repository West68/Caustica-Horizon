package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Compute pass that maps the display-res HDR RT image into an LDR image compatible with the main target. */
public final class RtDisplayPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Push constants: int hdrEnabled, float paperWhiteNits, float headroom, int itrpLook. */
    private static final int PUSH_BYTES = 224; // base16 + two float4 grades + sharpen (pad to 64) + night-sky mat4 + 2 float4 + previous view-projection

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final long bloomSampler;
    private boolean destroyed;

    private RtDisplayPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline, long bloomSampler) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.bloomSampler = bloomSampler;
    }

    public static RtDisplayPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(9, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            binds.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            // 5: render-res RT albedo guide (alpha marks camera sky), 6: Milky Way texture.
            binds.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            // 7/8: stable sky-mask history (read) and this frame's mask (written, then copied into history).
            binds.get(7).binding(7).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(8).binding(8).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(rt display)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "display descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(7);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt display)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "display descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(rt display)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "display descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(rt display)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "display pipeline layout");

            long module = loadModule(vk, stack, "display.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "display shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(rt display)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), "display compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            var samplerInfo=org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            int samplerResult=VK10.vkCreateSampler(vk,samplerInfo,null,p);
            if(samplerResult!=VK10.VK_SUCCESS) {
                VK10.vkDestroyPipeline(vk,pPipeline.get(0),null);VK10.vkDestroyPipelineLayout(vk,layout,null);
                VK10.vkDestroyDescriptorPool(vk,pool,null);VK10.vkDestroyDescriptorSetLayout(vk,dsl,null);
                check(samplerResult,"display bloom sampler");
            }
            long bloomSamplerHandle=p.get(0);
            var skySamplerInfo=org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(16f)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            check(VK10.vkCreateSampler(vk,skySamplerInfo,null,p),"display night sky sampler");
            RtDisplayPipeline created=new RtDisplayPipeline(ctx, dsl, pool, set, layout, pPipeline.get(0),bloomSamplerHandle);
            created.skySampler=p.get(0);
            return created;
        }
    }

    private long skySampler;
    public long skySampler() { return skySampler; }
    private long boundSkyView;

    /** The Milky Way texture once baked; until then binding 6 holds the bloom image as a valid stand-in. */
    public void setNightSky(long view) {
        if (view == 0L || view == boundSkyView) return;
        // One-time switch while earlier frames may still reference the set: wait for them first.
        VK10.vkDeviceWaitIdle(ctx.vk());
        boundSkyView = view;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).sampler(skySampler);
            var write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(descriptorSet).dstBinding(6).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    public void setImages(long outputImageView, long rtImageView, long exposureImageView, long hdrImageView, long bloomImageView,
                          long guideImageView, long coverHistoryView, long coverOutputView) {
        // Called only at a quiesced target/bloom generation change. Numeric VkImageView handles
        // may be recycled after destruction: equal handles do NOT preserve descriptor validity.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack);
            outputInfo.get(0).imageView(outputImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer rtInfo = VkDescriptorImageInfo.calloc(1, stack);
            rtInfo.get(0).imageView(rtImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
            exposureInfo.get(0).imageView(exposureImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrInfo.get(0).imageView(hdrImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(boundSkyView == 0L ? 9 : 8, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outputInfo);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(rtInfo);
            writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
            writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hdrInfo);
            var bloomInfo=VkDescriptorImageInfo.calloc(1,stack);
            bloomInfo.get(0).imageView(bloomImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).sampler(bloomSampler);
            writes.get(4).sType$Default().dstSet(descriptorSet).dstBinding(4).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(bloomInfo);
            var guideInfo=VkDescriptorImageInfo.calloc(1,stack);
            guideInfo.get(0).imageView(guideImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(5).sType$Default().dstSet(descriptorSet).dstBinding(5).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(guideInfo);
            var coverHistoryInfo=VkDescriptorImageInfo.calloc(1,stack);
            coverHistoryInfo.get(0).imageView(coverHistoryView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(6).sType$Default().dstSet(descriptorSet).dstBinding(7).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(coverHistoryInfo);
            var coverOutputInfo=VkDescriptorImageInfo.calloc(1,stack);
            coverOutputInfo.get(0).imageView(coverOutputView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(7).sType$Default().dstSet(descriptorSet).dstBinding(8).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(coverOutputInfo);
            if (boundSkyView == 0L) { // valid stand-in until the Milky Way is baked
                var standIn=VkDescriptorImageInfo.calloc(1,stack);
                standIn.get(0).imageView(bloomImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).sampler(bloomSampler);
                writes.get(8).sType$Default().dstSet(descriptorSet).dstBinding(6).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(standIn);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    /**
     * Run the display mapping. The SDR AgX output is always written (binding 0). When {@code hdrEnabled}, the
     * PQ-encoded HDR image (binding 3) is also written using the paper-white/headroom mapping.
     */
    public void dispatch(VkCommandBuffer cmd, int width, int height, boolean hdrEnabled, float paperWhiteNits, float headroom, boolean itrpLook, float bloomBlend,
                         org.joml.Matrix4fc invViewProj, float axisX, float axisY, float axisZ, float angle,
                         float nightStrength, float time, float rain, org.joml.Matrix4fc prevViewProj) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "display compute")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, hdrEnabled ? 1 : 0);
            push.putFloat(4, paperWhiteNits);
            push.putFloat(8, headroom);
            push.putInt(12, itrpLook ? 1 : 0);
            push.putFloat(16,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.BRIGHTNESS.value());
            push.putFloat(20,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.CONTRAST.value());
            push.putFloat(24,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.SATURATION.value());
            push.putFloat(28,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.TEMPERATURE.value());
            push.putFloat(32,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.GAMMA.value());
            push.putFloat(36,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.SHADOW_LIFT.value());
            push.putFloat(40,dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.HIGHLIGHT_SOFTNESS.value());
            push.putFloat(44,bloomBlend);
            var level=net.minecraft.client.Minecraft.getInstance().level;
            boolean overworld=level!=null && level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
            push.putFloat(48,itrpLook && overworld ? dev.comfyfluffy.caustica.CausticaConfig.Rt.Look.DETAIL_SHARPEN.value() : 0f);
            invViewProj.get(64, push);
            push.putFloat(128, axisX).putFloat(132, axisY).putFloat(136, axisZ).putFloat(140, angle);
            push.putFloat(144, overworld ? nightStrength : 0f).putFloat(148, time).putFloat(152, rain)
                .putFloat(156, boundSkyView != 0L ? 1f : 0f);
            prevViewProj.get(160, push);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroySampler(vk,bloomSampler,null);
        if(skySampler!=0L)VK10.vkDestroySampler(vk,skySampler,null);
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDisplayPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
