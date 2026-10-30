package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.EnhancedGpuTimings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * One-click GPU cost survey: switches one feature off at a time, measures the GPU spans with the
 * player standing still, and writes a ranking of what each feature costs. Settings are only changed in
 * memory and restored after every step, so a crash mid-survey leaves caustica.toml untouched.
 */
public final class PerfSweep {
    private record Step(String label, String path, String value) {}

    // Each step turns ONE feature off (or down) relative to the user's own settings.
    private static final List<Step> STEPS = List.of(
            new Step("基准（你的设置）", null, null),
            new Step("视差", "material.parallax", "0"),
            new Step("ITRP 云", "clouds.mode", "0"),
            new Step("最大反弹 4→2", "composite.max-bounces", "2"),
            new Step("辐射缓存", "composite.radiance-cache", "false"),
            new Step("天空表", "composite.sky-lut", "false"),
            new Step("镜面清晰化", "composite.mirror-psr", "false"),
            new Step("水面反射", "composite.water-specular-reflection", "false"),
            new Step("水面波浪", "composite.water-waves", "false"),
            new Step("手持光源", "visuals.held-lights", "false"),
            new Step("火把引导", "visuals.torch-brightness", "0"),
            new Step("太阳轮廓光", "look.sun-rims", "0"),
            new Step("视差阴影", "visuals.relief-shadow", "0"),
            new Step("雾", "visuals.fog-density", "0"),
            new Step("水下散射", "visuals.water-scatter", "0"),
            new Step("积水", "visuals.puddle", "0"),
            new Step("实体", "entities.enabled", "false"),
            new Step("粒子", "particles.enabled", "false"),
            new Step("远景地形", "compat.distant-horizons", "false"),
            new Step("泛光", "look.bloom", "0"),
            new Step("细节锐化", "look.detail-sharpen", "0"),
            new Step("基准（复测）", null, null));

    private static final int SETTLE_TICKS = 40;   // 2 s: let resources, RR history and exposure settle
    private static final int MEASURE_TICKS = 60;  // 3 s of samples

    private static boolean running;
    private static int step, tick;
    private static final Map<String, Object> originals = new HashMap<>();
    private static final List<double[]> results = new ArrayList<>();
    private static double sumScene, sumPt, sumRr, sumComposite, sumFps;
    private static int samples, fpsSamples;
    private static long lastSample;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PerfSweep::tick);
    }

    public static boolean running() { return running; }

    public static void start() {
        if (running) return;
        running = true;
        step = 0;
        tick = 0;
        results.clear();
        originals.clear();
        resetSums();
        say("性能体检开始：请站着别动、别开菜单，大约 " + STEPS.size() * (SETTLE_TICKS + MEASURE_TICKS) / 20 + " 秒。");
        apply(STEPS.get(0));
    }

    private static void tick(Minecraft mc) {
        if (!running) return;
        if (mc.level == null || mc.player == null) { abort("世界已关闭"); return; }
        EnhancedGpuTimings.watch(SETTLE_TICKS + MEASURE_TICKS);
        tick++;
        if (tick > SETTLE_TICKS) {
            long count = EnhancedGpuTimings.sampleCount();
            if (count != lastSample) {
                lastSample = count;
                double scene = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_SCENE);
                double pt = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_TRACE);
                double rr = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_RR);
                double composite = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_COMPOSITE);
                if (pt >= 0 && composite >= 0) {
                    sumScene += Math.max(scene, 0); sumPt += pt; sumRr += Math.max(rr, 0); sumComposite += composite;
                    samples++;
                }
            }
            sumFps += mc.getFps();
            fpsSamples++;
        }
        if (tick < SETTLE_TICKS + MEASURE_TICKS) return;
        int n = Math.max(samples, 1);
        results.add(new double[]{sumScene / n, sumPt / n, sumRr / n, sumComposite / n, sumFps / Math.max(fpsSamples, 1), samples});
        restore();
        step++;
        tick = 0;
        resetSums();
        if (step >= STEPS.size()) { finish(); return; }
        apply(STEPS.get(step));
    }

    private static void resetSums() {
        sumScene = sumPt = sumRr = sumComposite = sumFps = 0;
        samples = fpsSamples = 0;
        lastSample = EnhancedGpuTimings.sampleCount();
    }

    private static CausticaConfig.RuntimeSetting<?> find(String path) {
        for (var s : CausticaConfig.settings()) if (s.tomlPath().equals(path)) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void apply(Step s) {
        if (s.path() == null) return;
        var setting = (CausticaConfig.RuntimeSetting<Object>) find(s.path());
        if (setting == null) return;
        originals.put(s.path(), setting.get());
        Object current = setting.get();
        // Not a ?: chain: mixing Integer and Float there unboxes and promotes the Integer to Float.
        Object value = null;
        if (current instanceof Boolean) value = Boolean.valueOf(s.value());
        else if (current instanceof Integer) value = Integer.valueOf(s.value());
        else if (current instanceof Float) value = Float.valueOf(s.value());
        if (value != null) setting.set(value);
    }

    @SuppressWarnings("unchecked")
    private static void restore() {
        for (var e : originals.entrySet()) {
            var setting = (CausticaConfig.RuntimeSetting<Object>) find(e.getKey());
            if (setting != null) setting.set(e.getValue());
        }
        originals.clear();
    }

    private static void abort(String why) {
        restore();
        running = false;
        CausticaMod.LOGGER.warn("Perf sweep aborted: {}", why);
    }

    private static void finish() {
        running = false;
        double base = (results.get(0)[1] + results.get(results.size() - 1)[1]) / 2;
        double baseFrame = (results.get(0)[3] + results.get(results.size() - 1)[3]) / 2;
        StringBuilder out = new StringBuilder();
        out.append("Caustica 性能体检 ").append(LocalDateTime.now()).append('\n');
        out.append("每项 = 只关掉这一个功能时的显卡耗时（毫秒，越省越说明它越费）。基准取首尾两次的平均。\n");
        out.append("pt=路径追踪 scene=场景准备 rr=DLSS降噪 composite=整帧合成 fps=显示帧率 n=样本数\n\n");
        Integer[] order = new Integer[STEPS.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> results.get(i)[1] - base));
        out.append(String.format(Locale.ROOT, "%-14s %8s %8s %7s %7s %10s %7s %5s%n", "项目", "省下pt", "pt", "scene", "rr", "composite", "fps", "n"));
        for (int i : order) {
            double[] r = results.get(i);
            String saved = STEPS.get(i).path() == null ? "-" : String.format(Locale.ROOT, "%.2f", base - r[1]);
            out.append(String.format(Locale.ROOT, "%-14s %8s %8.2f %7.2f %7.2f %10.2f %7.1f %5d%n",
                    STEPS.get(i).label(), saved, r[1], r[0], r[2], r[3], r[4], (int) r[5]));
        }
        out.append(String.format(Locale.ROOT, "%n基准整帧合成 %.2f ms，首尾漂移 %.2f ms%n", baseFrame,
                results.get(results.size() - 1)[1] - results.get(0)[1]));
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-perf-sweep");
            Files.createDirectories(dir);
            Path file = dir.resolve("sweep-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
            Files.writeString(file, out, StandardCharsets.UTF_8);
            say("性能体检完成，设置已恢复。结果：" + file);
        } catch (Exception e) {
            CausticaMod.LOGGER.error("Perf sweep could not write its report", e);
            say("性能体检完成，但写文件失败，结果在日志里。");
        }
        CausticaMod.LOGGER.info("Perf sweep\n{}", out);
    }

    private static void say(String text) {
        var mc = Minecraft.getInstance();
        try { mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Caustica] " + text)); }
        catch (Throwable ignored) { CausticaMod.LOGGER.info("[Caustica] {}", text); }
    }

    private PerfSweep() {}
}
