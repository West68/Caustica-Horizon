package dev.comfyfluffy.caustica.rt.entity;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HandCollisionTest {
 @Test void wallSideWallAndNarrowGapRetractEntireAttachmentGroup() {
  var hand=new HandCollision.Bounds(-.4,-.6,-.8,.4,.2,-.1);
  assertEquals(.25,HandCollision.firstContact(hand,new HandCollision.Bounds(-2,-2,-1,2,2,-.2),1),1e-12);
  assertEquals(.5,HandCollision.firstContact(hand,new HandCollision.Bounds(.2,-2,-2,1,2,2),1),1e-12);
  assertEquals(1,HandCollision.firstContact(hand,new HandCollision.Bounds(2,2,2,3,3,3),1));
  assertEquals(0,HandCollision.firstContact(hand,new HandCollision.Bounds(-.1,-.1,-.1,.1,.1,.1),1));
 }
 @Test void randomPoseBoundsNeverIntersectAfterRetraction() {
  Random rng=new Random(10202026);
  for(int i=0;i<10000;i++) {
   double x=.05+rng.nextDouble()*2,y=.05+rng.nextDouble(),z=.05+rng.nextDouble();
   var hand=new HandCollision.Bounds(-x,-y,-z,x,y,z);
   double wall=.001+rng.nextDouble();
   var box=new HandCollision.Bounds(wall,-2,-2,wall+1,2,2);
   double scale=HandCollision.firstContact(hand,box,1);
   assertTrue(scale>=0 && scale<=1);
   assertTrue(x*scale<=wall+1e-12);
   // Uniform scale preserves relative attachments and camera ray directions.
   if(scale>0)assertEquals(x/z,(x*scale)/(z*scale),1e-12);
  }
 }
 @Test void extremeAnimationCannotCreateUnboundedCollisionQueries() {
  assertEquals(.002,new HandCollision.Bounds(-1000,-1000,-1000,1000,1000,1000).reachScale(2));
  assertEquals(0,new HandCollision.Bounds(Double.NaN,0,0,1,1,1).reachScale(2));
 }
}
