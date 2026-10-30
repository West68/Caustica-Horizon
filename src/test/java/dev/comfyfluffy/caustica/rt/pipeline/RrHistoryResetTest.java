package dev.comfyfluffy.caustica.rt.pipeline;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
final class RrHistoryResetTest {
    @Test void worldTransitionRequestsHistoryResetWithoutFeatureCreation() throws Exception {
        var rr = RtDlssRr.INSTANCE;
        Field history = RtDlssRr.class.getDeclaredField("resetHistory");
        history.setAccessible(true);
        boolean previous = history.getBoolean(rr);
        try {
            history.setBoolean(rr, false);
            boolean wasReady = rr.isReady();
            rr.requestHistoryReset();
            assertTrue(history.getBoolean(rr));
            assertEquals(wasReady, rr.isReady());
            rr.requestHistoryReset();
            assertTrue(history.getBoolean(rr));
        } finally { history.setBoolean(rr, previous); }
    }
}
