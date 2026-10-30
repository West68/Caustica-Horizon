package dev.comfyfluffy.caustica.streamline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ManagedMfgTest {
 @Test void everyMultiplierStartsWithBackendReadyUnlessExplicitlyDisabled() {
  var p=new java.util.Properties();
  assertTrue(ManagedMfg.shouldPreload(p));
  p.setProperty("enabled","false"); // old Off/2x selection must not disable future MFG
  assertTrue(ManagedMfg.shouldPreload(p));
  p.setProperty("enabled","true");
  assertTrue(ManagedMfg.shouldPreload(p));
  p.setProperty("preload","false");
  assertFalse(ManagedMfg.shouldPreload(p));
 }
 @Test void unknownOrModifiedNativeCannotBeImported() throws Exception {
  assertFalse(ManagedMfg.matches("RTXMFG.dll", new byte[0]));
  assertFalse(ManagedMfg.matches("RTX40MFGCore.dll",new byte[]{1,2,3}));
  assertFalse(ManagedMfg.matches("RTX40MFG.asi",new byte[10752]));
 }
}
