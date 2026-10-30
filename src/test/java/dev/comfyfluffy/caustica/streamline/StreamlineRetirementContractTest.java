package dev.comfyfluffy.caustica.streamline;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Offline ordering checks: no device/client initialization. */
final class StreamlineRetirementContractTest {
    @Test void swapchainRetirementFlushesAndWaitsBeforeFreeingFeatureAndInputs() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/streamline/StreamlineSwapchainCoordinator.java"));
        String body = source.split("public void configureStarting\\(\\)")[1]
                .split("public GpuSurface.Configuration")[0];
        int previous = -1;
        for (String event : new String[]{".submit()", ".suspendForSwapchainChange()", "context.waitIdle()",
                ".releaseFeatureResourcesAfterIdle()", ".releaseStreamlineInputsAfterIdle()"}) {
            int current = body.indexOf(event);
            assertTrue(current > previous, event);
            previous = current;
        }
        assertTrue(source.contains("if (desiredPlugin != pluginForSwapchain)"));
        assertFalse(source.contains("configuredRequestCount"));
    }
    @Test void transientMenusRetainResourcesAndLongTermReleaseIsGuarded() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtStreamlineFg.java"));
        String apply = source.split("private void applyOptionsForPresent")[1].split("private void refreshState")[0];
        assertTrue(apply.contains("writeOffOptions(options, true)"));
        assertFalse(apply.contains("freeDlssgResources"));
        String release = source.split("public void releaseFeatureResourcesAfterIdle")[1]
                .split("public void suspendForSwapchainChange")[0];
        assertTrue(release.contains("!featureResourcesMayExist"));
        assertTrue(release.indexOf("freeDlssgResources") < release.indexOf("featureResourcesMayExist = false"));
    }
}
