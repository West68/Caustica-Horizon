package dev.comfyfluffy.caustica.rt.entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EntityAnchorPrecisionTest {
 @Test void worldAnchorHistoryKeepsSubBlockPrecisionAtWorldBorder() throws Exception {
  var previous=Class.forName("dev.comfyfluffy.caustica.rt.entity.RtEntities$EntityPrev",false,getClass().getClassLoader());
  assertEquals(double.class,previous.getDeclaredField("anchorX").getType());
  assertEquals(double.class,previous.getDeclaredField("anchorY").getType());
  assertEquals(double.class,previous.getDeclaredField("anchorZ").getType());
  double current=29999999.3125, last=29999999.296875;
  assertEquals(.015625f,(float)(current-last));
  assertEquals(0f,(float)current-(float)last); // Quantising absolute positions loses the motion entirely.
  assertEquals(.3125f,(float)(current-29999999));
 }
}
