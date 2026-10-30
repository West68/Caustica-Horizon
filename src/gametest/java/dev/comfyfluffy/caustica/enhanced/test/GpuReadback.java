package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.accel.*;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import org.lwjgl.vulkan.*;
import org.lwjgl.system.*;
/** Test-only transfers. These synchronize only outside FPS windows. */
public final class GpuReadback {
 public static void image(RtImage image,int bytes,Path out)throws Exception{
  var ctx=RtContext.currentOrNull();ctx.waitIdle();long size=(long)image.width*image.height*bytes;
  var buffer=ctx.createBuffer(size,VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,true,"test image readback");
  try{
   ctx.submitSync(cmd->{try(var stack=MemoryStack.stackPush()){
    com.mojang.blaze3d.vulkan.VulkanCommandEncoder.memoryBarrier(cmd,stack);
    var copy=VkBufferImageCopy.calloc(1,stack);copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.get(0).imageExtent().set(image.width,image.height,1);
    VK10.vkCmdCopyImageToBuffer(cmd,image.image,VK10.VK_IMAGE_LAYOUT_GENERAL,buffer.handle,copy);
   }});
   org.lwjgl.util.vma.Vma.vmaInvalidateAllocation(ctx.vma(),buffer.allocation,0,size);
   byte[] data=new byte[Math.toIntExact(size)];MemoryUtil.memByteBuffer(buffer.mapped,data.length).get(data);Files.write(out,data);
   Files.writeString(out.resolveSibling(out.getFileName()+".json"),"{\"width\":"+image.width+",\"height\":"+image.height+",\"bytesPerPixel\":"+bytes+"}");
  }finally{buffer.destroy();}
 }
 public static void frame(Path out,String name)throws Exception{
  var field=RtComposite.class.getDeclaredField("output");field.setAccessible(true);image((RtImage)field.get(RtComposite.INSTANCE),8,out.resolve(name+"-hdr.bin"));
  field=RtComposite.class.getDeclaredField("rrOutput");field.setAccessible(true);image((RtImage)field.get(RtComposite.INSTANCE),8,out.resolve(name+"-rr-hdr.bin"));
  field=RtComposite.class.getDeclaredField("displayImage");field.setAccessible(true);image((RtImage)field.get(RtComposite.INSTANCE),4,out.resolve(name+"-display.bin"));
  field=RtComposite.class.getDeclaredField("exposure");field.setAccessible(true);var exposure=(dev.comfyfluffy.caustica.rt.pipeline.RtExposure)field.get(RtComposite.INSTANCE);image(exposure.image(),4,out.resolve(name+"-exposure.bin"));
 }
 public static void guides(Path out,String name)throws Exception {
  frame(out,name);
  var metadata=new com.google.gson.JsonObject();
  for(String fieldName:new String[]{"gNormal","gDepth","gAlbedo","gSpecAlbedo","gMotion","gSpecMotion"}) {
   var field=RtComposite.class.getDeclaredField(fieldName);field.setAccessible(true);var image=(RtImage)field.get(RtComposite.INSTANCE);
   int bytes=fieldName.equals("gDepth")||fieldName.equals("gMotion")||fieldName.equals("gSpecMotion")?4:8;
   image(image,bytes,out.resolve(name+"-"+fieldName+".bin"));
   var desc=new com.google.gson.JsonObject();desc.addProperty("width",image.width);desc.addProperty("height",image.height);desc.addProperty("format",image.format);metadata.add(fieldName,desc);
  }
  Files.writeString(out.resolve(name+"-guides.json"),metadata.toString());
 }
 public static void palette(Path out)throws Exception{
  var ctx=RtContext.currentOrNull();ctx.waitIdle();
  float[][] values={{0,0,0},{.000001f,.000001f,.000001f},{.001f,.001f,.001f},{.018f,.018f,.018f},{.18f,.18f,.18f},{.5f,.5f,.5f},{1,1,1},{2,2,2},{5,5,5},{20,20,20},{100,100,100},{1000,1000,1000},{65504,65504,65504},{.8f,.2f,.05f},{.05f,.2f,1},{-1,.2f,.5f}};
  int n=values.length;
  var input=ctx.createStorageImage(n,1,VK10.VK_FORMAT_R16G16B16A16_SFLOAT,"palette input");var ldr=ctx.createStorageImage(n,1,VK10.VK_FORMAT_R8G8B8A8_UNORM,"palette output");
  var hdr=ctx.createStorageImage(n,1,VK10.VK_FORMAT_R16G16B16A16_SFLOAT,"palette PQ");var exposure=ctx.createStorageImage(1,1,VK10.VK_FORMAT_R32_SFLOAT,"palette exposure");
  var staging=ctx.createBuffer(n*8L,VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,true,"palette upload");var pipeline=RtDisplayPipeline.create(ctx);
  try{
   var data=MemoryUtil.memShortBuffer(staging.mapped,n*4);for(var v:values){for(float x:v)data.put(Float.floatToFloat16(x));data.put(Float.floatToFloat16(1));}staging.flush();
   pipeline.setImages(ldr.view,input.view,exposure.view,hdr.view,input.view);
   ctx.submitSync(cmd->{try(var stack=MemoryStack.stackPush()){
    var copy=VkBufferImageCopy.calloc(1,stack);copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.get(0).imageExtent().set(n,1,1);VK10.vkCmdCopyBufferToImage(cmd,staging.handle,input.image,VK10.VK_IMAGE_LAYOUT_GENERAL,copy);
    var clear=VkClearColorValue.calloc(stack).float32(0,1f);var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);VK10.vkCmdClearColorImage(cmd,exposure.image,VK10.VK_IMAGE_LAYOUT_GENERAL,clear,range);
    com.mojang.blaze3d.vulkan.VulkanCommandEncoder.memoryBarrier(cmd,stack);pipeline.dispatch(cmd,n,1,true,200f,5f,true,0f);
   }});image(ldr,4,out.resolve("palette-sdr.bin"));image(hdr,8,out.resolve("palette-pq.bin"));
   Files.writeString(out.resolve("palette.json"),java.util.Arrays.deepToString(values));
  }finally{ctx.waitIdle();pipeline.destroy();staging.destroy();exposure.destroy();hdr.destroy();ldr.destroy();input.destroy();}
 }
 private GpuReadback(){}
}
