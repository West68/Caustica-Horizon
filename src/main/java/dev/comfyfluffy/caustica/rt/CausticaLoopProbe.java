package dev.comfyfluffy.caustica.rt;

/** Opt-in benchmark timing around Minecraft's outer tick and render calls. No file I/O on the hot path. */
public final class CausticaLoopProbe {
    private static final boolean ENABLED = Boolean.getBoolean("caustica.test.loopProbe");
    private static long lastTickEnd;
    private static long tickStart;
    private static long renderStart;
    private static long gameRenderStart;
    private static long submitStart;
    private static long awaitStart;
    private static long tickNanos;
    private static long renderNanos;
    private static long gameRenderNanos;
    private static long submitNanos;
    private static long awaitNanos;
    private static long gapNanos;
    private static long tickCount;
    private static long renderCount;
    private static long submitCount;
    private static long awaitCount;

    private CausticaLoopProbe() {}

    public static void tickBegin() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        if (lastTickEnd != 0L) gapNanos += Math.max(0L, now - lastTickEnd);
        tickStart = now;
    }

    public static void tickEnd() {
        if (!ENABLED || tickStart == 0L) return;
        long now = System.nanoTime();
        tickNanos += Math.max(0L, now - tickStart);
        tickCount++;
        lastTickEnd = now;
        tickStart = 0L;
    }

    public static void renderBegin() {
        if (ENABLED) renderStart = System.nanoTime();
    }

    public static void renderEnd() {
        if (!ENABLED || renderStart == 0L) return;
        renderNanos += Math.max(0L, System.nanoTime() - renderStart);
        renderCount++;
        renderStart = 0L;
    }

    public static void gameRenderBegin() {
        if (ENABLED) gameRenderStart = System.nanoTime();
    }

    public static void gameRenderEnd() {
        if (!ENABLED || gameRenderStart == 0L) return;
        gameRenderNanos += Math.max(0L, System.nanoTime() - gameRenderStart);
        gameRenderStart = 0L;
    }

    public static void submitBegin() {
        if (ENABLED) submitStart = System.nanoTime();
    }

    public static void submitEnd() {
        if (!ENABLED || submitStart == 0L) return;
        submitNanos += Math.max(0L, System.nanoTime() - submitStart);
        submitCount++;
        submitStart = 0L;
    }

    public static void awaitBegin() {
        if (ENABLED) awaitStart = System.nanoTime();
    }

    public static void awaitEnd() {
        if (!ENABLED || awaitStart == 0L) return;
        awaitNanos += Math.max(0L, System.nanoTime() - awaitStart);
        awaitCount++;
        awaitStart = 0L;
    }

    public static long[] snapshot() {
        return new long[]{tickNanos, renderNanos, gapNanos, tickCount, renderCount,
                gameRenderNanos, submitNanos, submitCount, awaitNanos, awaitCount};
    }
}
