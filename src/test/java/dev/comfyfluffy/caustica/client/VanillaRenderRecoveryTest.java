package dev.comfyfluffy.caustica.client;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanillaRenderRecoveryTest {
    @Test void explicitRetryClearsFailureAndStaleFrameCapture() throws Exception {
        var constructor = VanillaRenderController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var controller = constructor.newInstance();
        for (String field : new String[]{"frameStarted", "baseReady", "projectionCaptured", "worldSkipped"})
            set(controller, field, true);
        controller.markRtCompositeResult(false);
        assertEquals(true, get(controller, "failureLatched"));
        assertFalse(controller.shouldCaptureRtFirstPerson());
        controller.resetRuntimeState();
        for (String field : new String[]{"failureLatched", "frameStarted", "baseReady", "projectionCaptured", "worldSkipped"})
            assertEquals(false, get(controller, field), field);
        // Retry must not reuse the old projection or pretend a replacement frame already exists.
        assertFalse(controller.shouldCancelLevelRenderer());
        assertFalse(controller.shouldCaptureRtFirstPerson());
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = VanillaRenderController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = VanillaRenderController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
