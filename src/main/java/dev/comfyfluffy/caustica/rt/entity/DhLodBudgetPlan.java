package dev.comfyfluffy.caustica.rt.entity;
import dev.comfyfluffy.caustica.client.DhLodCapture.SectionSnapshot;
import java.util.*;
/** Stable nearest-first detail allocation, including a bounded far representation before upgrades. */
public final class DhLodBudgetPlan {
 private static int boundedResolution(SectionSnapshot s,int r){return r==64||s.quads().size()<=7*r*r+8*r?64:r;}
 public static long estimatedBytes(SectionSnapshot s,int resolution){
  if(s.quads().isEmpty())return 0;
  int n=resolution==64?s.quads().size():java.lang.Math.min(s.quads().size(),7*resolution*resolution+8*resolution);
  return DhQuadMesh.bytes(n)*2;
 }
 /** Input is ordered by section-edge distance, with a stable position tie-breaker. */
 public static Map<Long,Integer> plan(List<SectionSnapshot> ordered,double x,double z,long budget){
  var result=new HashMap<Long,Integer>();long used=0;
  for(var s:ordered){int r=boundedResolution(s,4);result.put(s.pos(),r);used+=estimatedBytes(s,r);}
  for(var s:ordered){int base=result.get(s.pos()),target=DhLodSelection.resolution(s,x,z);long old=estimatedBytes(s,base);
   for(int candidate:new int[]{64,32,16,8,4}){if(candidate>target)continue;int r=boundedResolution(s,candidate);long next=estimatedBytes(s,r);
    if(used-old+next<=budget){result.put(s.pos(),r);used+=next-old;break;}
   }
  }
  return result;
 }
 private DhLodBudgetPlan(){}
}
