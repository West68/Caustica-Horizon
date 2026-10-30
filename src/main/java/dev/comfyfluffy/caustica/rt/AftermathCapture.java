package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import static java.lang.foreign.ValueLayout.*;

/** Opt-in crash diagnostics only. The SDK DLL remains external and is never redistributed. */
final class AftermathCapture {
    private static final Arena ARENA = Arena.ofShared();
    private static final AtomicLong SERIAL = new AtomicLong();
    private static MethodHandle status;
    private static Path output;
    private static boolean attempted;

    static synchronized void initialize() {
        String dll = System.getProperty("caustica.rt.aftermathDll");
        if (attempted || dll == null || dll.isBlank()) return;
        attempted = true;
        try {
            Path library = Path.of(dll).toRealPath();
            output = Path.of("caustica-aftermath").toAbsolutePath();
            Files.createDirectories(output);
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(library, ARENA);
            FunctionDescriptor callback = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
            var lookup = MethodHandles.lookup();
            var signature = MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class);
            MemorySegment crash = linker.upcallStub(lookup.findStatic(AftermathCapture.class, "crash", signature), callback, ARENA);
            MemorySegment debug = linker.upcallStub(lookup.findStatic(AftermathCapture.class, "debug", signature), callback, ARENA);
            MethodHandle enable = linker.downcallHandle(symbols.find("GFSDK_Aftermath_EnableGpuCrashDumps").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            status = linker.downcallHandle(symbols.find("GFSDK_Aftermath_GetCrashDumpStatus").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            int result = (int) enable.invokeExact(0x21b, 2, 0, crash, debug,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            if (result != 1) {
                status = null;
                throw new IllegalStateException("Aftermath enable returned 0x" + Integer.toHexString(result));
            }
            CausticaMod.LOGGER.info("Aftermath capture enabled before Vulkan device creation: {} -> {}", library, output);
        } catch (Throwable t) {
            status = null;
            CausticaMod.LOGGER.error("Optional Aftermath capture could not initialize", t);
        }
    }

    private static void crash(MemorySegment data, int size, MemorySegment user) { write(data, size, ".nv-gpudmp"); }
    private static void debug(MemorySegment data, int size, MemorySegment user) { write(data, size, ".nvdbg"); }
    private static void write(MemorySegment data, int size, String extension) {
        // Driver callbacks can run concurrently; no Java exception may cross the native boundary.
        try {
            long bytes = Integer.toUnsignedLong(size);
            if (bytes == 0 || bytes > 128L * 1024 * 1024) throw new IllegalArgumentException("diagnostic size " + bytes);
            Path file = output.resolve("capture-" + ProcessHandle.current().pid() + "-" + SERIAL.incrementAndGet() + extension);
            Files.write(file, data.reinterpret(bytes).toArray(JAVA_BYTE), StandardOpenOption.CREATE_NEW);
            if (extension.equals(".nv-gpudmp")) CausticaMod.LOGGER.error("Aftermath crash dump written: {}", file);
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Aftermath diagnostic callback write failed", t);
        }
    }

    static void awaitCrashDump() {
        if (status == null) return;
        // Only after a real Device Lost. Never add a wait to a healthy frame or swallow the failure.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            long deadline = System.nanoTime() + 5_000_000_000L;
            do {
                int result = (int) status.invokeExact(value);
                int state = value.get(JAVA_INT, 0);
                if (result != 1 || state == 2 || state == 4) return;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            CausticaMod.LOGGER.warn("Aftermath crash capture wait reached 5 second limit");
        } catch (Throwable t) {
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            CausticaMod.LOGGER.error("Aftermath crash capture wait failed", t);
        }
    }
}
