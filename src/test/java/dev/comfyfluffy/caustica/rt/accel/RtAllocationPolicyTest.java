package dev.comfyfluffy.caustica.rt.accel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.lwjgl.util.vma.Vma;
class RtAllocationPolicyTest {
 @Test void largeFrameTargetsAreIndependentlyReleasableWithoutChangingSmallLuts() {
  assertEquals(0,RtAllocationPolicy.storageImageFlags(1,1));assertEquals(0,RtAllocationPolicy.storageImageFlags(511,512));
  for(int[] size:new int[][]{{512,512},{960,540},{1920,1080},{2560,1600},{Integer.MAX_VALUE,2}})
   assertEquals(Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT,RtAllocationPolicy.storageImageFlags(size[0],size[1]));
 }
 @Test void zeroOrNegativeExtentsCannotReachVulkanAllocation() { assertThrows(IllegalArgumentException.class,()->RtAllocationPolicy.storageImageFlags(0,720)); }
}
