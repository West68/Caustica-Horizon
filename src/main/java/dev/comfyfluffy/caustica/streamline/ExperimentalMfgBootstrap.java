package dev.comfyfluffy.caustica.streamline;

import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Explicit, process-lifetime experiment. No proxy installation or system DLL replacement. */
final class ExperimentalMfgBootstrap {
    private static final String SHA256 = "3c9f402a37b629a5965fdf70e678c2bae11c1e4ccface20259c7cb9228c3b512";
    // The upstream DLL pins itself. Keep the lookup alive until process exit, too.
    private static final Arena LIFETIME = Arena.ofShared();
    private static MethodHandle query;
    private static String status = "Disabled";
    static boolean loaded() { return query != null || agentLoaded(); }
    private static boolean agentLoaded() { return ManagedMfg.active(); }
    static String status() { return ManagedMfg.active() && !"1".equals(System.getenv("CAUSTICA_MFG_AGENT_ACTIVE")) ? ManagedMfg.status() : agentLoaded() ? "JVM Agent / RTX40MFG Core loaded; verify generated presents" : status; }

    /**
     * Whether a loaded plugin actually owns the DLSS-G control path.
     *
     * <p>Measured with MFG Unlocker v1.5.0-hotfix.1: its status file reported
     * {@code bridgeReady=false}, {@code setOptionsEntryDetourInstalled=false}, {@code setOptionsCalls=0},
     * {@code getStateCalls=0} and {@code universalRouteFailureReason="no active wrapper call"} while
     * {@code slDLSSGGetState} advertised a maximum of five. Generation reported zero on every sample. A
     * plugin that cannot see the host's SetOptions calls cannot honour a multiplier, so the ceiling is only
     * trusted when the control path is ready. True when no plugin is loaded at all.
     */
    private static volatile boolean controlPlaneReady = true;
    private static volatile String controlPlaneSummary = "no plugin";

    static boolean controlPlaneReady() { return controlPlaneReady; }
    static String controlPlaneSummary() { return controlPlaneSummary; }

    /**
     * Optional provider override for isolated experiments. When a plugin is loaded and the game directory
     * contains native-experiments/nvngx_dlssg.dll, that build replaces the bundled provider before slInit.
     * The working 26.1.2 setup used DLSS-G 310.7.0 and the plugin's evaluate hook was active there; the
     * bundled 310.9.16 reports ngxEvaluateCalls=0 with the same plugin. Never applies without a plugin.
     */
    /** Copy an entire native override set (Streamline + provider) over the extracted bundle before the
     * plugin or the bridge loads anything. Controlled experiments only: the directory exists solely in an
     * isolated test instance, and nothing is bundled or downloaded. */
    static void installNativeOverrides(Path nativeDirectory, Path gameDirectory) {
        if (!Boolean.getBoolean("caustica.experimental.nativeOverrides") || nativeDirectory == null || gameDirectory == null) return;
        Path dir = gameDirectory.resolve("native-experiments/native-override");
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                try {
                    Files.copy(source, nativeDirectory.resolve(source.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    CausticaMod.LOGGER.warn("[MFG] native override: {}", source.getFileName());
                } catch (Exception failure) {
                    CausticaMod.LOGGER.warn("[MFG] native override failed for {}: {}", source.getFileName(), failure.toString());
                }
            }
        } catch (Exception failure) {
            CausticaMod.LOGGER.warn("[MFG] native override scan failed: {}", failure.toString());
        }
    }

    static void installProviderOverride(Path nativeDirectory, Path gameDirectory) {
        if (!Boolean.getBoolean("caustica.experimental.nativeOverrides") || !loaded() || nativeDirectory == null || gameDirectory == null) return;
        Path source = gameDirectory.resolve("native-experiments/nvngx_dlssg.dll");
        if (!Files.isRegularFile(source)) return;
        Path target = nativeDirectory.resolve("nvngx_dlssg.dll");
        try {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            CausticaMod.LOGGER.warn("[MFG] provider override active: replaced {} with the isolated copy", target);
        } catch (Exception failure) {
            CausticaMod.LOGGER.warn("[MFG] provider override failed: {}", failure.toString());
        }
    }

    /** Read the plugin's own status file after slInit; it is the only source that distinguishes
     *  "option accepted" from "control path installed". */
    static void evaluateControlPlane(Path gameDirectory) {
        if (!loaded()) return;
        if(ManagedMfg.active()) {
            // Legacy ASI/Core hooks NGX, not the newer single-module control-plane ABI.
            // Never read stale status from an unrelated previous experiment.
            controlPlaneReady=false;
            controlPlaneSummary="ASI/Core NGX route; multiplier confirmation comes from SDK presents/ETW";
            return;
        }
        Path statusFile = gameDirectory.resolve("native-experiments/RTXMFG-Universal.status.json");
        try {
            if (!Files.isRegularFile(statusFile)) {
                controlPlaneSummary = "status file missing";
                return;
            }
            String json = Files.readString(statusFile);
            boolean bridgeReady = json.contains("\"bridgeReady\":true");
            boolean setOptionsDetour = json.contains("\"setOptionsEntryDetourInstalled\":true");
            boolean getStateDetour = json.contains("\"getStateEntryDetourInstalled\":true");
            controlPlaneReady = bridgeReady && setOptionsDetour;
            controlPlaneSummary = "bridgeReady=" + bridgeReady + " setOptionsDetour=" + setOptionsDetour
                    + " getStateDetour=" + getStateDetour;
            if (!controlPlaneReady) {
                CausticaMod.LOGGER.warn("[MFG] plugin is loaded but its DLSS-G control path is not ready ({}); "
                        + "the provider route still needs independent SDK generated-present verification",
                        controlPlaneSummary);
            } else {
                CausticaMod.LOGGER.info("[MFG] plugin control path ready ({})", controlPlaneSummary);
            }
        } catch (Throwable failure) {
            controlPlaneSummary = "status unreadable: " + failure;
        }
    }
    static Path configuredDll(Path root, Properties settings) {
        if (!Boolean.parseBoolean(settings.getProperty("enabled", "false"))) return null;
        Path base=root.resolve("native-experiments").toAbsolutePath().normalize();
        Path dll=base.resolve(settings.getProperty("dll", "RTXMFG.dll")).normalize();
        if (!dll.startsWith(base)) throw new IllegalArgumentException("MFG DLL must remain inside native-experiments");
        return dll;
    }
    /**
     * Say plainly whether an experimental MFG plugin is present, because a silently skipped plugin looks
     * exactly like a runtime that refuses high multipliers. This never loads anything by itself: the
     * unofficial DLL is only mapped when the user opts in through the isolated bootstrap file.
     */
    private static void reportUnconfigured(Path root) {
        Path dir = root.resolve("native-experiments");
        if (!Files.isDirectory(dir)) {
            status = "Not installed (no native-experiments directory)";
            CausticaMod.LOGGER.info("[MFG] experimental MFG plugin not installed; "
                    + "official Streamline will cap generation at the driver-reported maximum");
            return;
        }
        java.util.List<String> dlls;
        try (var stream = Files.list(dir)) {
            dlls = stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".dll"))
                    .toList();
        } catch (java.io.IOException exception) {
            dlls = java.util.List.of();
        }
        if (dlls.isEmpty()) {
            status = "Not configured (native-experiments has no DLL)";
            CausticaMod.LOGGER.info("[MFG] experimental MFG plugin not configured in {}; "
                    + "official Streamline will cap generation at the driver-reported maximum", dir);
            return;
        }
        status = "Present but not enabled: " + dlls;
        CausticaMod.LOGGER.warn("[MFG] found {} in {} but it is NOT loaded. Higher multipliers (3x-6x) stay "
                + "unavailable until it is enabled through native-experiments/bootstrap.properties "
                + "(enabled=true, dll=<name>); this build does not enable third-party DLLs implicitly",
                dlls, dir);
    }

    private static void reportDisabled(Path root, String why) {
        status = "Present but disabled: " + why;
        CausticaMod.LOGGER.info("[MFG] experimental MFG plugin is disabled ({}); "
                + "only the driver-reported multiplier range will be accepted", why);
    }

    static synchronized void tryLoadBeforeStreamline(Path gameDirectory) {
        try { loadBeforeStreamline(gameDirectory); }
        catch(Throwable failure) {
            status = "Blocked: " + failure.getMessage();
            CausticaMod.LOGGER.warn("Experimental MFG disabled; official Streamline features continue: {}",status);
        }
    }

    static synchronized void loadBeforeStreamline(Path gameDirectory) throws Throwable {
        if(agentLoaded()) { status="JVM Agent owns the legacy ASI/Core load; single-module injection skipped"; return; }
        String requested = System.getProperty("caustica.rt.experimentalMfgDll", "");
        if (!Boolean.getBoolean("caustica.experimental.mfg") && requested.isBlank()) {
            reportDisabled(gameDirectory, "startup opt-in -Dcaustica.experimental.mfg=true required; official 2x preserved");
            return;
        }
        if (query != null) return;
        Path root = gameDirectory.toRealPath();
        if (requested.isBlank()) {
            Path config=root.resolve("native-experiments/bootstrap.properties");
            if (!Files.isRegularFile(config)) {
                reportUnconfigured(root);
                return;
            }
            Properties settings=new Properties();
            try(var input=Files.newInputStream(config)) {settings.load(input);}
            Path selected=configuredDll(root,settings);
            if(selected==null) {
                reportDisabled(root, "enabled=false in native-experiments/bootstrap.properties");
                return;
            }
            requested=selected.toString();
        }
        Path dll = Path.of(requested).toRealPath();
        Path controlledDirectory=root.resolve("native-experiments").toRealPath();
        if (!controlledDirectory.startsWith(root) || !dll.startsWith(controlledDirectory) || !Files.isRegularFile(dll)) {
            throw new IllegalArgumentException("RTXMFG experiment must reside in this isolated gameDir/native-experiments");
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dll)));
        // A newer plugin can be tested without weakening the default: the user pins the exact SHA-256 of the
        // binary they verified in bootstrap.properties. Without that line the built-in v1.4.2 hash applies,
        // so an unknown DLL is still refused. Never silently accept a different build.
        String expected = pinnedHash(root);
        if (!expected.equalsIgnoreCase(hash)) {
            throw new IllegalArgumentException(
                    "MFG plugin hash mismatch: file=" + hash + " expected=" + expected
                            + " (set sha256=<verified hash> in native-experiments/bootstrap.properties to test a different verified build)");
        }
        warnAboutAdditionalPlugins(root, dll);
        Path local=root.resolve("native-experiments");
        setNativeEnvironment("RTX_MFG_CONFIG_PATH",local.resolve("RTXMFG-Universal.json").toString());
        setNativeEnvironment("RTX_MFG_STATUS_PATH",local.resolve("RTXMFG-Universal.status.json").toString());
        // Do not change TEMP/TMP: the community plugin's diagnostic log may use the normal process temp.
        // Its optional overlay still uses executable-relative INI paths; we never create files in Java/bin.
        SymbolLookup symbols = SymbolLookup.libraryLookup(dll, LIFETIME);
        query = Linker.nativeLinker().downcallHandle(symbols.find("MfgUnlockSingleModuleQuery").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        snapshot("before Streamline bridge/slInit", root);
        status="Loaded before Streamline; actual generation/display unverified";
        CausticaMod.LOGGER.info("Isolated MFG plugin loaded before Streamline: {}; SHA256={}. Version, hook and generation remain separate", dll, hash);
        CausticaMod.LOGGER.info("MFG bootstrap gameDir={}, java.home={}, process={}, configuration={}, status={}",
                root,System.getProperty("java.home"),ProcessHandle.current().info().command().orElse("unknown"),
                local.resolve("RTXMFG-Universal.json"),local.resolve("RTXMFG-Universal.status.json"));
    }

    /** Built-in pin for the tested build, or the user's explicitly verified pin from the bootstrap file. */
    private static String pinnedHash(Path root) {
        try {
            Path config = root.resolve("native-experiments/bootstrap.properties");
            if (Files.isRegularFile(config)) {
                Properties settings = new Properties();
                try (var input = Files.newInputStream(config)) {
                    settings.load(input);
                }
                String override = settings.getProperty("sha256", "").trim();
                if (override.matches("[0-9a-fA-F]{64}")) {
                    return override.toLowerCase(java.util.Locale.ROOT);
                }
                if (!override.isEmpty()) {
                    CausticaMod.LOGGER.warn("[MFG] bootstrap sha256 is not a SHA-256 hex digest; ignoring it");
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the built-in pin; the caller still validates the file it actually loads.
        }
        return SHA256;
    }

    /** A second hook DLL must never load: two Vulkan present interceptors corrupt the chain. */
    private static void warnAboutAdditionalPlugins(Path root, Path chosen) {
        Path dir = root.resolve("native-experiments");
        try (var stream = Files.list(dir)) {
            java.util.List<String> others = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".dll"))
                    .filter(path -> !path.toAbsolutePath().normalize()
                            .equals(chosen.toAbsolutePath().normalize()))
                    .map(path -> path.getFileName().toString())
                    .toList();
            if (!others.isEmpty()) {
                CausticaMod.LOGGER.warn("[MFG] other DLL(s) present but NOT loaded (two FG hooks would "
                        + "intercept Vulkan twice): {}", others);
            }
        } catch (Throwable ignored) {
            // Directory listing is diagnostic only.
        }
    }

    private static void setNativeEnvironment(String name,String value) throws Throwable {
        var kernel=SymbolLookup.libraryLookup("kernel32.dll",LIFETIME);
        var setter=Linker.nativeLinker().downcallHandle(kernel.find("SetEnvironmentVariableW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS));
        try(var arena=Arena.ofConfined()) {
            int success=(int)setter.invokeExact(StreamlineLibrary.utf16(arena,name),StreamlineLibrary.utf16(arena,value));
            if(success==0) throw new IllegalStateException("Could not set process-local "+name);
        }
    }

    static synchronized void snapshot(String stage, Path gameDirectory) {
        if (query == null) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(120, 8);
            status.set(ValueLayout.JAVA_INT, 0, 120);
            int success = (int) query.invokeExact(status);
            String line = "stage=" + stage + ", query=" + success
                    + ", version=" + status.get(ValueLayout.JAVA_INT, 4)
                    + ", backendOwner=" + status.get(ValueLayout.JAVA_INT, 8)
                    + ", duplicateSuppressed=" + status.get(ValueLayout.JAVA_INT, 12)
                    + ", vulkanHooked=" + status.get(ValueLayout.JAVA_INT, 20)
                    + ", vulkanFrames=" + status.get(ValueLayout.JAVA_LONG, 48) + System.lineSeparator();
            Files.writeString(gameDirectory.resolve("rtxmfg-bootstrap.log"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            CausticaMod.LOGGER.info("RTXMFG module status: {}", line.trim());
        } catch (Throwable failure) {
            CausticaMod.LOGGER.warn("RTXMFG status query failed", failure);
        }
    }
}
