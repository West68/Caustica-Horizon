package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.*;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import java.util.*;
/** Resource-epoch cache of bright-texel chromaticity, not a fixed warm tint for every emitting item. */
public final class HeldLightColors {
 private static final Map<String,float[]> CACHE=new HashMap<>();
 public static void clear(){CACHE.clear();}
 public static float[] of(ItemStack stack,float[] fallback) {
  String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  return CACHE.computeIfAbsent(id,key->sample(stack,fallback));
 }
 private static float[] sample(ItemStack stack,float[] fallback) {
  var mc=Minecraft.getInstance();List<Integer> pixels=new ArrayList<>();
  try {
   if(stack.getItem() instanceof BlockItem block) {
    var model=mc.getModelManager().getBlockStateModelSet().get(block.getBlock().defaultBlockState());
    List<BlockStateModelPart> parts=new ArrayList<>();model.collectParts(RandomSource.create(0),parts);
    Set<TextureAtlasSprite> sprites=Collections.newSetFromMap(new IdentityHashMap<>());
    for(var part:parts){for(var q:part.getQuads(null))sprites.add(q.materialInfo().sprite());
     for(var side:Direction.values())for(var q:part.getQuads(side))sprites.add(q.materialInfo().sprite());}
    for(var sprite:sprites) {
     if(sprite==null)continue;var c=sprite.contents();var im=((SpriteContentsAccessor)c).caustica$originalImage();
     if(im==null)continue;int step=Math.max(1,Math.max(c.width(),c.height())/128);
     for(int y=0;y<c.height();y+=step)for(int x=0;x<c.width();x+=step)pixels.add(im.getPixel(x,y));
    }
   } else {
    Identifier item=BuiltInRegistries.ITEM.getKey(stack.getItem());
    var resource=mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath(item.getNamespace(),"textures/item/"+item.getPath()+".png"));
    if(resource.isPresent())try(var in=resource.get().open()) {
     var im=javax.imageio.ImageIO.read(in);if(im!=null){int step=Math.max(1,Math.max(im.getWidth(),im.getHeight())/128);
      for(int y=0;y<im.getHeight();y+=step)for(int x=0;x<im.getWidth();x+=step)pixels.add(im.getRGB(x,y));}
    }
   }
  }catch(Exception ex){dev.comfyfluffy.caustica.CausticaMod.LOGGER.debug("Held emission color fallback for {}",stack.getItem(),ex);}
  return brightColor(pixels,fallback);
 }
 public static float[] brightColor(List<Integer> pixels,float[] fallback) {
  double[] sum=new double[3];
  for(int argb:pixels){double alpha=((argb>>>24)&255)/255.;double[] c={linear((argb>>>16)&255),linear((argb>>>8)&255),linear(argb&255)};
   double l=.2126*c[0]+.7152*c[1]+.0722*c[2],w=alpha*l*l*l*l;
   for(int i=0;i<3;i++)sum[i]+=c[i]*w;}
  double peak=Math.max(sum[0],Math.max(sum[1],sum[2]));
  return peak>1e-12?new float[]{(float)(sum[0]/peak),(float)(sum[1]/peak),(float)(sum[2]/peak)}:fallback.clone();
 }
 private static double linear(int b){double x=b/255.;return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4);}
 private HeldLightColors(){}
}
