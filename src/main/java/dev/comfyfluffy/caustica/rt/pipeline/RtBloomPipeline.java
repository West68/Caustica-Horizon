package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static org.lwjgl.vulkan.VK10.*;

/** Post-RR, pre-display scene-linear bloom; immutable per-pass sets, no per-frame allocation/update. */
public final class RtBloomPipeline {
    private static final int MAX_PASSES=ItrpBloomPlan.MAX_LEVELS*2-1;
    private final RtContext ctx;
    private long dsl,pool,layout,pipeline,sampler;
    private final long[] sets=new long[MAX_PASSES];
    private RtImage[] down=new RtImage[0],up=new RtImage[0];
    private RtImage input,exposure;
    private record Pass(int set,RtImage target,int mode,float lowMix) {}
    private Pass[] passes=new Pass[0];
    private RtBloomPipeline(RtContext ctx){this.ctx=ctx;}
    public static RtBloomPipeline create(RtContext ctx) {
        var result=new RtBloomPipeline(ctx);
        try {result.initialize();return result;}
        catch(Throwable failure){result.destroy();throw failure;}
    }
    private void initialize() {
        try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);
            var binds=VkDescriptorSetLayoutBinding.calloc(4,stack);
            for(int i=0;i<4;i++)binds.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                .descriptorType(i>=2?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            check(vkCreateDescriptorSetLayout(ctx.vk(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds),null,out),"bloom descriptor layout");dsl=out.get(0);
            var sizes=VkDescriptorPoolSize.calloc(2,stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_PASSES*2);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(MAX_PASSES*2);
            check(vkCreateDescriptorPool(ctx.vk(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_PASSES).pPoolSizes(sizes),null,out),"bloom descriptor pool");pool=out.get(0);
            var layouts=stack.mallocLong(MAX_PASSES);for(int i=0;i<MAX_PASSES;i++)layouts.put(i,dsl);
            var allocated=stack.mallocLong(MAX_PASSES);
            check(vkAllocateDescriptorSets(ctx.vk(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(layouts),allocated),"bloom descriptor sets");
            for(int i=0;i<MAX_PASSES;i++)sets[i]=allocated.get(i);
            var range=VkPushConstantRange.calloc(1,stack);range.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(12);
            check(vkCreatePipelineLayout(ctx.vk(),VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(dsl)).pPushConstantRanges(range),null,out),"bloom pipeline layout");layout=out.get(0);
            var sci=VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0);
            check(vkCreateSampler(ctx.vk(),sci,null,out),"bloom sampler");sampler=out.get(0);
            byte[] bytes;
            try(var in=RtBloomPipeline.class.getResourceAsStream("/caustica/rt/itrp_bloom.comp.spv")) {
                if(in==null)throw new IllegalStateException("Missing bloom shader");bytes=in.readAllBytes();
            }catch(IOException e){throw new IllegalStateException(e);}
            ByteBuffer code=MemoryUtil.memAlloc(bytes.length).put(bytes);code.flip();long module;
            try {check(vkCreateShaderModule(ctx.vk(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,out),"bloom shader");module=out.get(0);}
            finally {MemoryUtil.memFree(code);}
            try {
                var stage=VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                var info=VkComputePipelineCreateInfo.calloc(1,stack);info.get(0).sType$Default().stage(stage).layout(layout);
                check(vkCreateComputePipelines(ctx.vk(),VK_NULL_HANDLE,info,null,out),"bloom pipeline");pipeline=out.get(0);
            } finally {vkDestroyShaderModule(ctx.vk(),module,null);}
            RtDebugLabels.name(ctx,VK_OBJECT_TYPE_PIPELINE,pipeline,"ITRP scene-linear bloom");
        }
    }
    /** Caller has already quiesced the existing render targets for this resize/mode transition. */
    public void setInput(RtImage source,boolean enabled) {setInput(source,null,enabled);}
    /** {@code exposure}: the compositor's 1x1 R32F exposure image, read by the first level's highlight clamp. */
    public void setInput(RtImage source,RtImage exposure,boolean enabled) {
        releaseImages();
        if(!enabled)return;
        if(exposure==null)throw new IllegalArgumentException("bloom needs the exposure image");
        this.exposure=exposure;
        var sizes=ItrpBloomPlan.levels(source.width,source.height);
        down=new RtImage[sizes.size()];up=new RtImage[sizes.size()-1];
        var scheduled=new java.util.ArrayList<Pass>();
        try {
            for(int i=0;i<down.length;i++) {
                var s=sizes.get(i);
                down[i]=ctx.createStorageImage(s.width(),s.height(),VK_FORMAT_R16G16B16A16_SFLOAT,"ITRP bloom down "+i);
                if(i<up.length)up[i]=ctx.createStorageImage(s.width(),s.height(),VK_FORMAT_R16G16B16A16_SFLOAT,"ITRP bloom up "+i);
            }
            int set=0;
            for(int i=0;i<down.length;i++) {
                var high=i==0?source:down[i-1];bind(set,high,high,down[i]);
                scheduled.add(new Pass(set++,down[i],i==0?2:0,0)); // 2: first level, Karis-averaged
            }
            for(int i=up.length-1;i>=0;i--) {
                var low=i==up.length-1?down[i+1]:up[i+1];bind(set,down[i],low,up[i]);
                scheduled.add(new Pass(set++,up[i],1,ItrpBloomPlan.lowMix(i,down.length)));
            }
            input=source;passes=scheduled.toArray(Pass[]::new);
        } catch(Throwable failure){releaseImages();throw failure;}
    }
    private void bind(int index,RtImage high,RtImage low,RtImage target) {
        try(var stack=MemoryStack.stackPush()) {
            var writes=VkWriteDescriptorSet.calloc(4,stack);var images=new RtImage[]{high,low,target,exposure};
            for(int i=0;i<4;i++) {
                var image=VkDescriptorImageInfo.calloc(1,stack);
                image.get(0).imageView(images[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(i>=2?VK_NULL_HANDLE:sampler);
                writes.get(i).sType$Default().dstSet(sets[index]).dstBinding(i).descriptorCount(1)
                    .descriptorType(i>=2?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(image);
            }
            vkUpdateDescriptorSets(ctx.vk(),writes,null);
        }
    }
    public boolean ready(){return input!=null && passes.length>0;}
    public long outputView(){if(!ready())throw new IllegalStateException("Bloom not initialized");return up.length>0?up[0].view:down[0].view;}
    /** {@code clampHighlights}: the reference bloom clamp (SDR output outside the End). */
    public void record(VkCommandBuffer cmd,boolean clampHighlights) {
        if(!ready())return;
        try(var stack=MemoryStack.stackPush();var ignored=RtDebugLabels.scope(ctx,cmd,"ITRP bloom")) {
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
            var push=stack.malloc(12);
            for(var pass:passes) {
                vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,stack.longs(sets[pass.set]),null);
                push.putFloat(0,pass.lowMix).putInt(4,pass.mode).putInt(8,clampHighlights && pass==passes[0]?1:0);
                vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,push);
                vkCmdDispatch(cmd,(pass.target.width+7)/8,(pass.target.height+7)/8,1);
                // Includes RAW between levels, WAR before next-frame reuse and display sampling.
                var barrier=VkMemoryBarrier.calloc(1,stack);barrier.get(0).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,barrier,null,null);
            }
        }
    }
    private void releaseImages() {
        for(var image:down)if(image!=null)image.destroy();for(var image:up)if(image!=null)image.destroy();
        down=new RtImage[0];up=new RtImage[0];passes=new Pass[0];input=null;exposure=null;
    }
    /** Same device-idle boundary as display/guide destruction; never unload live descriptors. */
    public void destroy() {
        releaseImages();
        if(pipeline!=0)vkDestroyPipeline(ctx.vk(),pipeline,null);pipeline=0;
        if(layout!=0)vkDestroyPipelineLayout(ctx.vk(),layout,null);layout=0;
        if(pool!=0)vkDestroyDescriptorPool(ctx.vk(),pool,null);pool=0;
        if(dsl!=0)vkDestroyDescriptorSetLayout(ctx.vk(),dsl,null);dsl=0;
        if(sampler!=0)vkDestroySampler(ctx.vk(),sampler,null);sampler=0;
    }
}
