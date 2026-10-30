package dev.comfyfluffy.caustica.client;

import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicUvIndexTest {
    private static SchematicUvIndex.Tile tile(float a,float b,float c,float d,int page) {
        return new SchematicUvIndex.Tile(a,b,c,d,.1f,.2f,.3f,.4f,page,3,3);
    }
    private static int lookup(ByteBuffer data,float u,float v) {
        int h=(SchematicUvIndex.cell(v)*64+SchematicUvIndex.cell(u))*8;
        int offset=data.getInt(h),count=data.getInt(h+4);
        for(int i=0;i<count;i++) {
            int r=data.getInt((offset+i)*4)*4;
            if(u>=data.getFloat(r) && v>=data.getFloat(r+4) && u<data.getFloat(r+8) && v<data.getFloat(r+12))
                return data.getInt(r+32);
        }
        return -1;
    }
    @Test void adjacentTilesNeverSampleNeighbourAtSharedEdges() {
        var data=SchematicUvIndex.build(List.of(tile(0,0,.5f,1,1),tile(.5f,0,1,1,7)));
        for(int y=0;y<100;y++) {
            float v=(y+.5f)/100;
            assertEquals(1,lookup(data,Math.nextDown(.5f),v));
            assertEquals(7,lookup(data,.5f,v));
            assertEquals(7,lookup(data,Math.nextDown(1f),v));
        }
    }
    @Test void manySpritesInOneCellResolveTheirExactRectangle() {
        var tiles=new ArrayList<SchematicUvIndex.Tile>();
        for(int i=0;i<24;i++) tiles.add(tile(i/4096f,.2f,(i+1)/4096f,.21f,i%8));
        var data=SchematicUvIndex.build(tiles);
        for(int i=0;i<24;i++)assertEquals(i%8,lookup(data,(i+.5f)/4096f,.205f));
        assertEquals(-1,lookup(data,.5f,.205f));
    }
    @Test void emptyAtlasAndInvalidRectanglesAreSafe() {
        assertEquals(-1,lookup(SchematicUvIndex.build(List.of()),.5f,.5f));
        assertThrows(IllegalArgumentException.class,()->SchematicUvIndex.build(List.of(tile(1,0,0,1,0))));
    }
    @Test void canonicalCoordinatesAndLodRemainUnchanged() {
        var data=SchematicUvIndex.build(List.of(tile(.2f,.3f,.4f,.5f,6)));
        int r=SchematicUvIndex.HEADER_WORDS*4;
        assertEquals(.1f,data.getFloat(r+16));assertEquals(.4f,data.getFloat(r+28));
        assertEquals(6,data.getInt(r+32));assertEquals(3,data.getInt(r+36));assertEquals(3,data.getInt(r+40));
    }
}
