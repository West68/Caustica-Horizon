package dev.comfyfluffy.caustica.rt.entity;
import dev.comfyfluffy.caustica.client.DhLodCapture;
import java.util.*;
/** Budgeted far-field surface envelope built from real DH top faces. No screen-space reconstruction.
 * Preserves independent terrain/water layers, closes stepped sides and leaves unknown data empty.
 * Near Caustica geometry is untouched. Caves and thin distant features use the simplified LOD envelope. */
public final class DhLodProxy {
 public static DhLodCapture.SectionSnapshot make(DhLodCapture.SectionSnapshot source,int resolution) {
  int width=1<<source.detailLevel(),step=width/resolution;
  if(step<1||width>32767)return source;
  var solid=new DhLodCapture.LodQuad[resolution*resolution];
  var water=new DhLodCapture.LodQuad[solid.length];
  var ground=new DhLodCapture.LodQuad[solid.length];
  var plants=new DhLodCapture.LodQuad[solid.length];
  for(var q:source.quads()) {
   if(q.direction()!=1||q.widthEastWest()<=0||q.widthNorthSouthOrHeight()<=0)continue;
   var cells=q.water()?water:solid;
   int minX=Math.max(0,(int)Math.ceil((q.x()-step*.5)/step));
   int maxX=Math.min(resolution-1,(int)Math.floor((q.x()+q.widthEastWest()-step*.5-1e-4)/step));
   int minZ=Math.max(0,(int)Math.ceil((q.z()-step*.5)/step));
   int maxZ=Math.min(resolution-1,(int)Math.floor((q.z()+q.widthNorthSouthOrHeight()-step*.5-1e-4)/step));
   for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
    int i=x+z*resolution;if(cells[i]==null||q.y()>cells[i].y())cells[i]=q;
    if(!q.water()&&!q.vegetation()&&(ground[i]==null||q.y()>ground[i].y()))ground[i]=q;
   }
  }
  // Submerged kelp/leaves are a separate layer, never an opaque seabed extruded down to minY.
  for(int i=0;i<solid.length;i++)if(water[i]!=null && solid[i]!=null && solid[i].vegetation()
      && solid[i].y()<=water[i].y() && ground[i]!=null){plants[i]=solid[i];solid[i]=ground[i];}
  var out=new ArrayList<DhLodCapture.LodQuad>();int wet=0;
  for(int z=0;z<resolution;z++)for(int x=0;x<resolution;x++) {
   int i=x+z*resolution;var q=solid[i];
   if(q!=null){out.add(quad(q,x*step,q.y(),z*step,step,step,1,false));
    for(int d=2;d<6;d++) {
     int xx=x+(d==4?-1:d==5?1:0),zz=z+(d==2?-1:d==3?1:0);
     // An unknown neighbour is not a cliff to the dimension minimum. Preserve the real source
     // boundary faces below instead of manufacturing underwater curtains at every section edge.
     if(xx<0||zz<0||xx>=resolution||zz>=resolution)continue;
     var next=solid[xx+zz*resolution];
     if(next==null)continue;
     int bottom=next.y();
     if(bottom<q.y())out.add(quad(q,(x+(d==5?1:0))*step,bottom,(z+(d==3?1:0))*step,step,q.y()-bottom,d,false));
    }
   }
   q=plants[i];if(q!=null)out.add(quad(q,x*step,q.y(),z*step,step,step,1,false));
   q=water[i];if(q!=null){out.add(quad(q,x*step,q.y(),z*step,step,step,1,true));wet++;}
  }
  // If there are only vertical/unknown surfaces, keep the source instead of inventing ground.
  if(out.isEmpty())return source;
  // Resample only actual source edge faces, with a bounded number per edge and layer.
  // Never copy thousands of fine edge quads into an otherwise tiny far proxy.
  var edges=new DhLodCapture.LodQuad[8*resolution];
  for(var q:source.quads()) {
   int d=q.direction();boolean boundary=(d==2&&q.z()==0)||(d==3&&q.z()==width)||(d==4&&q.x()==0)||(d==5&&q.x()==width);
   if(!boundary)continue;
   int along=d<4?q.x():q.z();
   int first=Math.max(0,(int)Math.ceil((along-step*.5)/step));
   int last=Math.min(resolution-1,(int)Math.floor((along+q.widthEastWest()-step*.5-1e-4)/step));
   for(int c=first;c<=last;c++){int k=((q.water()?4:0)+d-2)*resolution+c;var old=edges[k];
    if(old==null||q.y()+q.widthNorthSouthOrHeight()>old.y()+old.widthNorthSouthOrHeight())edges[k]=q;
   }
  }
  for(int k=0;k<edges.length;k++){var q=edges[k];if(q==null)continue;int d=q.direction(),c=k%resolution;
   out.add(quad(q,d<4?c*step:d==5?width:0,q.y(),d>=4?c*step:d==3?width:0,step,q.widthNorthSouthOrHeight(),d,q.water()));if(q.water())wet++;
  }
  return new DhLodCapture.SectionSnapshot(source.pos(),source.detailLevel(),source.minBlockX(),source.minBlockZ(),List.copyOf(out),wet,source.version(),source.minY());
 }
 private static DhLodCapture.LodQuad quad(DhLodCapture.LodQuad q,int x,int y,int z,int u,int v,int dir,boolean wet) {
  return new DhLodCapture.LodQuad((short)x,(short)y,(short)z,(short)u,(short)v,q.color(),q.textureTileId(),q.materialId(),q.skyLight(),q.blockLight(),dir,wet,wet,q.vegetation());
 }
 private DhLodProxy(){}
}
