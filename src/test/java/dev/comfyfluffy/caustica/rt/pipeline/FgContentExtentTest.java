package dev.comfyfluffy.caustica.rt.pipeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FgContentExtentTest {
    @Test void acceptsExactExtentAndSinglePaddingRow() {
        assertTrue(FgContentExtent.fits(1920,1080,1920,1080));
        assertTrue(FgContentExtent.fits(2560,1600,2560,1601));
    }
    @Test void rejectsStaleResizeAndInvalidExtents() {
        assertFalse(FgContentExtent.fits(1920,1080,2560,1601));
        assertFalse(FgContentExtent.fits(2560,1601,2560,1600));
        assertFalse(FgContentExtent.fits(2560,1599,2560,1601));
        assertFalse(FgContentExtent.fits(0,0,0,0));
        assertFalse(FgContentExtent.fits(1,Integer.MAX_VALUE,1,Integer.MIN_VALUE));
    }
}
