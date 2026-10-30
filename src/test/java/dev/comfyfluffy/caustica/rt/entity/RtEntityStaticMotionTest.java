package dev.comfyfluffy.caustica.rt.entity;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtEntityStaticMotionTest {
    @Test
    void staticBlockEntityMotionDoesNotRequireGpuOrDiscardOtherEntities() throws Exception {
        // emitBe passes null for a new, unchanged or budget-reused BE. Exercise the actual uploader;
        // null GPU/frame arguments prove this path performs no allocation or frame mutation.
        Method upload = null;
        for (Method method : RtEntities.class.getDeclaredMethods()) {
            if (method.getName().equals("uploadDisp") && method.getParameterTypes()[2] == FloatArrayList.class)
                upload = method;
        }
        if (upload == null) throw new AssertionError("List displacement uploader missing");
        upload.setAccessible(true);
        for (int frame = 0; frame < 8; frame++)
            assertEquals(0L, upload.invoke(RtEntities.INSTANCE, null, null, null));
        assertEquals(0L, upload.invoke(RtEntities.INSTANCE, null, null, new FloatArrayList()));
    }
}