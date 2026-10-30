package dev.comfyfluffy.caustica.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** UV-cell index over the block atlas; no per-fragment linear search over the whole resource pack. */
public final class SchematicUvIndex {
    public static final int GRID = 64, HEADER_WORDS = GRID * GRID * 2, RECORD_WORDS = 12;
    public record Tile(float u0,float v0,float u1,float v1,float u,float v,float du,float dv,int page,int lod,int features) {}
    public static ByteBuffer build(List<Tile> tiles) {
        List<List<Integer>> cells = new ArrayList<>(GRID*GRID);
        for(int c=0;c<GRID*GRID;c++) cells.add(new ArrayList<>());
        for(int i=0;i<tiles.size();i++) {
            Tile t=tiles.get(i);
            if(!(t.u1>t.u0 && t.v1>t.v0)) throw new IllegalArgumentException("Empty sprite rectangle");
            for(int y=cell(t.v0); y<=cell(Math.nextDown(t.v1)); y++)
                for(int x=cell(t.u0); x<=cell(Math.nextDown(t.u1)); x++) cells.get(y*GRID+x).add(i);
        }
        int words=HEADER_WORDS+tiles.size()*RECORD_WORDS;
        int indices=cells.stream().mapToInt(List::size).sum();
        ByteBuffer data=ByteBuffer.allocate(Math.multiplyExact(words+indices,4)).order(ByteOrder.nativeOrder());
        int cursor=words;
        for(var c:cells) { data.putInt(cursor).putInt(c.size()); cursor+=c.size(); }
        for(var t:tiles) data.putFloat(t.u0).putFloat(t.v0).putFloat(t.u1).putFloat(t.v1)
                .putFloat(t.u).putFloat(t.v).putFloat(t.du).putFloat(t.dv)
                .putInt(t.page).putInt(t.lod).putInt(t.features).putInt(0);
        for(var c:cells) for(int i:c) data.putInt(HEADER_WORDS+i*RECORD_WORDS);
        return data.flip();
    }
    public static int cell(float uv) { return Math.max(0,Math.min(GRID-1,(int)Math.floor(uv*GRID))); }
    private SchematicUvIndex() {}
}
