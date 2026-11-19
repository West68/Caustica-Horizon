package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.EnhancedGpuTimings;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unattended benchmark for bench jars only (stripped from releases): runs when
 * caustica-perf-sweep/autobench.txt exists, on a throwaway copy of a world. Each line of that file is one step:
 * {@code label; toml.path=value | /command | ...}. Settings changes stay in memory. Writes one row per step
 * (GPU spans) plus the frame.csv index range of the step, then quits the game.
 */
public final class PerfAutoBench {
    private static final int WARMUP_TICKS = 30 * 20, SETTLE_TICKS = 10 * 20, MEASURE_TICKS = 10 * 20;

    private record Step(String label, String[] actions) {}

    private static final List<Step> steps = new ArrayList<>();
    private static final StringBuilder out = new StringBuilder();
    private static int step = -1, tick;
    private static double scene, pt, rr, composite, fps, present;
    // Minimum observed span: laptop power state dips every sample, so the min tracks the throttle-free cost.
    private static double ptMin = Double.MAX_VALUE, rrMin = Double.MAX_VALUE, compMin = Double.MAX_VALUE;
    private static int samples, fpsSamples;
    private static long lastSample, csvStart;
    private static Path dir;
    private static boolean walk;
    private static String pendingShot;
    private static String burstName;
    private static int burstLeft, burstIndex;

    public static void init() {
        dir = FabricLoader.getInstance().getGameDir().resolve("caustica-perf-sweep");
        Path plan = dir.resolve("autobench.txt");
        if (!Files.isRegularFile(plan)) return;
        try {
            for (String line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int sep = line.indexOf(';');
                String[] actions = sep < 0 ? new String[0] : line.substring(sep + 1).split("\\|");
                steps.add(new Step((sep < 0 ? line : line.substring(0, sep)).trim(), actions));
            }
            Files.move(plan, dir.resolve("autobench-consumed.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(PerfAutoBench::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        mc.options.pauseOnLostFocus = false;
        mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        EnhancedGpuTimings.watch(400);
        tick++;
        if (walk) {
            mc.player.setYRot(mc.player.getYRot() + 0.6f);
            mc.options.keyJump.setDown(mc.player.horizontalCollision);
        }
        if (burstLeft > 0) {
            java.io.File file = dir.resolve(burstName + "_" + (burstIndex++) + ".png").toFile();
            burstLeft--;
            net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try (image) { image.writeToFile(file); } catch (Exception ignored) {}
            });
        }
        if (step < 0) {
            if (tick == 1) CausticaConfig.Rt.FrameStats.ENABLED.set(true);
            if (tick < WARMUP_TICKS) return;
            next(mc);
            return;
        }
        if (tick == SETTLE_TICKS) {
            if (pendingShot != null) {
                java.io.File file = dir.resolve(pendingShot + ".png").toFile();
                pendingShot = null;
                net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                    try (image) { image.writeToFile(file); } catch (Exception ignored) {}
                });
            }
            csvStart = RtFrameStats.FRAME.frameIndex();
            lastSample = EnhancedGpuTimings.sampleCount();
            EnhancedGpuTimings.summary(); // reset CPU accumulators for this step
        }
        if (tick > SETTLE_TICKS) {
            long count = EnhancedGpuTimings.sampleCount();
            if (count != lastSample) {
                lastSample = count;
                double p = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_TRACE);
                double c = EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_COMPOSITE);
                if (p >= 0 && c >= 0) {
                    scene += Math.max(EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_SCENE), 0);
                    pt += p;
                    ptMin = Math.min(ptMin, p);
                    compMin = Math.min(compMin, c);
                    double rrSpan = Math.max(EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_RR), 0);
                    rr += rrSpan;
                    rrMin = Math.min(rrMin, rrSpan);
                    composite += c;
                    present += Math.max(EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_HUDLESS), 0) + Math.max(EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_UI), 0) + Math.max(EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_BLIT), 0) + Math.max(EnhancedGpuTimings.latestSpanMs(EnhancedGpuTimings.S_PRESENT_TAIL), 0);
                    samples++;
                }
            }
            fps += mc.getFps();
            fpsSamples++;
        }
        if (tick < SETTLE_TICKS + MEASURE_TICKS) return;
        int n = Math.max(samples, 1);
        out.append(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.1f,%d,%d,%d,%.2f,%.2f,%.2f,\"%s\"%n", steps.get(step).label(),
                present / n, pt / n, rr / n, composite / n, fps / Math.max(fpsSamples, 1), samples,
                csvStart, RtFrameStats.FRAME.frameIndex(), ptMin, rrMin, compMin, EnhancedGpuTimings.summary()));
        write();
        next(mc);
    }

    private static void next(Minecraft mc) {
        step++;
        tick = 0;
        scene = pt = rr = composite = fps = present = 0;
        ptMin = Double.MAX_VALUE; rrMin = Double.MAX_VALUE; compMin = Double.MAX_VALUE;
        samples = fpsSamples = 0;
        if (step >= steps.size()) {
            mc.stop();
            return;
        }
        var server = mc.getSingleplayerServer();
        for (String raw : steps.get(step).actions()) {
            String action = raw.trim();
            if (action.startsWith("/")) {
                if (server != null) {
                    String cmd = action.substring(1);
                    var source = server.createCommandSourceStack();
                    var player = mc.player.getUUID();
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayer(player);
                        server.getCommands().performPrefixedCommand(sp == null ? source
                                : source.withEntity(sp).withPosition(sp.position()), cmd);
                    });
                }
            } else if (action.startsWith("camera=")) {
                mc.options.setCameraType(net.minecraft.client.CameraType.values()[Integer.parseInt(action.substring(7))]);
            } else if (action.startsWith("prop:")) {
                String[] kv = action.substring(5).split("=", 2);
                System.setProperty(kv[0], kv[1]);
            } else if (action.equals("reload")) {
                mc.levelExtractor.allChanged();
            } else if (action.startsWith("look=")) {
                String[] yp = action.substring(5).split(",");
                mc.player.setYRot(Float.parseFloat(yp[0]));
                mc.player.setXRot(Float.parseFloat(yp[1]));
                mc.player.getAbilities().flying = true;
            } else if (action.startsWith("burst=")) {
                String[] nb = action.substring(6).split(",");
                burstName = nb[0]; burstLeft = Integer.parseInt(nb[1]); burstIndex = 0;
            } else if (action.startsWith("shot=")) {
                pendingShot = action.substring(5);
            } else if (action.startsWith("walk=")) {
                walk = action.endsWith("1");
                mc.options.keyUp.setDown(walk);
                mc.options.keySprint.setDown(walk);
                if (!walk) mc.options.keyJump.setDown(false);
            } else if (action.startsWith("freecam=")) {
                // Tweakeroo free camera (dev harness only): camera detaches from the player, like the user's
                // "soul" view. Reflection keeps Tweakeroo an optional, compile-time-free dependency.
                try {
                    Class<?> toggle = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle");
                    Object freeCam = toggle.getField("TWEAK_FREE_CAMERA").get(null);
                    toggle.getMethod("setBooleanValue", boolean.class).invoke(freeCam, action.endsWith("1"));
                } catch (ReflectiveOperationException e) {
                    dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn("autobench: Tweakeroo free camera unavailable", e);
                }
            } else if (action.startsWith("window=")) {
                String[] wh = action.substring(7).split("x");
                mc.getWindow().setWindowed(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            } else if (action.contains("=")) {
                String[] kv = action.split("=", 2);
                set(kv[0].trim(), kv[1].trim());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void set(String path, String value) {
        for (var s : CausticaConfig.settings()) {
            if (!s.tomlPath().equals(path)) continue;
            var setting = (CausticaConfig.RuntimeSetting<Object>) s;
            Object current = setting.get();
            if (current instanceof Boolean) setting.set(Boolean.valueOf(value));
            else if (current instanceof Integer) setting.set(Integer.valueOf(value));
            else if (current instanceof Float) setting.set(Float.valueOf(value));
        }
    }

    private static void write() {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("autobench-result.csv"),
                    "label,scene,pt,rr,composite,fps,n,csvStart,csvEnd,ptMin,rrMin,compMin\n" + out, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private PerfAutoBench() {}
}
