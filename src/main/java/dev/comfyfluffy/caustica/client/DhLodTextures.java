package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import java.lang.reflect.*;

/** Reuses DH's own color-ratio block atlas. No duplicate images or full-screen pass. */
public final class DhLodTextures {
 private static Object atlas;private static Method upload,wrapper,view;
 private static boolean unavailable;private static long lastView;private static int slot;
 public static int slot(){return slot;}
 public static void prepare(RtContext ctx) {
  slot=0;if(unavailable)return;
  try {
   if(atlas==null){var c=Class.forName("com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeBlockTextureAtlas");atlas=c.getField("INSTANCE").get(null);upload=c.getMethod("uploadPendingTiles");wrapper=c.getMethod("getTextureWrapper");view=wrapper.getReturnType().getMethod("getTextureView");}
   // The skipped DH draw normally does this upload. Run it before Caustica begins its RT commands.
   upload.invoke(atlas);Object w=wrapper.invoke(atlas);if(w==null)return;
   Object result=view.invoke(w);if(!(result instanceof VulkanGpuTextureView v)||v.isClosed())return;
   long handle=v.vkImageView();
   if(lastView!=0 && lastView!=handle){RenderSystem.getDevice().createCommandEncoder().submit();ctx.waitIdle();}
   slot=RtEntityTextures.INSTANCE.externalSlot("dh.block-ratios",handle);lastView=handle;
  }catch(ReflectiveOperationException|LinkageError e){unavailable=true;CausticaMod.LOGGER.warn("DH optional block-ratio atlas unavailable; retaining flat LOD materials",e);}
 }
 private DhLodTextures(){}
}
