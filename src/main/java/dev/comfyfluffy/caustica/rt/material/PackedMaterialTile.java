package dev.comfyfluffy.caustica.rt.material;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Exact content key for the RGBA8 texels actually uploaded, not a lossy texture compressor.
 * The compressed bytes only live on the CPU during a reload; GPU precision and mips are unchanged. */
final class PackedMaterialTile {
    final int width, height, levels;
    private final byte[] bytes;
    private final int hash;
    PackedMaterialTile(List<RtMaterialTextureData.Level> source) {
        if(source.isEmpty()) throw new IllegalArgumentException("Empty material tile");
        boolean uniform=isUniform(source);
        width=uniform?1:source.getFirst().width(); height=uniform?1:source.getFirst().height(); levels=source.size();
        var out=new ByteArrayOutputStream();
        try(var compressed=new DeflaterOutputStream(out)) {
            for(var level:source) for(float[] channel:new float[][]{level.surface0(),level.normalAo(),level.surface1()}) {
                byte[] data=new byte[uniform?4:channel.length];
                for(int i=0;i<data.length;i++) data[i]=(byte)RtMaterialTextureData.unorm8(channel[i]);
                compressed.write(data);
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
        bytes=out.toByteArray(); hash=31*(31*(31*width+height)+levels)+Arrays.hashCode(bytes);
    }
    private static boolean isUniform(List<RtMaterialTextureData.Level> levels) {
        var base=levels.getFirst();float[][] first={base.surface0(),base.normalAo(),base.surface1()};
        for(var level:levels) {
            float[][] channels={level.surface0(),level.normalAo(),level.surface1()};
            for(int c=0;c<3;c++) for(int i=0;i<channels[c].length;i++)
                if(RtMaterialTextureData.unorm8(channels[c][i])!=RtMaterialTextureData.unorm8(first[c][i%4]))return false;
        }
        return true;
    }
    InputStream open() { return new InflaterInputStream(new ByteArrayInputStream(bytes)); }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return other instanceof PackedMaterialTile tile && width==tile.width && height==tile.height
            && levels==tile.levels && Arrays.equals(bytes,tile.bytes);
    }
}
