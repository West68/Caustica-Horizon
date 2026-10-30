package dev.comfyfluffy.caustica.client;
import java.util.*;
/** Immutable lossless DH source quads, 24 bytes each instead of one record object plus a list reference.
 * List access remains available for diagnostics; upload and revision comparison use the packed lanes. */
public final class DhPackedSourceQuads extends AbstractList<DhLodCapture.LodQuad> implements RandomAccess {
 private final long[] lanes;
 private final int size;
 private DhPackedSourceQuads(long[] lanes,int size){this.lanes=lanes;this.size=size;}
 @Override public int size(){return size;}
 public long lane(int index,int lane){Objects.checkIndex(index,size);return lanes[index*3+lane];}
 @Override public DhLodCapture.LodQuad get(int index){
  long a=lane(index,0),b=lane(index,1),c=lane(index,2);
  return new DhLodCapture.LodQuad((short)a,(short)(a>>>16),(short)(a>>>32),(short)(a>>>48),
   (short)b,(int)(b>>>32),(short)(b>>>16),(byte)c,(byte)(c>>>8),(byte)(c>>>16),
   (int)((c>>>24)&255),(c&(1L<<32))!=0,(c&(1L<<33))!=0,(c&(1L<<34))!=0);
 }
 /** Skylight/blocklight are baked DH inputs, not Caustica geometry or traced lighting. */
 public boolean sameRtGeometry(DhPackedSourceQuads other){
  if(size!=other.size)return false;
  for(int i=0;i<size*3;i+=3)if(lanes[i]!=other.lanes[i]||lanes[i+1]!=other.lanes[i+1]
    ||(lanes[i+2]&~0xffff00L)!=(other.lanes[i+2]&~0xffff00L))return false;
  return true;
 }
 public static final class Builder {
  private long[] lanes;private int size;private boolean sealed;
  public Builder(int capacity){lanes=new long[Math.multiplyExact(capacity,3)];}
  public int size(){return size;}
  public void add(short x,short y,short z,short width,short height,int color,short tile,byte material,
    byte sky,byte block,int direction,boolean transparent,boolean water,boolean vegetation){
   if(sealed)throw new IllegalStateException("DH snapshot already published");
   if(size*3==lanes.length)lanes=Arrays.copyOf(lanes,Math.max(12,Math.addExact(lanes.length,Math.max(3,lanes.length/2+2)/3*3)));
   int o=size++*3;
   lanes[o]=Short.toUnsignedLong(x)|(Short.toUnsignedLong(y)<<16)|(Short.toUnsignedLong(z)<<32)|(Short.toUnsignedLong(width)<<48);
   lanes[o+1]=Short.toUnsignedLong(height)|(Short.toUnsignedLong(tile)<<16)|(Integer.toUnsignedLong(color)<<32);
   lanes[o+2]=Byte.toUnsignedLong(material)|(Byte.toUnsignedLong(sky)<<8)|(Byte.toUnsignedLong(block)<<16)
    |((long)direction<<24)|(transparent?1L<<32:0)|(water?1L<<33:0)|(vegetation?1L<<34:0);
  }
  public DhPackedSourceQuads build(){if(sealed)throw new IllegalStateException("DH snapshot already published");sealed=true;return new DhPackedSourceQuads(lanes,size);}
 }
}
