package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.client.DhLodCapture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exact DH rectangles: VkAabbPositionsKHR followed by unlit ARGB and face/material bits.
 * One 32-byte record is both BLAS input and hit-shader data; no duplicate triangle/UV arrays. */
public final class DhPackedQuads {
    public static final int STRIDE=32;
    public static long bytes(int quads){return Math.multiplyExact((long)quads,STRIDE);}
    public static void write(ByteBuffer target,DhLodCapture.SectionSnapshot source){
        target.order(ByteOrder.nativeOrder());
        if(source.quads() instanceof dev.comfyfluffy.caustica.client.DhPackedSourceQuads packed){
            for(int i=0;i<packed.size();i++){
                long a=packed.lane(i,0),b=packed.lane(i,1),c=packed.lane(i,2);
                writeQuad(target,i*STRIDE,(short)a,(short)(a>>>16),(short)(a>>>32),(short)(a>>>48),(short)b,
                    (int)(b>>>32),(int)((b>>>16)&65535),(int)((c>>>24)&255),(c&(1L<<33))!=0,(c&(1L<<32))!=0,DhLodCapture.materialClass((int)(c&255)),(int)((c>>>16)&15));
            }
        }else for(int i=0;i<source.quads().size();i++){
            var q=source.quads().get(i);
            writeQuad(target,i*STRIDE,q.x(),q.y(),q.z(),q.widthEastWest(),q.widthNorthSouthOrHeight(),q.color(),
                Short.toUnsignedInt(q.textureTileId()),q.direction(),q.water(),q.transparent(),DhLodCapture.materialClass(q.materialId()),q.blockLight()&15);
        }
    }
    private static void writeQuad(ByteBuffer target,int offset,float x,float y,float z,float u,float v,int color,int tile,int d,boolean water,boolean transparent,int materialClass,int blockLight){
        if(d<0||d>5)throw new IllegalArgumentException("DH face "+d);
        float mx=x,my=y,mz=z;
        if(d<2){mx+=u;mz+=v;}else if(d<4){mx+=u;my+=v;}else{mz+=u;my+=v;}
        if(water&&d!=0){my-=1f/9f;if(d==1)y=my;}
        // Conservative boxes bound exactly one face, never a solid volume.
        if(d<2){float plane=y,epsilon=Math.max(1f/4096f,Math.ulp(plane));y=plane-epsilon;my=plane+epsilon;}
        else if(d<4){float plane=z,epsilon=Math.max(1f/4096f,Math.ulp(plane));z=plane-epsilon;mz=plane+epsilon;}
        else{float plane=x,epsilon=Math.max(1f/4096f,Math.ulp(plane));x=plane-epsilon;mx=plane+epsilon;}
        target.putFloat(offset,x).putFloat(offset+4,y).putFloat(offset+8,z);
        target.putFloat(offset+12,mx).putFloat(offset+16,my).putFloat(offset+20,mz);
        target.putInt(offset+24,color);
        target.putInt(offset+28,tile|(d<<16)|(water?1<<19:0)|(transparent&&!water?1<<20:0)|(materialClass&3)<<21|(blockLight&15)<<23);
    }
    private DhPackedQuads(){}
}
