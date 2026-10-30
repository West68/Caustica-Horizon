package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.client.DhLodCapture;
import dev.comfyfluffy.caustica.rt.entity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhLodBudgetPlanTest {
 private static DhLodCapture.SectionSnapshot s(long id,int x,int n){var q=new DhLodCapture.LodQuad((short)0,(short)80,(short)0,(short)64,(short)64,0xff00ff00,(short)1,(byte)0,(byte)15,(byte)0,1,false,false);
  return new DhLodCapture.SectionSnapshot(id,6,x,0,Collections.nCopies(n,q),0,1,-64);}
 @Test void fullCacheCannotLetFarMeshesPermanentlyStarveClosestDetail(){
  var sources=new ArrayList<DhLodCapture.SectionSnapshot>();for(int i=0;i<400;i++)sources.add(s(i,i*64,8000));
  long budget=160L*1024*1024;var plan=DhLodBudgetPlan.plan(sources,0,0,budget);
  assertEquals(64,plan.get(0L));assertEquals(64,plan.get(1L));
  assertTrue(plan.values().stream().anyMatch(r->r<64));
  long used=0;for(var s:sources)used+=DhLodBudgetPlan.estimatedBytes(s,plan.get(s.pos()));assertTrue(used<=budget);
  assertEquals(plan,DhLodBudgetPlan.plan(sources,0,0,budget));
 }
 @Test void movingToPreviouslyFarSectionImmediatelyChangesItsDetailTarget(){
  var a=s(1,0,30000);var b=s(2,1024,30000);long budget=20L*1024*1024;
  assertEquals(64,DhLodBudgetPlan.plan(List.of(a,b),0,0,budget).get(1L));
  assertEquals(64,DhLodBudgetPlan.plan(List.of(b,a),1024,0,budget).get(2L));
 }
}
