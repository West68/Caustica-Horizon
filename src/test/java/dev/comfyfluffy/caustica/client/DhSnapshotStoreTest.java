package dev.comfyfluffy.caustica.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhSnapshotStoreTest {
 @Test void dimensionsWithIdenticalSectionCoordinatesDoNotOverwriteEachOther(){
  var store=new DhSnapshotStore<String>();Object overworld=new Object(),end=new Object(),a=new Object(),b=new Object();
  store.put(overworld,42,a,"fine overworld");store.put(end,42,b,"end island");
  assertEquals("fine overworld",store.get(overworld,42));assertEquals("end island",store.get(end,42));
  assertEquals(1,store.values(overworld,(owner,value)->owner==a).size());
  store.remove(42,b);assertNull(store.get(end,42));assertEquals("fine overworld",store.get(overworld,42));
 }
 @Test void lateCloseCannotDeleteAReplacementAndClearDropsEveryWorld(){
  var store=new DhSnapshotStore<String>();Object level=new Object(),old=new Object(),replacement=new Object();
  store.put(level,1,old,"old");store.put(level,1,replacement,"new");store.remove(1,old);
  assertEquals("new",store.get(level,1));store.clear();assertEquals(0,store.count(level));
 }
}
