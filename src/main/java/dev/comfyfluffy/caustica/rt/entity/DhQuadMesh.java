package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.client.DhLodCapture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** DH positions/widths are already block units. Y alone is relative to the dimension minimum. */
public final class DhQuadMesh {
    public static final int FLAG=2048;
    public static long bytes(int quads) {return primOffset(quads)+(long)quads*96;}
    public static long indexOffset(int n) {return (long)n*48;}
    public static long uvOffset(int n) {return indexOffset(n)+(long)n*24;}
    public static long primOffset(int n) {return (uvOffset(n)+(long)n*32+15)&~15L;}
    public static float[] vertices(DhLodCapture.LodQuad q) {
        float x=q.x(),y=q.y(),z=q.z(),u=q.widthEastWest(),v=q.widthNorthSouthOrHeight();
        // Face indices verified against EDhDirection.faceIndex and LodQuadBuilder.putQuad.
        return switch(q.direction()) {
            case 0,1 -> new float[]{x,y,z, x+u,y,z, x+u,y,z+v, x,y,z+v};
            case 2,3 -> new float[]{x,y,z, x+u,y,z, x+u,y+v,z, x,y+v,z};
            case 4,5 -> new float[]{x,y,z, x,y,z+u, x,y+v,z+u, x,y+v,z};
            default -> throw new IllegalArgumentException("DH face "+q.direction());
        };
    }
    public static void write(ByteBuffer target, DhLodCapture.SectionSnapshot s) {
        target.order(ByteOrder.nativeOrder());
        int n=s.quads().size(), pi=(int)primOffset(n), ii=(int)indexOffset(n), ui=(int)uvOffset(n);
        for(int k=0;k<n;k++) {
            var q=s.quads().get(k);float[] v=vertices(q);
            float nx=q.direction()==4?-1:q.direction()==5?1:0;
            float ny=q.direction()==0?-1:q.direction()==1?1:0;
            float nz=q.direction()==2?-1:q.direction()==3?1:0;
            // DH water boxes end at an integral block boundary; vanilla source fluid is 8/9 high.
            // Apply only to the surface edge, keeping side and top edges coincident.
            for(int j=0;j<4;j++) {
                float yy=v[j*3+1];
                if(q.water() && q.direction()!=0 && (q.direction()==1 || j>=2))yy-=1f/9f;
                target.putFloat(k*48+j*12,v[j*3]);target.putFloat(k*48+j*12+4,yy);target.putFloat(k*48+j*12+8,v[j*3+2]);
                target.putFloat(ui+k*32+j*8,(j==1||j==2)?1:0);target.putFloat(ui+k*32+j*8+4,j>=2?1:0);
            }
            int[] indices={0,1,2,0,2,3};
            for(int j=0;j<6;j++)target.putInt(ii+k*24+j*4,k*4+indices[j]);
            // Packed color is unlit ARGB; no DH directional AO/sky/block-light baking is carried into RT.
            float r=((q.color()>>>16)&255)/255f,g=((q.color()>>>8)&255)/255f,b=(q.color()&255)/255f;
            for(int t=0;t<2;t++) {
                int o=pi+k*96+t*48;
                target.putFloat(o,nx);target.putFloat(o+4,ny);target.putFloat(o+8,nz);target.putFloat(o+12,0);
                target.putFloat(o+16,r);target.putFloat(o+20,g);target.putFloat(o+24,b);target.putFloat(o+28,0);
                target.putInt(o+32,0);target.putInt(o+36,FLAG|(q.water()?4096:0)|(q.transparent()&&!q.water()?8192:0));
                target.putInt(o+40,q.direction());target.putInt(o+44,Short.toUnsignedInt(q.textureTileId()));
            }
        }
    }
    private DhQuadMesh() {}
}
