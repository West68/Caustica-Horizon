package dev.comfyfluffy.caustica.rt.material;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialPageCropTest {
 @Test void preservesEveryReachableMipAndNormalizedSamplingCoordinates() {
  int width=128;var random=new Random(9281);
  var square=new ArrayList<byte[]>();
  for(int w=width;w>0;w/=2){byte[] a=new byte[w*w*4];random.nextBytes(a);square.add(a);}
  for(int used:new int[]{17,24,31,52,99,127,128}) {
   int height=MaterialPageCrop.height(width,used,3);
   assertTrue(height>=used && height<=width && height%8==0);
   var crop=MaterialPageCrop.levels(square,width,height);
   assertEquals(MaterialPageCrop.bytes(width,height),crop.stream().mapToLong(a->a.length).sum());
   assertTrue(MaterialPageCrop.bytes(width,height)<=MaterialPageCrop.bytes(width,width));
   for(int mip=0;mip<=3;mip++) {
    assertArrayEquals(Arrays.copyOf(square.get(mip),crop.get(mip).length),crop.get(mip));
    // Same texel coordinate (and hence nearest/bilinear result) after remapping page V.
    for(int j=0;j<100;j++) {
     double pixelY=random.nextDouble()*used;
     assertEquals(pixelY/width*(width>>mip),pixelY/height*(height>>mip),1e-12);
    }
   }
  }
 }
 @Test void fullPageRemainsByteIdenticalAndTooLargeCropIsRejected() {
  byte[] pixels=new byte[32*32*4];
  assertSame(pixels,MaterialPageCrop.levels(List.of(pixels),32,32).getFirst());
  assertThrows(IllegalArgumentException.class,()->MaterialPageCrop.levels(List.of(pixels),32,64));
 }
 @Test void realPageStorageAllocatesRectanglesAndBlitsAtTheBottomWithoutSquareTemporaries() throws Exception {
  Class<?> type=Class.forName("dev.comfyfluffy.caustica.rt.material.RtBlockMaterials$PagePixels");
  var ctor=type.getDeclaredConstructor(int.class,int.class,int.class);ctor.setAccessible(true);
  Object page=ctor.newInstance(128,24,8);
  var field=type.getDeclaredField("normalAo");field.setAccessible(true);
  @SuppressWarnings("unchecked") var levels=(List<byte[]>)field.get(page);
  for(int mip=0;mip<8;mip++)assertEquals(Math.max(1,128>>mip)*Math.max(1,24>>mip)*4,levels.get(mip).length);
  var blit=type.getDeclaredMethod("blitPacked",byte[].class,int.class,int.class,int.class,int.class,int.class,int.class,byte[].class);
  blit.setAccessible(true);
  byte[] source=new byte[16*16*4];new Random(15).nextBytes(source);
  byte[] target=levels.getFirst();
  blit.invoke(null,target,128,8,8,8,16,16,source);
  for(int y=0;y<16;y++)for(int x=0;x<16;x++)for(int c=0;c<4;c++)
   assertEquals(source[(y*16+x)*4+c],target[((y+8)*128+x+8)*4+c]);
 }

}
