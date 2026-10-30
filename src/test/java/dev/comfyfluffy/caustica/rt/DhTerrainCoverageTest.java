package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.rt.entity.DhTerrainCoverage;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhTerrainCoverageTest {
 @Test void missingFineSectionsKeepLodUntilTheyArePublished(){var g=new DhTerrainCoverage();g.reset(-2,-4,-2,5,24,5);assertFalse(g.covers(-16,-16,16));for(int y=-4;y<20;y++)g.add(-1,y,-1);assertTrue(g.covers(-16,-16,16));assertFalse(g.covers(-32,-16,32));g.reset(-2,-4,-2,5,24,5);assertFalse(g.contains(-1,0,-1));}
 @Test void rebasingAndTallDimensionsPreserveGpuBitIndices(){var g=new DhTerrainCoverage();g.reset(1874999,-4,-1875000,2,80,2);g.add(1875000,63,-1874999);assertTrue(g.contains(1875000,63,-1874999));assertFalse(g.contains(1875000,62,-1874999));var b=ByteBuffer.allocate(g.bytes()).order(ByteOrder.nativeOrder());g.write(b,30000000,64,-30000000);assertEquals(-16,b.getInt(0));assertEquals(-128,b.getInt(4));assertEquals(0,b.getInt(8));assertEquals(3,b.getInt(24));assertEquals(1<<3,b.getInt(32+((1+1*2)*3+2)*4));}
}
