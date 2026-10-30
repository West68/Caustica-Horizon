package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
/** Cheap dimension/biome fog target; smooth transitions do not rebuild any GPU resource. */
public final class RtDimensionEnvironment {
    private static ClientLevel world;private static long previous;
    private static float r,g,b,d;
    public static Float4 fog(ClientLevel level,BlockPos camera,int dimension) {
        float nr=.025f,ng=.012f,nb=.04f,nd=.004f;
        if(dimension==1 && level!=null) {
            var biome=level.getBiome(camera);
            nr=.990f;ng=.170f;nb=.005f;nd=.010f;
            if(biome.is(Biomes.SOUL_SAND_VALLEY)){nr=.030f;ng=.090f;nb=.160f;nd=.008f;}
            else if(biome.is(Biomes.WARPED_FOREST)){nr=.045f;ng=.155f;nb=.200f;nd=.009f;}
            else if(biome.is(Biomes.CRIMSON_FOREST)){nr=.200f;ng=.020f;nb=.005f;nd=.010f;}
            else if(biome.is(Biomes.BASALT_DELTAS)){nr=.500f;ng=.500f;nb=.500f;nd=.012f;}
        }
        if(dimension==1){nr*=.0025f;ng*=.0025f;nb*=.0025f;}
        long now=System.nanoTime();float blend=world!=level||previous==0?1:(float)(-Math.expm1(-Math.min((now-previous)/1e9,1)/.4));
        world=level;previous=now;r+=(nr-r)*blend;g+=(ng-g)*blend;b+=(nb-b)*blend;d+=(nd-d)*blend;
        return new Float4(r,g,b,d);
    }
    private static ClientLevel lightWorld;private static long lightPrevious;
    private static float lr,lg,lb;
    /** ITRP NetherLighting(): per-biome ambient tint (x 8e-5), smoothed like the fog. Zero outside the Nether. */
    public static Float4 netherLighting(ClientLevel level,BlockPos camera,int dimension) {
        float nr=0,ng=0,nb=0;
        if(dimension==1 && level!=null) {
            var biome=level.getBiome(camera);
            nr=1.77f*.8f;ng=.85f*.8f;nb=.27f*.8f;
            if(biome.is(Biomes.SOUL_SAND_VALLEY)){nr=.92f*.6f;ng=.99f*.6f;nb=1.29f*.6f;}
            else if(biome.is(Biomes.CRIMSON_FOREST)){nr=1.30f*.7f;ng=.95f*.7f;nb=.65f*.7f;}
            else if(biome.is(Biomes.WARPED_FOREST)){nr=.97f*.7f;ng=.99f*.7f;nb=1.18f*.7f;}
            else if(biome.is(Biomes.BASALT_DELTAS)){nr=1.07f*1.2f;ng=.98f*1.2f;nb=.99f*1.2f;}
            nr*=8e-5f;ng*=8e-5f;nb*=8e-5f;
        }
        long now=System.nanoTime();float blend=lightWorld!=level||lightPrevious==0?1:(float)(-Math.expm1(-Math.min((now-lightPrevious)/1e9,1)/.4));
        lightWorld=level;lightPrevious=now;lr+=(nr-lr)*blend;lg+=(ng-lg)*blend;lb+=(nb-lb)*blend;
        return new Float4(lr,lg,lb,0);
    }
    private RtDimensionEnvironment(){}
}
