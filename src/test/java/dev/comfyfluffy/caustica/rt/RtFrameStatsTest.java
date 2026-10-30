package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class RtFrameStatsTest {
    @Test void everyLiteralFrameCounterIsRegisteredWithProfilingEnabled() throws Exception {
        boolean previous=CausticaConfig.Rt.FrameStats.ENABLED.value();
        CausticaConfig.Rt.FrameStats.ENABLED.set(true);
        try {
            RtFrameStats.FRAME.begin();
            var pattern=Pattern.compile("RtFrameStats\\.FRAME\\.count\\(\\s*\"([^\"]+)\"");
            int checked=0;
            try(var files=Files.walk(Path.of("src/main/java"))) {
                for(var path:files.filter(p->p.toString().endsWith(".java")).toList()) {
                    var matcher=pattern.matcher(Files.readString(path));
                    while(matcher.find()) {
                        String name=matcher.group(1);
                        assertDoesNotThrow(()->RtFrameStats.FRAME.count(name,1),path+": "+name);
                        checked++;
                    }
                }
            }
            assertTrue(checked>20);
        } finally {
            CausticaConfig.Rt.FrameStats.ENABLED.set(false);RtFrameStats.FRAME.begin();
            CausticaConfig.Rt.FrameStats.ENABLED.set(previous);
        }
    }
    @Test void everyLiteralFrameStageIsRegisteredWithProfilingEnabled() throws Exception {
        boolean previous = CausticaConfig.Rt.FrameStats.ENABLED.value();
        CausticaConfig.Rt.FrameStats.ENABLED.set(true);
        try {
            RtFrameStats.FRAME.begin();
            var pattern = Pattern.compile("RtFrameStats\\.FRAME\\.(?:stage|endStage)\\(\\s*\"([^\"]+)\"");
            int checked = 0;
            try (var files = Files.walk(Path.of("src/main/java"))) {
                for (var path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = pattern.matcher(Files.readString(path));
                    while (matcher.find()) {
                        String name = matcher.group(1);
                        assertDoesNotThrow(() -> RtFrameStats.FRAME.stage(name).close(), path + ": " + name);
                        assertDoesNotThrow(() -> RtFrameStats.FRAME.endStage(name, System.nanoTime()));
                        checked++;
                    }
                }
            }
            assertTrue(checked > 20);
            assertDoesNotThrow(() -> RtFrameStats.FRAME.stage("frame.itrpBloom").close());
        } finally {
            CausticaConfig.Rt.FrameStats.ENABLED.set(false);
            RtFrameStats.FRAME.begin();
            CausticaConfig.Rt.FrameStats.ENABLED.set(previous);
        }
    }
}
