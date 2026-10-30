package dev.comfyfluffy.caustica.rt.material;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class DerivedMaterialKeyTest {
    @Test void parentAndFeatureBitsStayIndependentIncludingSignedBoundary() {
        var keys = new HashSet<Long>();
        for (int id : new int[]{0, 1, 65535, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            for (int flags : new int[]{32, 64, 96, 131072, 393216, 655360, 917504}) {
                assertTrue(keys.add(RtMaterialRegistry.derivedMaterialKey(id, flags)));
            }
        }
    }
    @Test void missingEntriesNeverAliasValidZeroAndClearStartsNewEpoch() {
        var ids = new Long2IntOpenHashMap();
        long key = RtMaterialRegistry.derivedMaterialKey(0, 32);
        assertEquals(-1, ids.getOrDefault(key, -1));
        ids.put(key, 0);
        assertEquals(0, ids.getOrDefault(key, -1));
        ids.clear();
        assertEquals(-1, ids.getOrDefault(key, -1));
    }
}
