package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmissionHistoryStateTest {
    @Test void torchSliderResetsHistoryOnceWithoutChangingGlobalEmission() {
        var state=new EmissionHistoryState();
        assertTrue(state.changed(1,4,true,true,true,1));
        assertFalse(state.changed(1,4,true,true,true,1));
        assertTrue(state.changed(1,4,true,true,true,2));
        assertFalse(state.changed(1,4,true,true,true,2));
    }
    @Test void stableFramesDoNotRepeatedlyResetButSliderAndReloadDo() {
        var state = new EmissionHistoryState();
        assertTrue(state.changed(4,4,true,true,true));
        for(int i=0;i<1000;i++) assertFalse(state.changed(4,4,true,true,true));
        assertTrue(state.changed(4,8,true,true,true));
        assertFalse(state.changed(4,8,true,true,true));
        assertTrue(state.changed(5,8,true,true,true));
        assertFalse(state.changed(5,8,true,true,true));
        assertTrue(state.changed(5,4,true,true,true)); // restore defaults
    }
    @Test void colorAndEntityEmissionEditsAlsoInvalidateExactlyOnce() {
        var state = new EmissionHistoryState(); state.changed(1,4,true,true,true);
        assertTrue(state.changed(1,4,false,true,true));
        assertTrue(state.changed(1,4,false,false,true));
        assertTrue(state.changed(1,4,false,false,false));
        assertFalse(state.changed(1,4,false,false,false));
    }
}
