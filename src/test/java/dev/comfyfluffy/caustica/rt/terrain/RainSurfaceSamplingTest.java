package dev.comfyfluffy.caustica.rt.terrain;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class RainSurfaceSamplingTest {
    @Test void adjacentBlocksAndSectionEdgesShareContinuousExposure() {
        BlockAndTintGetter view=(BlockAndTintGetter)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{BlockAndTintGetter.class},(proxy,method,args)-> {
                if(method.getName().equals("getBrightness"))return ((BlockPos)args[1]).getX()<16?10:15;
                throw new UnsupportedOperationException(method.getName());
            });
        var cursor=new BlockPos.MutableBlockPos();
        int left=RtTerrainMesher.rainSkyAt(view,new BlockPos(15,63,0),1,0,cursor);
        int right=RtTerrainMesher.rainSkyAt(view,new BlockPos(16,63,0),0,0,cursor);
        assertEquals(left,right);assertTrue(left>170 && left<255);
        int previous=0;
        for(int i=0;i<=100;i++) {
            int current=RtTerrainMesher.rainSkyAt(view,new BlockPos(15,63,0),i/100f,0,cursor);
            assertTrue(current>=previous);if(i>0)assertTrue(current-previous<=2);
            previous=current;
        }
    }
}
