package dev.comfyfluffy.caustica.rt.material;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
final class PackedMaterialTileTest {
    @Test void roundTripAllChannelsAndOddDimensionMips() throws Exception {
        var random=new Random(42); float[][] data=new float[3][3*5*4];
        for(var channel:data)for(int i=0;i<channel.length;i++)channel[i]=random.nextFloat()*1.4f-.2f;
        var levels=RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(3,5,data[0],data[1],data[2]),3);
        var tile=new PackedMaterialTile(levels);var expected=new ByteArrayOutputStream();
        for(var level:levels)for(var channel:new float[][]{level.surface0(),level.normalAo(),level.surface1()})
            for(float value:channel)expected.write(RtMaterialTextureData.unorm8(value));
        try(var input=tile.open()){assertArrayEquals(expected.toByteArray(),input.readAllBytes());}
        assertEquals(tile,new PackedMaterialTile(levels));assertEquals(tile.hashCode(),new PackedMaterialTile(levels).hashCode());
        data[2][0]=data[2][0]>.5f?0:1;
        assertNotEquals(tile,new PackedMaterialTile(levels));
    }
    @Test void uniformTilesPreserveEveryChannelAndRequireAllMipsMatch() throws Exception {
        float[] a=new float[60];for(int i=0;i<a.length;i++)a[i]=(i%4)*.2f;
        var level=new RtMaterialTextureData.Level(3,5,a,a,a);var tile=new PackedMaterialTile(List.of(level));
        assertEquals(1,tile.width);assertEquals(1,tile.height);
        try(var input=tile.open()){byte[] data=input.readAllBytes();assertEquals(12,data.length);
            for(int i=0;i<data.length;i++)assertEquals((byte)RtMaterialTextureData.unorm8(a[i%4]),data[i]);}
        var b=Arrays.copyOf(a,a.length);b[4]=1;
        assertEquals(3,new PackedMaterialTile(List.of(level,new RtMaterialTextureData.Level(3,5,b,b,b))).width);
    }
    @Test void dimensionsAndMipCountArePartOfExactIdentity() {
        float[] data=new float[16]; data[5]=.5f;
        var square=new RtMaterialTextureData.Level(2,2,data,data,data);
        var strip=new RtMaterialTextureData.Level(1,4,data,data,data);
        assertNotEquals(new PackedMaterialTile(List.of(square)),new PackedMaterialTile(List.of(strip)));
        assertNotEquals(new PackedMaterialTile(List.of(square)),new PackedMaterialTile(List.of(square,square)));
    }
}
