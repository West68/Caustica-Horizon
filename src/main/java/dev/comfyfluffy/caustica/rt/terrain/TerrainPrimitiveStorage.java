package dev.comfyfluffy.caustica.rt.terrain;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Lossless, section-local interning of 48-byte triangle records. Never quantizes any field. */
public final class TerrainPrimitiveStorage {
    private static final int WORDS=12;
    private static final AtomicLong RAW=new AtomicLong(), STORED=new AtomicLong();
    public static long rawUploadedBytes() { return RAW.get(); }
    public static long storedUploadedBytes() { return STORED.get(); }
    public record Encoded(byte[] indexed, int byteSize) {
        public boolean usesIndex() { return indexed!=null; }
    }
    private static final class Key {
        final float[] source; final int words; int offset, hash;
        Key(float[] source,int offset,int hash,int words) { this.source=source;this.offset=offset;this.hash=hash;this.words=words; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Key key)||words!=key.words)return false;
            for(int i=0;i<words;i++)if(Float.floatToRawIntBits(source[offset+i])!=Float.floatToRawIntBits(key.source[key.offset+i]))return false;
            return true;
        }
    }
    public static Encoded encode(float[] records) { return encode(records,12,16); }
    /** Corner UV triples are shared exactly, rather than quantized or reconstructed from an atlas. */
    public static Encoded encodeUvs(float[] records) { return encode(records,6,8); }
    private static Encoded encode(float[] records,int words,int alignment) {
        int recordBytes=words*4;
        if(records.length%words!=0)throw new IllegalArgumentException("Incomplete terrain primitive");
        int rawSize=Math.multiplyExact(records.length,4), count=records.length/words;
        if(count<4 || Boolean.getBoolean("caustica.test.rawTerrainPrimitives"))return raw(rawSize);
        int indexBytes=Math.addExact(Math.multiplyExact(count,4),alignment-1)&~(alignment-1);
        int[] offsets=new int[count], unique=new int[count];int uniqueCount=0;
        Map<Key,Integer> dictionary=new HashMap<>();Key probe=new Key(records,0,0,words);
        for(int triangle=0;triangle<count;triangle++) {
            int offset=triangle*words,hash=1;
            for(int i=0;i<words;i++)hash=31*hash+Float.floatToRawIntBits(records[offset+i]);
            probe.offset=offset;probe.hash=hash;
            Integer found=dictionary.get(probe);
            if(found==null) {
                found=uniqueCount;unique[uniqueCount++]=offset;
                dictionary.put(new Key(records,offset,hash,words),found);
                // At this point even if all remaining records repeat, packing cannot save memory.
                if((long)indexBytes+(long)uniqueCount*recordBytes>=rawSize)return raw(rawSize);
            }
            offsets[triangle]=indexBytes+found*recordBytes;
        }
        int storedSize=Math.addExact(indexBytes,Math.multiplyExact(uniqueCount,recordBytes));
        ByteBuffer output=ByteBuffer.allocate(storedSize).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset:offsets)output.putInt(offset);
        output.position(indexBytes);
        for(int i=0;i<uniqueCount;i++)for(int word=0;word<words;word++)output.putInt(Float.floatToRawIntBits(records[unique[i]+word]));
        RAW.addAndGet(rawSize);STORED.addAndGet(storedSize);
        return new Encoded(output.array(),storedSize);
    }
    private static Encoded raw(int size) { RAW.addAndGet(size);STORED.addAndGet(size);return new Encoded(null,size); }
    private TerrainPrimitiveStorage() {}
}
