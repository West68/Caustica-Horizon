package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RenderFlagScopeTest {
    public static class Flags {public static boolean isInHand;public static Boolean isInLeftHand;public static int unrelated=7;}
    @Test void nestedEntitiesRestoreTheCallersContext()throws Exception{
        var slots=RenderFlagScope.bind(Flags.class,"isInHand","isInLeftHand","missingOptionalFlag","unrelated");
        Flags.isInHand=true;Flags.isInLeftHand=true;
        try(var outer=new RenderFlagScope(slots)){
            assertFalse(Flags.isInHand);assertNull(Flags.isInLeftHand);
            Flags.isInHand=true;Flags.isInLeftHand=false;
            try(var inner=new RenderFlagScope(slots)){assertFalse(Flags.isInHand);assertNull(Flags.isInLeftHand);}
            assertTrue(Flags.isInHand);assertEquals(Boolean.FALSE,Flags.isInLeftHand);
        }
        assertTrue(Flags.isInHand);assertEquals(Boolean.TRUE,Flags.isInLeftHand);assertEquals(7,Flags.unrelated);
    }
    @Test void failureAndRepeatedCloseDoNotLeakState()throws Exception{
        Flags.isInHand=true;
        var scope=new RenderFlagScope(RenderFlagScope.bind(Flags.class,"isInHand"));
        assertThrows(IllegalStateException.class,()->{try(scope){throw new IllegalStateException("render failed");}});
        assertTrue(Flags.isInHand);Flags.isInHand=false;scope.close();assertFalse(Flags.isInHand);
    }
}
