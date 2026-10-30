package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WetnessHistoryTest {
 @Test void filmPersistsAfterRainAndWorldSwitchResets(){
  Object a=new Object(),b=new Object();var h=new WetnessHistory();long t=1_000_000_000L;
  assertEquals(1,h.update(a,1,t));
  float wet=h.update(a,0,t+250_000_000);assertTrue(wet<1&&wet>.99);
  float before=wet;for(int i=2;i<402;i++)wet=h.update(a,0,t+i*250_000_000L);
  assertTrue(wet<before&&wet>0);assertEquals(0,h.update(b,0,t+200_000_000_000L));
 }
 @Test void partitionedTimeIsStableAndWettingFasterThanDrying(){
  Object w=new Object();var a=new WetnessHistory();var b=new WetnessHistory();long t=1_000_000_000L;
  a.update(w,0,t);b.update(w,0,t);float x=a.update(w,1,t+250_000_000);
  b.update(w,1,t+125_000_000);assertEquals(x,b.update(w,1,t+250_000_000),1e-7);
  var c=new WetnessHistory();c.update(w,1,t);float loss=1-c.update(w,0,t+250_000_000);assertTrue(x>loss);
 }
}
