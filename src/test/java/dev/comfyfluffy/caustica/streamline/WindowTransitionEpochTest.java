package dev.comfyfluffy.caustica.streamline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class WindowTransitionEpochTest {
 @Test void completionOnlyAcknowledgesChangesSeenAtConfigureStart() {
  var epoch=new WindowTransitionEpoch();assertFalse(epoch.pending());epoch.changed();assertTrue(epoch.pending());
  epoch.configurationStarted();epoch.changed();epoch.configurationCompleted();assertTrue(epoch.pending());
  epoch.configurationStarted();epoch.configurationCompleted();assertFalse(epoch.pending());
 }
 @Test void asynchronousCallbackCannotBeLostDuringCompletion() throws Exception {
  var epoch=new WindowTransitionEpoch();epoch.configurationStarted();var thread=new Thread(epoch::changed);
  thread.start();thread.join();epoch.configurationCompleted();assertTrue(epoch.pending());
 }
}
