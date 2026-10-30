package dev.comfyfluffy.caustica.streamline;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.lang.foreign.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Local, hash-pinned ASI/Core import. Hooks live for the entire process; never hot-unloaded. */
public final class ManagedMfg implements PreLaunchEntrypoint {
    private static final Map<String,String> PINS = Map.of(
        "RTX40MFG.asi", "fb03c9e287c3adc052f4202ab3218eeda05b1e572cc4bdf1a4d3e0ed32b48b1c",
        "RTX40MFGCore.dll", "9ae8702ace7bedc13dc050b8fc6acbb09ee81fca8b04f35b1c68f6910c852808");
    private static volatile boolean active, restartRequired;
    private static volatile String status = "启动时检测多帧组件";
    public static boolean active() { return active || "1".equals(System.getenv("CAUSTICA_MFG_AGENT_ACTIVE")); }
    public static boolean restartRequired() { return restartRequired; }
    public static String status() { return status; }
    static boolean matches(String name, byte[] bytes) throws Exception {
        return Objects.equals(PINS.get(name), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    private static Path root() { return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize(); }
    private static Path directory(Path root) { return root.resolve("native-experiments/managed-asi"); }
    private static Path settings(Path root) { return root.resolve("config/caustica-mfg.properties"); }
    // Legacy enabled=false meant merely that the last selected multiplier was Off/2x. It must not
    // prevent preparing process-lifetime hooks for a later in-game 3x/4x selection.
    static boolean shouldPreload(Properties properties) {
        return Boolean.parseBoolean(properties.getProperty("preload", "true"));
    }
    private static void writeAtomic(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "mfg-", ".tmp");
        try {
            Files.writeString(temp, text);
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static boolean verified(Path dir) throws Exception {
        for(var entry:PINS.entrySet()) {
            Path file=dir.resolve(entry.getKey());
            if(!Files.isRegularFile(file) || !matches(entry.getKey(), Files.readAllBytes(file))) return false;
        }
        return true;
    }
    private static void prepare(Path root) throws Exception {
        Path target=directory(root);
        if(!verified(target)) {
            // Only known reference locations; no network, executable proxy, or broad filesystem scan.
            List<Path> sources=List.of(root.resolve("native-experiments/asi"),
                root.getParent().getParent().resolve("Prism-Fabulously Optimized/minecraft/config/super_resolution/libraries"));
            Path source=null;
            for(Path candidate:sources) if(verified(candidate)) { source=candidate;break; }
            if(source==null) throw new IllegalStateException("Verified RTX40MFG 1.2 ASI/Core not found locally");
            Files.createDirectories(target);
            if(!target.toRealPath().startsWith(root.toRealPath())) throw new IllegalStateException("Import escapes game directory");
            for(String name:PINS.keySet()) {
                byte[] bytes=Files.readAllBytes(source.resolve(name));
                if(!matches(name,bytes)) throw new IllegalStateException("MFG source changed during import");
                Files.write(target.resolve(name),bytes);
            }
        }
        writeAtomic(target.resolve("RTX40MFG-Universal.json"),
            "{\"followGame\":true,\"mode\":\"follow\",\"intervalLogging\":true}\n");
    }
    /** Called by the normal settings callback. Returns false if a process restart is needed. */
    public static synchronized boolean selectGeneratedCount(int generated) {
        restartRequired=false;
        try {
            Path root=root();
            if(generated>1) prepare(root);
            writeAtomic(settings(root), "# Preload hooks once at startup; Off/2x does not unload them\npreload=true\nenabled=true\n");
            restartRequired=generated>1 && !active();
            status=restartRequired ? "当前进程未加载多帧组件；下次启动将自动准备" : active()
                ? "多帧后端已准备，可直接切换倍率；实际生成以运行状态为准" : "原生 2× 后端；当前进程未加载多帧组件";
            return !restartRequired;
        } catch(Exception failure) {
            status="MFG unavailable: "+failure.getMessage();
            System.err.println("[Caustica MFG] "+status);
            return false;
        }
    }
    @Override public void onPreLaunch() {
        if(!System.getProperty("os.name", "").startsWith("Windows") || active()) return;
        boolean nativeAttempted=false;
        try {
            Path root=root(), config=settings(root);
            Properties properties=new Properties();
            if(Files.isRegularFile(config))try(var input=Files.newInputStream(config)) { properties.load(input); }
            if(!shouldPreload(properties)) { status="多帧组件启动预加载已手动停用";return; }
            prepare(root);
            byte[] loader;
            try(var input=ManagedMfg.class.getResourceAsStream("/caustica/bootstrap/windows-x64/caustica_mfg_agent.dll")) {
                if(input==null) throw new IllegalStateException("Bundled bootstrap missing");
                loader=input.readAllBytes();
            }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(loader));
            Path dll=root.resolve(".caustica-native/mfg-bootstrap/"+hash+"/caustica_mfg_agent.dll");
            Files.createDirectories(dll.getParent());
            if(!Files.isRegularFile(dll) || !Arrays.equals(loader,Files.readAllBytes(dll))) Files.write(dll,loader);
            // Keep lookup and third-party code pinned until process exit; never unload installed hooks.
            SymbolLookup symbols=SymbolLookup.libraryLookup(dll,Arena.global());
            var load=Linker.nativeLinker().downcallHandle(symbols.find("CausticaMfgBootstrap").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS));
            try(var arena=Arena.ofConfined()) {
                nativeAttempted=true;
                int result=(int)load.invokeExact(arena.allocateFrom(directory(root).toRealPath().toString()));
                if(result!=0) throw new IllegalStateException("ASI/Core initialization failed: "+result);
            }
            active=true;
            status="多帧后端已在启动时准备，可直接切换 2×/3×/4×；尚未确认实际生成";
            System.out.println("[Caustica MFG] "+status);
        } catch(Throwable failure) {
            status="MFG bootstrap failed: "+failure;
            if(nativeAttempted) {
                // Failed initialization can leave process-lifetime detours installed. Starting a second
                // provider in that process is unsafe; fail startup instead of pretending fallback is clean.
                throw new IllegalStateException(status+"; disable config/caustica-mfg.properties and restart",failure);
            }
            System.err.println("[Caustica MFG] "+status+"; no third-party hook initialized, official Streamline remains available");
        }
    }
}
