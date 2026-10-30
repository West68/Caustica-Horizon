package dev.comfyfluffy.caustica.enhanced.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.EnhancedGpuTimings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

/** Disposable fixture only: fixed camera, real user packs, native inverted crosshair and three POM modes. */
final class CompatibilitySmoke {
    private static int stage, wait;
    private static Future<?> saved;
    private static final AtomicInteger pending = new AtomicInteger();
    private static final Path out = Path.of(System.getProperty("ce.evidence"));
    static void start() { ClientTickEvents.END_CLIENT_TICK.register(CompatibilitySmoke::tick); }
    private static void commands(Minecraft mc, String... commands) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> { for (String cmd : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),cmd); });
    }
    private static void shot(Minecraft mc, String name) {
        pending.incrementAndGet();
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) { image.writeToFile(out.resolve(name + ".png")); }
            catch (Exception e) { throw new RuntimeException(e); }
            finally { pending.decrementAndGet(); }
        });
    }
    private static void tick(Minecraft mc) {
        try {
            mc.options.pauseOnLostFocus = false;
            if (mc.level != null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) mc.gui.setScreen(null);
            if (wait-- > 0) return;
            switch (stage) {
                case 0 -> {
                    if (mc.gui.screen()!=null && mc.gui.screen().getClass().getName().contains("UpdateModScreen")) mc.gui.setScreen(new TitleScreen());
                    if (!(mc.gui.screen() instanceof TitleScreen)) return;
                    mc.createWorldOpenFlows().openWorld("CE-work-fixture", () -> { throw new AssertionError("No isolated fixture"); });
                    stage++; wait=20;
                }
                case 1 -> {
                    if(mc.level==null || mc.player==null || mc.gui.screen()!=null) return;
                    mc.getWindow().setWindowed(1920,1200);
                    mc.options.enableVsync().set(false);
                    mc.options.framerateLimit().set(260);
                    org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());
                    CausticaConfig.Rt.Material.PARALLAX.set(0);
                    commands(mc,"fill -12 99 -12 12 99 12 stone_bricks","fill -6 100 0 6 105 0 stone_bricks",
                            "time set 6000","weather clear","gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false",
                            "gamemode creative @p","tp @p 0.5 100 4.5 180 12");
                    stage++; wait=220;
                }
                case 2 -> { shot(mc,"compat-off-cozy"); CausticaConfig.Rt.Material.PARALLAX.set(1); stage++;wait=120; }
                case 3 -> { shot(mc,"compat-parallax"); CausticaConfig.Rt.Material.PARALLAX.set(2); stage++;wait=120; }
                case 4 -> { shot(mc,"compat-smooth"); GpuTimingSnapshot.begin(); Cadence.begin(); stage++;wait=200; }
                case 5 -> {
                    Cadence.write(out.resolve("compat-cadence.csv")); GpuTimingSnapshot.write(out.resolve("compat-gpu.txt"));
                    var server=mc.getSingleplayerServer();
                    saved=server.submit(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"save-all flush"));
                    stage++;wait=5;
                }
                case 6 -> { if(!saved.isDone() || pending.get()!=0)return; saved.get();mc.disconnectWithSavingScreen();stage++;wait=20; }
                case 7 -> { if(mc.level!=null)return; Files.writeString(out.resolve("run-completed.json"),"{\"worldEntered\":true,\"normalWorldExit\":true,\"threeMaterialModes\":true,\"fg\":false}");stage++;mc.stop(); }
                default -> { }
            }
        } catch (Throwable e) {
            try { Files.writeString(out.resolve("compat-failure.txt"),e.toString()); } catch(Exception ignored) { }
            throw new RuntimeException(e);
        }
    }
}

