package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

final class RtStreamlineFgFixedMultiplierTest {
    @Test
    void isolatedFiveAndSixRequestsDoNotChangeReportedCapability() {
        String key = "caustica.rt.fg.experimentalGeneratedFrames";
        String previous = System.getProperty(key);
        int reported = RtStreamlineFg.INSTANCE.multiFrameCountMax();
        try {
            for (int count = 2; count <= 5; count++) {
                System.setProperty(key, Integer.toString(count));
                assertEquals(count, RtStreamlineFg.INSTANCE.effectiveMultiFrameCount());
                assertEquals(reported, RtStreamlineFg.INSTANCE.multiFrameCountMax());
            }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test
    void fixedGeneratedFrameRangeIsOneThroughFive() {
        assertEquals(1, RtStreamlineFg.selectMultiFrameCount(-10, 0));
        assertEquals(1, RtStreamlineFg.selectMultiFrameCount(1, 5));
        assertEquals(2, RtStreamlineFg.selectMultiFrameCount(2, 5));
        assertEquals(3, RtStreamlineFg.selectMultiFrameCount(3, 5));
        assertEquals(4, RtStreamlineFg.selectMultiFrameCount(4, 5));
        assertEquals(5, RtStreamlineFg.selectMultiFrameCount(5, 5));
        assertEquals(5, RtStreamlineFg.selectMultiFrameCount(99, 5));
        assertEquals(5, RtStreamlineFg.selectMultiFrameCount(5, 2)); // request must not silently degrade
    }

    @Test
    void zeroFromARecreatedSwapchainCannotEraseAdapterCapability() {
        assertEquals(5, RtStreamlineFg.retainReportedMaximum(5, 0));
        assertEquals(5, RtStreamlineFg.retainReportedMaximum(0, 5));
    }

    @Test
    void staleDynamicAndAutoSelectionsFallBackToFixed() {
        assertEquals(0, RtStreamlineFg.streamlineModeForVulkan("off"));
        assertEquals(1, RtStreamlineFg.streamlineModeForVulkan("fixed"));
        assertEquals(1, RtStreamlineFg.streamlineModeForVulkan("dynamic"));
        assertEquals(1, RtStreamlineFg.streamlineModeForVulkan("auto"));
    }

    @Test
    void swapchainRecreationRetainsMaximumAndDeviceDestroyClearsIt() throws Exception {
        RtStreamlineFg fg = RtStreamlineFg.INSTANCE;
        Field maximum = RtStreamlineFg.class.getDeclaredField("multiFrameCountMax");
        maximum.setAccessible(true);
        Field observed = RtStreamlineFg.class.getDeclaredField("maxObservedGenerated");
        observed.setAccessible(true);
        maximum.setInt(fg, 5);

        fg.onSwapchainConfigured(3840, 2160, 64, 6, false, false, false, 2L);
        assertEquals(5, fg.multiFrameCountMax());

        fg.destroy();
        assertEquals(0, fg.multiFrameCountMax());
    }
    /**
     * The option set must follow what is actually submitted, not the raw request. Asking for 5x before the
     * runtime has reported a ceiling submits 2x, so the fingerprint must stay stable; once the ceiling is
     * known, a change inside it must resubmit and a change above it must clamp instead of churning.
     */
    @Test
    void optionsFollowTheSubmittedCountNotTheRawRequest() throws Exception {
        var setting = dev.comfyfluffy.caustica.CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        int previousCount = setting.value();
        var fg = RtStreamlineFg.INSTANCE;
        var fingerprint = RtStreamlineFg.class.getDeclaredMethod("optionsFingerprint",
                boolean.class, int.class, int.class, int.class, int.class, int.class, int.class, boolean.class);
        fingerprint.setAccessible(true);
        Field maximum = RtStreamlineFg.class.getDeclaredField("multiFrameCountMax");
        maximum.setAccessible(true);
        Field observed = RtStreamlineFg.class.getDeclaredField("maxObservedGenerated");
        observed.setAccessible(true);
        int previousMax = maximum.getInt(fg);
        try {
            maximum.setInt(fg, 0);
            observed.setInt(fg, 0);
            setting.set(1);
            long unknownTwoX = (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true);
            setting.set(5);
            assertEquals(unknownTwoX,
                    (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true),
                    "an unknown ceiling must not resubmit a different option set");

            maximum.setInt(fg, 3);
            observed.setInt(fg, 5);
            setting.set(1);
            long one = (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true);
            setting.set(2);
            long two = (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true);
            setting.set(3);
            long three = (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true);
            setting.set(4);
            long four = (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true);
            setting.set(5);
            long five = (long) fingerprint.invoke(fg, true, 1920, 1080, 37, 960, 540, 37, true);
            org.junit.jupiter.api.Assertions.assertNotEquals(one, two);
            org.junit.jupiter.api.Assertions.assertNotEquals(two, three);
            assertEquals(three, four, "requests above the ceiling clamp to it");
            assertEquals(three, five, "requests above the ceiling clamp to it");
        } finally {
            setting.set(previousCount);
            maximum.setInt(fg, previousMax);
        }
    }
}
