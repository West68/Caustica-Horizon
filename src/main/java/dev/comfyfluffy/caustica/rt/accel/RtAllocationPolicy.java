package dev.comfyfluffy.caustica.rt.accel;
import org.lwjgl.util.vma.Vma;
/** Large resize-dependent images must release their VkDeviceMemory on destruction. */
public final class RtAllocationPolicy {
 public static int storageImageFlags(int width,int height) {
  if(width<=0||height<=0)throw new IllegalArgumentException("Invalid image extent");
  // Keep tiny LUTs/histograms pooled; large frame targets must not strand mixed AS/image blocks.
  return (long)width*height>=512L*512 ? Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT : 0;
 }
 private RtAllocationPolicy(){}
}
