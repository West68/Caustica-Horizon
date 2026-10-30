package dev.comfyfluffy.caustica.enhanced.test;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.EnhancedGpuTimings;
import java.nio.file.Files;
import java.nio.file.Path;

/** Manual client-test adapter for the current nonblocking profiler. No benchmark CSV is fabricated. */
final class GpuTimingSnapshot {
    static void begin() {
        CausticaConfig.Rt.Diagnostics.GPU_TIMINGS.set(true);
        EnhancedGpuTimings.beginBenchmarkWindow(dev.comfyfluffy.caustica.rt.RtComposite.frameCounter());
    }
    static void write(Path output) {
        try {
            Files.writeString(output, EnhancedGpuTimings.endBenchmarkWindow());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private GpuTimingSnapshot() {}
}
