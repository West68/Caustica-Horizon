package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic-only VRAM inventory ({@code -Dcaustica.diag.vramInventory=true}). Names Caustica VMA allocations
 * with their debug labels and periodically writes the detailed VMA JSON maps of both the Caustica allocator and
 * Minecraft's own allocator, plus process-wide heap budgets. Off by default: no naming, no dumps, no cost.
 */
public final class VramInventory {
    public static final boolean ENABLED = Boolean.getBoolean("caustica.diag.vramInventory");
    private static final long INTERVAL_NS = Long.getLong("caustica.diag.vramInventoryIntervalMs", 30_000L) * 1_000_000L;
    private static long nextDumpNs;
    private static int sequence;

    private VramInventory() {
    }

    /** Attach the resource label to its VMA allocation so the JSON map is attributable. */
    public static void name(long vma, long allocation, String label) {
        if (!ENABLED || vma == 0L || allocation == 0L || label == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Vma.vmaSetAllocationName(vma, allocation, stack.UTF8(label));
        }
    }

    /** Called once per RT frame; writes a snapshot every interval. */
    public static void tick(RtContext context) {
        if (!ENABLED || context == null) return;
        long now = System.nanoTime();
        if (nextDumpNs == 0L) nextDumpNs = now + INTERVAL_NS;
        if (now < nextDumpNs) return;
        nextDumpNs = now + INTERVAL_NS;
        try {
            Path dir = Path.of(System.getProperty("ce.evidence", "vram-inventory"));
            Files.createDirectories(dir);
            String prefix = String.format("vram-%03d", sequence++);
            Path phase = dir.resolve("phase.json");
            StringBuilder meta = new StringBuilder("{\"nanoTime\":").append(now);
            if (Files.exists(phase)) meta.append(",\"phase\":").append(Files.readString(phase).trim());
            meta.append(",\"heaps\":[");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VmaBudget.Buffer budgets = VmaBudget.calloc(org.lwjgl.vulkan.VK10.VK_MAX_MEMORY_HEAPS, stack);
                Vma.vmaGetHeapBudgets(context.vma(), budgets);
                for (int i = 0; i < 2; i++) {
                    VmaBudget b = budgets.get(i);
                    if (i > 0) meta.append(',');
                    meta.append("{\"heap\":").append(i).append(",\"processUsage\":").append(b.usage())
                            .append(",\"budget\":").append(b.budget())
                            .append(",\"rtAllocationBytes\":").append(b.statistics().allocationBytes())
                            .append(",\"rtBlockBytes\":").append(b.statistics().blockBytes()).append('}');
                }
            }
            meta.append("]}");
            Files.writeString(dir.resolve(prefix + "-meta.json"), meta);
            writeStats(context.vma(), dir.resolve(prefix + "-caustica-vma.json"));
            writeStats(context.device().vma(), dir.resolve(prefix + "-minecraft-vma.json"));
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("VRAM inventory snapshot failed", t);
        }
    }

    private static void writeStats(long vma, Path out) throws java.io.IOException {
        if (vma == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            Vma.vmaBuildStatsString(vma, p, true);
            long str = p.get(0);
            try {
                Files.writeString(out, MemoryUtil.memUTF8(str), StandardCharsets.UTF_8);
            } finally {
                Vma.vmaFreeStatsString(vma, MemoryUtil.memByteBufferNT1(str));
            }
        }
    }
}
