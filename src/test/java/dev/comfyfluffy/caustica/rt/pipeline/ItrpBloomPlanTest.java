package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItrpBloomPlanTest {
 @Test void allViewportsHaveFiniteBoundedLevelsAndExactMemoryAccounting() {
  for(int[] viewport:new int[][]{{1,1},{1,17},{17,1},{3,5},{1919,1079},{1920,1080},{2560,1600},{3840,2160}}) {
   var levels=ItrpBloomPlan.levels(viewport[0],viewport[1]);assertTrue(levels.size()<=9);
   int w=viewport[0],h=viewport[1];long pixels=0;
   for(int i=0;i<levels.size();i++) {
    var s=levels.get(i);assertEquals((w+1)/2,s.width());assertEquals((h+1)/2,s.height());
    assertTrue(s.width()>0 && s.height()>0);pixels+=(long)s.width()*s.height()*(i==levels.size()-1?1:2);
    w=s.width();h=s.height();
   }
   assertEquals(pixels*8,ItrpBloomPlan.imageBytes(viewport[0],viewport[1]));
  }
  assertTrue(ItrpBloomPlan.imageBytes(1920,1080)<11L*1024*1024);
  assertThrows(IllegalArgumentException.class,()->ItrpBloomPlan.levels(0,100));
 }
 @Test void recursiveReconstructionMatchesReferenceNormalizedExtendedWeights() {
  double[] weights={4,2.4,1.44,1.17,.67515432,.60281636,.53583677,.4744388,.09302722}; // highlight diffusion 1
  for(int count=1;count<=9;count++)for(int impulse=0;impulse<count;impulse++) {
   double output=impulse==count-1?1:0,sum=0;for(int i=0;i<count;i++)sum+=weights[i];
   for(int i=count-2;i>=0;i--) {
    double mix=ItrpBloomPlan.lowMix(i,count);assertTrue(mix>=0 && mix<1);
    output=(i==impulse?1:0)*(1-mix)+output*mix;
   }
   assertEquals(weights[impulse]/sum,output,1e-7);
  }
 }
 @Test void normalizedFiltersAndPreTonemapMixPreserveConstantHdrFields() {
  double tentSum=0;for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)tentSum+=(x==0?2:1)*(y==0?2:1)/16.0;
  assertEquals(1,tentSum);
  for(double value:new double[]{0,.18,1,21,4000,65504})for(int count=1;count<=9;count++) {
   double reconstructed=value;
   for(int i=count-2;i>=0;i--) {double mix=ItrpBloomPlan.lowMix(i,count);reconstructed=value*(1-mix)+reconstructed*mix;}
   for(boolean submerged:new boolean[]{false,true}) {
    double blend=.035+(submerged?.5:0);
    assertEquals(value,value*(1-blend)+reconstructed*blend,1e-8);
   }
  }
 }
}
