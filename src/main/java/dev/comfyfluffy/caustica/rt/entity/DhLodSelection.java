package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.client.DhLodCapture.SectionSnapshot;
import java.util.*;

/** CPU-only LOD admission and atomic parent/child handoff policy. */
public final class DhLodSelection {
    public static double distance(SectionSnapshot s,double x,double z) {
        double width=Math.scalb(1.0,s.detailLevel());
        double dx=Math.max(0,Math.max(s.minBlockX()-x,x-(s.minBlockX()+width)));
        double dz=Math.max(0,Math.max(s.minBlockZ()-z,z-(s.minBlockZ()+width)));
        return dx*dx+dz*dz;
    }
    /** 64 means untouched source quads, including all vertical layers and foliage gaps. */
    public static int resolution(SectionSnapshot s,double x,double z) {
        double d=distance(s,x,z);
        return s.quads().size()<=2048 || d<512.0*512 ?64:d<2048.0*2048?16:8;
    }
    public static boolean contains(SectionSnapshot a,SectionSnapshot b) {
        long aw=1L<<a.detailLevel(),bw=1L<<b.detailLevel();
        return a.minBlockX()<=b.minBlockX() && a.minBlockZ()<=b.minBlockZ()
            && (long)a.minBlockX()+aw>=(long)b.minBlockX()+bw
            && (long)a.minBlockZ()+aw>=(long)b.minBlockZ()+bw;
    }
    private static boolean overlaps(SectionSnapshot a,SectionSnapshot b) {
        long aw=1L<<a.detailLevel(),bw=1L<<b.detailLevel();
        return a.minBlockX()<(long)b.minBlockX()+bw && b.minBlockX()<(long)a.minBlockX()+aw
            && a.minBlockZ()<(long)b.minBlockZ()+bw && b.minBlockZ()<(long)a.minBlockZ()+aw;
    }
    /** Retain an old parent until every requested replacement has a BLAS. Never overlap it
     * with completed children: their builds still get recorded while they await publication. */
    public static List<SectionSnapshot> handoff(List<SectionSnapshot> wanted,Map<Long,SectionSnapshot> resident) {
        return handoff(wanted,resident,(x,z,width)->false);
    }
    @FunctionalInterface public interface FineCoverage {boolean covers(int x,int z,int width);}
    public static List<SectionSnapshot> handoff(List<SectionSnapshot> wanted,Map<Long,SectionSnapshot> resident,FineCoverage fine) {
        Set<Long> ids=new HashSet<>();var ready=new ArrayList<SectionSnapshot>();
        for(var s:wanted){ids.add(s.pos());var r=resident.get(s.pos());if(r!=null)ready.add(r);}
        var fallback=new ArrayList<SectionSnapshot>();
        for(var old:resident.values())if(!ids.contains(old.pos())) {
            boolean requested=false;
            for(var s:wanted)if(overlaps(old,s)){requested=true;break;}
            if(requested && !fullyCovered(old.minBlockX(),old.minBlockZ(),1<<old.detailLevel(),ready,fine))fallback.add(old);
        }
        // Quadtree footprints are nested or disjoint. Publish only the outermost fallback.
        fallback.sort(Comparator.comparingInt(SectionSnapshot::detailLevel).reversed());
        var result=new ArrayList<SectionSnapshot>();
        for(var s:fallback)if(!covered(result,s))result.add(s);
        var parents=List.copyOf(result);
        for(var s:wanted){var r=resident.get(s.pos());if(r!=null && !covered(parents,r))result.add(r);}
        return result;
    }
    private static boolean fullyCovered(int x,int z,int width,List<SectionSnapshot> ready,FineCoverage fine) {
        if(fine.covers(x,z,width))return true;
        boolean intersects=false;
        for(var r:ready) {
            long size=1L<<r.detailLevel();
            if(r.minBlockX()<=x && r.minBlockZ()<=z && (long)r.minBlockX()+size>=(long)x+width && (long)r.minBlockZ()+size>=(long)z+width)return true;
            if(r.minBlockX()<(long)x+width && x<(long)r.minBlockX()+size && r.minBlockZ()<(long)z+width && z<(long)r.minBlockZ()+size)intersects=true;
        }
        if(!intersects || width<=16)return false;
        int half=width/2;
        return fullyCovered(x,z,half,ready,fine) && fullyCovered(x+half,z,half,ready,fine)
            && fullyCovered(x,z+half,half,ready,fine) && fullyCovered(x+half,z+half,half,ready,fine);
    }
    private static boolean covered(List<SectionSnapshot> sections,SectionSnapshot s) {
        for(var p:sections)if(contains(p,s))return true;
        return false;
    }
    private DhLodSelection() {}
}
