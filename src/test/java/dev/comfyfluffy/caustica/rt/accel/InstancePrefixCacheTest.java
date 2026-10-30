package dev.comfyfluffy.caustica.rt.accel;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
final class InstancePrefixCacheTest {
    @Test void slotsInvalidateOnSameSizePublicationRebaseClearAndSourceChange() {
        var source = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var slot = new RtAccel.InstancePrefixCache();
            assertFalse(slot.matches(source, 1, 100));
            slot.remember(source, 1, 100);
            assertTrue(slot.matches(source, 1, 100));
            assertFalse(slot.matches(source, 2, 100));
            assertFalse(slot.matches(source, 1, 99));
            assertFalse(slot.matches(new ArrayList<>(), 1, 100));
            slot.remember(source, -1, 100);
            assertFalse(slot.matches(source, -1, 100));
        }
    }
    @Test void emptyPrefixStillRequiresItsOwnWorldAndSlotRevision() {
        var cache = new RtAccel.InstancePrefixCache();
        var source = new ArrayList<>();
        cache.remember(source, 5, 0);
        assertTrue(cache.matches(source, 5, 0));
        assertFalse(cache.matches(source, 6, 0));
        assertFalse(new RtAccel.InstancePrefixCache().matches(source, 5, 0));
    }
}
