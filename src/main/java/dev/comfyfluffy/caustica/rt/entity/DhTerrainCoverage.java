package dev.comfyfluffy.caustica.rt.entity;
import java.nio.*;
import java.util.Arrays;
/** Exact published/known-empty near section ownership; no camera-centred exclusion guess. */
public final class DhTerrainCoverage {
 private int x,y,z,sx,sy,sz,stride;private int[] bits=new int[0];
 public void reset(int x,int y,int z,int sx,int sy,int sz){this.x=x;this.y=y;this.z=z;this.sx=sx;this.sy=sy;this.sz=sz;stride=(sy+31)/32;int n=Math.multiplyExact(Math.multiplyExact(sx,sz),stride);if(bits.length!=n)bits=new int[n];else Arrays.fill(bits,0);}
 public void add(int cx,int cy,int cz){int a=cx-x,b=cy-y,c=cz-z;if(a<0||b<0||c<0||a>=sx||b>=sy||c>=sz)return;bits[(a+c*sx)*stride+(b>>>5)]|=1<<(b&31);}
 public boolean contains(int cx,int cy,int cz){int a=cx-x,b=cy-y,c=cz-z;return a>=0&&b>=0&&c>=0&&a<sx&&b<sy&&c<sz&&(bits[(a+c*sx)*stride+(b>>>5)]&(1<<(b&31)))!=0;}
 public boolean covers(int bx,int bz,int width){int a=Math.floorDiv(bx,16),c=Math.floorDiv(bz,16),lastX=Math.floorDiv(bx+width-1,16),lastZ=Math.floorDiv(bz+width-1,16);if(a<x||c<z||lastX>=x+sx||lastZ>=z+sz)return false;
  for(int zz=c;zz<=lastZ;zz++)for(int xx=a;xx<=lastX;xx++)for(int yy=y;yy<y+sy;yy++)if(!contains(xx,yy,zz))return false;return true;}
 public int bytes(){return 32+bits.length*4;}
 public void write(ByteBuffer dst,int rbx,int rby,int rbz){dst.order(ByteOrder.nativeOrder());dst.putInt((x<<4)-rbx).putInt((y<<4)-rby).putInt((z<<4)-rbz).putInt(sx).putInt(sy).putInt(sz).putInt(stride).putInt(0);for(int b:bits)dst.putInt(b);}
}
