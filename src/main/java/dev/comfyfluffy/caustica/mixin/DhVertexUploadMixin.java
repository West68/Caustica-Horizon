package dev.comfyfluffy.caustica.mixin;
import dev.comfyfluffy.caustica.client.DhUploadSkip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** While Caustica shades DH geometry in hardware, DH's legacy raster draw is cancelled, so its per-section
 * vertex buffers are never read. Keep DH's bookkeeping (counts, uploaded flag) but do not allocate the GPU copy.
 * Only first uploads are skipped; an existing buffer keeps DH's original update path. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.common.render.blaze.wrappers.buffer.BlazeVertexBufferWrapper",remap=false)
public abstract class DhVertexUploadMixin {
 @Shadow(remap=false) public int vertexCount;
 @Shadow(remap=false) public int indexCount;
 @Shadow(remap=false) public boolean uploaded;
 @Shadow(remap=false) public com.mojang.blaze3d.buffers.GpuBuffer vertexGpuBuffer;
 @Inject(method="uploadVertexBuffer",at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void caustica$skipUnusedUpload(java.nio.ByteBuffer data,int count,CallbackInfo ci){
  if(vertexGpuBuffer!=null || !DhUploadSkip.active())return;
  vertexCount=count;
  indexCount=(int)(count*1.5d); // same bookkeeping as DH's own upload
  uploaded=true;
  DhUploadSkip.markSkipped();
  ci.cancel();
 }
}
