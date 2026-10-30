package dev.comfyfluffy.caustica.rt.material;
import java.util.*;
/** Remove only unused bottom rows. Alignment preserves every reachable semantic mip's texel grid. */
final class MaterialPageCrop {
 static int height(int pageSize,int occupiedHeight,int maxLod) {
  int alignment=1<<maxLod;
  return Math.min(pageSize,Math.max(alignment,(occupiedHeight+alignment-1)&-alignment));
 }
 static long bytes(int width,int height) {
  long bytes=0;
  for(int w=width,h=height;w>0;w/=2,h=Math.max(1,h/2))bytes+=(long)w*h*4;
  return bytes;
 }
 static List<byte[]> levels(List<byte[]> source,int width,int height) {
  var result=new ArrayList<byte[]>(source.size());
  for(int i=0;i<source.size();i++) {
   int size=Math.multiplyExact(Math.multiplyExact(Math.max(1,width>>i),Math.max(1,height>>i)),4);
   if(size>source.get(i).length)throw new IllegalArgumentException("Crop exceeds source mip");
   result.add(size==source.get(i).length?source.get(i):Arrays.copyOf(source.get(i),size));
  }
  return result;
 }
}
