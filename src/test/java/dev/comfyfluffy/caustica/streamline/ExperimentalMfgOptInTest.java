package dev.comfyfluffy.caustica.streamline;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ExperimentalMfgOptInTest {
    @TempDir Path root;

    @Test void staleExperimentDirectoryCannotReplaceOfficialRuntime() throws Exception {
        String previous=System.getProperty("caustica.experimental.nativeOverrides");
        try {
            System.clearProperty("caustica.experimental.nativeOverrides");
            Path nativeDir=Files.createDirectories(root.resolve("native"));
            Path overrides=Files.createDirectories(root.resolve("game/native-experiments/native-override"));
            Files.writeString(nativeDir.resolve("sl.interposer.dll"),"official");
            Files.writeString(overrides.resolve("sl.interposer.dll"),"experiment");
            ExperimentalMfgBootstrap.installNativeOverrides(nativeDir,root.resolve("game"));
            assertEquals("official",Files.readString(nativeDir.resolve("sl.interposer.dll")));
        } finally { restore("caustica.experimental.nativeOverrides",previous); }
    }

    @Test void bootstrapFileAloneCannotLoadThirdPartyModule() throws Throwable {
        String enabled=System.getProperty("caustica.experimental.mfg");
        String dll=System.getProperty("caustica.rt.experimentalMfgDll");
        try {
            System.clearProperty("caustica.experimental.mfg");
            System.clearProperty("caustica.rt.experimentalMfgDll");
            Path experiments=Files.createDirectories(root.resolve("native-experiments"));
            Files.writeString(experiments.resolve("bootstrap.properties"),"enabled=true\ndll=does-not-exist.dll\n");
            ExperimentalMfgBootstrap.loadBeforeStreamline(root);
            assertFalse(ExperimentalMfgBootstrap.loaded());
            assertTrue(ExperimentalMfgBootstrap.status().contains("startup opt-in"));
        } finally { restore("caustica.experimental.mfg",enabled);restore("caustica.rt.experimentalMfgDll",dll); }
    }

    private static void restore(String key,String value) {
        if(value==null)System.clearProperty(key);else System.setProperty(key,value);
    }
}
