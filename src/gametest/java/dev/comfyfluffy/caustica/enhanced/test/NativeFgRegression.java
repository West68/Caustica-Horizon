package dev.comfyfluffy.caustica.enhanced.test;

import com.google.gson.JsonObject;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Real client loop; never uses Fabric GameTest's simulated focus or server/client phaser. */
final class NativeFgRegression {
    private record Step(String name, boolean fg, Consumer<Minecraft> apply) {}
    private static final Path OUT = Path.of(System.getProperty("ce.evidence"));
    private static final ArrayList<Step> steps = new ArrayList<>();
    private static int stage, stepIndex = -1, observed, activeSamples, submittedSamples, foregroundSamples, unverifiedFgSteps, correctMultiplierSamples;
    private static long deadline, firstFrame, startNanos, sampleNanos;
    private static long rrEvaluationsStart, srEvaluationsStart;
    private static long timelineWaitCountStart, timelineWaitNanosStart, terrainRevisionStart;
    private static int terrainInstancesStart, dhSectionsStart;
    private static long terrainBytesStart;
    private static long[] loopTimingStart;
    private static Instant startUtc;
    private static String failure = "";
    private static boolean reentered, viewportChanged, rrSceneBuilt;
    private static int measureWidth,measureHeight;
    private static java.util.concurrent.CompletableFuture<?> reload;
    private static java.util.concurrent.Future<?> saved;
    private static final StringBuilder states = new StringBuilder();

    private static long[] apiTimingStart;
    private static java.lang.invoke.MethodHandle getForegroundWindow;
    private static boolean isForeground(Minecraft mc) throws Throwable {
        if(getForegroundWindow==null) {
            var user32=java.lang.foreign.SymbolLookup.libraryLookup("user32.dll",java.lang.foreign.Arena.global());
            getForegroundWindow=java.lang.foreign.Linker.nativeLinker().downcallHandle(
                user32.find("GetForegroundWindow").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS));
        }
        long foreground=((java.lang.foreign.MemorySegment)getForegroundWindow.invokeExact()).address();
        return foreground==org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().handle());
    }

    static void start() { ClientTickEvents.END_CLIENT_TICK.register(NativeFgRegression::tick); }
    private static void json(String name, JsonObject data) throws Exception {
        Files.writeString(OUT.resolve(name + ".json"), data.toString());
    }
    private static void commands(Minecraft mc, String... cmds) {
        var server=mc.getSingleplayerServer();
        server.execute(()->{for(var cmd:cmds) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),cmd);});
    }
    private static void requestGeneratedCount(int count) {
        if(Boolean.getBoolean("ce.menuFg")) {
            var options=dev.comfyfluffy.caustica.client.RtVideoOptions.categoryOptions("fg");
            @SuppressWarnings("unchecked") var option=(net.minecraft.client.OptionInstance<Integer>)options[options.length-1];
            option.set(count);
            if(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value()!=count || !CausticaConfig.Rt.Fg.ENABLED.value())
                throw new AssertionError("Menu request not applied: expected "+count+" generated frame(s), actual request "+CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value());
        } else CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(count);
    }
    private static final String SCENE=System.getProperty("ce.sceneTp","-40.57037839689626 80 374.25722861887783 36.6746826171875 42.36389923095703").replace('_',' ');
    private static Runnable restoreKnobs=()->{};
    /** One knob step: restore every knob to the user's value, then change only this one. */
    private static <T> Step knob(String label,boolean fg,Runnable change) {
        return new Step(label,fg,m->{restoreKnobs.run();change.run();});
    }
    private static void configure(Minecraft mc) throws Exception {
        Files.createDirectories(OUT);
        var origin=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("caustica").orElseThrow().getOrigin().getPaths().getFirst();
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(origin)));
        if(!sha.equalsIgnoreCase(System.getProperty("ce.expectedSha"))) throw new AssertionError("Wrong renderer jar");
        Files.writeString(OUT.resolve("renderer-origin.txt"),origin+"\n"+sha);
        mc.options.pauseOnLostFocus=false;
        mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);
        mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        if(!Boolean.getBoolean("ce.preserveScene")) mc.options.fov().set(70);
        mc.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080));
        // Exercise Minecraft's own fullscreen transition, never desktop focus/input automation.
        if(Boolean.getBoolean("ce.startFullscreen")&&!mc.getWindow().isFullscreen())mc.getWindow().toggleFullScreen();
        CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(Integer.getInteger("ce.generatedCount",1));CausticaConfig.Rt.Fg.ENABLED.set(false);
        if(!Boolean.getBoolean("ce.preserveScene")) { CausticaConfig.Rt.DlssRr.QUALITY.set(Integer.getInteger("ce.quality",0));CausticaConfig.Rt.DlssRr.SR_ONLY.set(false);CausticaConfig.Rt.DlssRr.ENABLED.set(true); }
        if(!Boolean.getBoolean("ce.preserveScene")) commands(mc,"time set 6000","weather clear","gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false","tp @p "+SCENE);
        if(Boolean.getBoolean("ce.stableScene")) commands(mc,"gamemode spectator @p","tick freeze","tp @p "+SCENE);
        if(Boolean.getBoolean("ce.rrScene")) commands(mc,
            "execute in minecraft:the_end run fill 40 179 40 56 179 56 minecraft:red_nether_bricks",
            "execute in minecraft:the_end run fill 40 180 40 40 185 56 minecraft:white_concrete",
            "execute in minecraft:the_end run fill 41 180 40 56 185 40 minecraft:red_concrete",
            "execute in minecraft:the_end run fill 56 180 41 56 185 56 minecraft:blue_concrete",
            "execute in minecraft:the_end run setblock 48 183 41 minecraft:glowstone",
            "execute in minecraft:the_end run tp @p 48.5 181 55.5 180 35");
        if(Boolean.getBoolean("ce.scene2"))commands(mc,"tp @p -40.57037839689626 80 374.25722861887783 146.6746826171875 42.36389923095703");
        for(String label:new String[]{"off-a","on-a","off-b","on-b"})
            steps.add(new Step(label,label.startsWith("on"), m->{
                if(label.equals("off-a") && Boolean.getBoolean("ce.startFullscreen") && !Boolean.getBoolean("ce.keepFullscreen") && m.getWindow().isFullscreen()) {
                    m.getWindow().toggleFullScreen();m.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080));
                }
            }));
        if(Boolean.getBoolean("ce.rrOnly")) steps.removeIf(step->step.fg);
        if(Boolean.getBoolean("ce.perf2k")) {
            // Target-condition breakdown: FG multipliers, then the cost of each visual system (FG Off),
            // then the baseline again to expose thermal/clock drift. Only the step's own knob differs.
            steps.clear();
            boolean dh=CausticaConfig.Rt.Visuals.DH_LODS.value(),waves=CausticaConfig.Rt.Composite.WATER_WAVES.value(),cache=CausticaConfig.Rt.Composite.RADIANCE_CACHE.value(),psr=CausticaConfig.Rt.Composite.MIRROR_PSR.value();
            int clouds=CausticaConfig.Rt.Clouds.MODE.value(),parallax=CausticaConfig.Rt.Material.PARALLAX.value(),bounces=CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
            float bloom=CausticaConfig.Rt.Look.BLOOM.value(),sharpen=CausticaConfig.Rt.Look.DETAIL_SHARPEN.value(),rims=CausticaConfig.Rt.Look.SUN_RIMS.value();
            restoreKnobs=()->{CausticaConfig.Rt.Visuals.DH_LODS.set(dh);CausticaConfig.Rt.Composite.WATER_WAVES.set(waves);CausticaConfig.Rt.Composite.RADIANCE_CACHE.set(cache);CausticaConfig.Rt.Composite.MIRROR_PSR.set(psr);
                CausticaConfig.Rt.Clouds.MODE.set(clouds);CausticaConfig.Rt.Material.PARALLAX.set(parallax);CausticaConfig.Rt.Composite.MAX_BOUNCES.set(bounces);
                CausticaConfig.Rt.Look.BLOOM.set(bloom);CausticaConfig.Rt.Look.DETAIL_SHARPEN.set(sharpen);CausticaConfig.Rt.Look.SUN_RIMS.set(rims);};
            steps.add(knob("p-off",false,()->{}));
            for(int count:new int[]{1,2,3})steps.add(knob("p-mfg-"+(count+1)+"x",true,()->requestGeneratedCount(count)));
            steps.add(knob("k-dh-off",false,()->CausticaConfig.Rt.Visuals.DH_LODS.set(false)));
            steps.add(knob("k-clouds-perf",false,()->CausticaConfig.Rt.Clouds.MODE.set(1)));
            steps.add(knob("k-clouds-off",false,()->CausticaConfig.Rt.Clouds.MODE.set(0)));
            steps.add(knob("k-parallax-off",false,()->CausticaConfig.Rt.Material.PARALLAX.set(0)));
            steps.add(knob("k-psr-off",false,()->CausticaConfig.Rt.Composite.MIRROR_PSR.set(false)));
            steps.add(knob("k-waves-off",false,()->CausticaConfig.Rt.Composite.WATER_WAVES.set(false)));
            steps.add(knob("k-cache-off",false,()->CausticaConfig.Rt.Composite.RADIANCE_CACHE.set(false)));
            steps.add(knob("k-bloom-sharpen-off",false,()->{CausticaConfig.Rt.Look.BLOOM.set(0f);CausticaConfig.Rt.Look.DETAIL_SHARPEN.set(0f);}));
            steps.add(knob("k-rims-off",false,()->CausticaConfig.Rt.Look.SUN_RIMS.set(0f)));
            steps.add(knob("k-bounces-3",false,()->CausticaConfig.Rt.Composite.MAX_BOUNCES.set(3)));
            steps.add(knob("p-off-again",false,()->{}));
            steps.add(knob("p-mfg-4x-again",true,()->requestGeneratedCount(3)));
        }
        if(Boolean.getBoolean("ce.perf2kScenes")) {
            // Open-air scene, then the torch-lit cave (emitters, entities, block entities): FG Off and 4x each.
            steps.clear();
            steps.add(new Step("p-off",false,m->{}));
            steps.add(new Step("p-mfg-4x",true,m->requestGeneratedCount(3)));
            String cave=System.getProperty("ce.caveTp","-163.3_71.9_1394.4_-150_25").replace('_',' ');
            steps.add(new Step("c-off",false,m->commands(m,"tp @p "+cave)));
            steps.add(new Step("c-mfg-4x",true,m->{commands(m,"tp @p "+cave);requestGeneratedCount(3);}));
            steps.add(new Step("c-mfg-3x",true,m->{commands(m,"tp @p "+cave);requestGeneratedCount(2);}));
        }
        if(Boolean.getBoolean("ce.perf2kSky")) {
            // Open-sky scene at 4x: diffuse continuations from the exact sky vs the per-frame sky LUT, A/B/A/B.
            steps.clear();
            for(String label:new String[]{"sky-exact-1","sky-lut-1","sky-exact-2","sky-lut-2"})
                steps.add(new Step(label,true,m->{CausticaConfig.Rt.Composite.SKY_LUT.set(label.contains("lut"));requestGeneratedCount(3);}));
            steps.add(new Step("sky-exact-off",false,m->CausticaConfig.Rt.Composite.SKY_LUT.set(false)));
            steps.add(new Step("sky-lut-off",false,m->CausticaConfig.Rt.Composite.SKY_LUT.set(true)));
        }
        if(Boolean.getBoolean("ce.bpmScreen")) {
            // Reproduce Alt+Y: open Better Player Model's model browser (lazy model loading) under RT.
            steps.clear();
            steps.add(new Step("bpm-screen",false,m->{try{m.gui.setScreen((net.minecraft.client.gui.screens.Screen)Class.forName("com.elfmcys.yesstevemodel.client.gui.PlayerModelScreen").getConstructor().newInstance());}catch(Exception e){throw new RuntimeException(e);}}));
        }
        if(Boolean.getBoolean("ce.diagRun")) {
            // One short run: walking mob pose trace, campfire animation, DH refinement, 1200p -> 1440p resize.
            steps.clear();
            steps.add(new Step("mob",false,m->commands(m,"gamemode creative @p","tp @p -163.5 71 1393.5 180 20","summon zoglin -163.5 71 1388.5")));
            steps.add(new Step("fire-a",false,m->commands(m,"kill @e[type=zoglin]","tp @p -164.5 72 1392.5 180 45")));
            steps.add(new Step("fire-b",false,m->{}));
            steps.add(new Step("r1200",false,m->m.getWindow().setWindowed(1920,1200)));
            steps.add(new Step("r1440",false,m->m.getWindow().setWindowed(2560,1440)));
        }
        if(Boolean.getBoolean("ce.mobAb")) {
            // Same walking pigs, three capture modes, per-frame pose trace in the log.
            steps.clear();
            steps.add(new Step("pig-replay",false,m->{if(Integer.getInteger("ce.fpsCap",0)>0)m.options.framerateLimit().set(Integer.getInteger("ce.fpsCap",0));commands(m,"gamemode survival @p","tp @p 168.4 75 113.1 180 25","item replace entity @p weapon.mainhand with carrot","summon pig 168.5 74 104.5","summon pig 170.5 74 103.5","summon pig 166.5 74 103.5");System.setProperty("caustica.test.emfTemplate","false");System.setProperty("caustica.test.legacyEmfReplay","false");}));
            if(!Boolean.getBoolean("ce.mobOnly")){steps.add(new Step("pig-template",false,m->{System.setProperty("caustica.test.emfTemplate","true");}));
            steps.add(new Step("pig-legacy",false,m->{System.setProperty("caustica.test.emfTemplate","false");System.setProperty("caustica.test.legacyEmfReplay","true");}));}
            if(!Boolean.getBoolean("ce.mobOnly"))steps.add(new Step("fire-a",false,m->{System.setProperty("caustica.test.legacyEmfReplay","false");commands(m,"kill @e[type=pig,distance=..20]","tp @p -164.5 72.6 1392.3 180 50");}));
            steps.add(new Step("fire-b",false,m->{}));
            steps.add(new Step("r1200",false,m->m.getWindow().setWindowed(1920,1200)));
            steps.add(new Step("r1440",false,m->m.getWindow().setWindowed(2560,1440)));
        }
        if(Boolean.getBoolean("ce.mobShots")) {
            // Pigs walk toward the carrot holder; a capture per step shows facing and gait.
            steps.clear();
            steps.add(new Step("walk-0",false,m->commands(m,"gamemode survival @p","tp @p 168.4 75 116.1 180 20","item replace entity @p weapon.mainhand with carrot","kill @e[type=pig,distance=..30]","summon pig 167.5 74 101.5","summon pig 169.5 74 100.5")));
            for(int k=1;k<=6;k++)steps.add(new Step("walk-"+k,false,m->{}));
        }
        if(Boolean.getBoolean("ce.perf2kShort")) {
            // Generated-frame cost vs output size under the same VRAM state.
            steps.clear();
            steps.add(new Step("p-off",false,m->{}));
            steps.add(new Step("p-mfg-4x",true,m->requestGeneratedCount(3)));
            steps.add(new Step("r1080-off",false,m->m.getWindow().setWindowed(1920,1080)));
            steps.add(new Step("r1080-mfg-4x",true,m->requestGeneratedCount(3)));
            steps.add(new Step("p-mfg-4x-back",true,m->{m.getWindow().setWindowed(2560,1440);requestGeneratedCount(3);}));
        }
        if(Boolean.getBoolean("ce.guideAb")) {
            steps.clear();
            for(String label:new String[]{"guide-reference-a","guide-fast-a","guide-fast-b","guide-reference-b"})
                steps.add(new Step(label,false,m->System.setProperty("caustica.test.fullReflectionGuides",Boolean.toString(label.contains("reference")))));
        }
        if(Boolean.getBoolean("ce.basicSoak"))steps.add(new Step("soak",true,m->{}));
        if(Boolean.getBoolean("ce.freezeWeather"))commands(mc,"gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false");
        if(Boolean.getBoolean("ce.mfgSweep")) {
            if(Boolean.getBoolean("ce.mfgSweepOnly"))steps.clear();
            for(int count:new int[]{2,3,1,2})steps.add(new Step("mfg-"+(count+1)+"x-"+steps.size(),true,
                m->{System.setProperty("caustica.test.fullReflectionGuides","false");requestGeneratedCount(count);}));
            steps.add(new Step("soak",true,m->{}));
        }
        if(Boolean.getBoolean("ce.extraScene")) {
            for(String label:new String[]{"off-a","on-a","off-b","on-b"}) {
                steps.add(new Step("scene2-"+label,label.startsWith("on"),m->commands(m,"tp @p -40.57037839689626 80 374.25722861887783 146.6746826171875 42.36389923095703")));
            }
        }
        if(Boolean.getBoolean("ce.extendedFg")) {
            for(int quality:new int[]{2,1,3,5,0})steps.add(new Step("quality-"+quality,true,m->CausticaConfig.Rt.DlssRr.QUALITY.set(quality)));
            for(int preset:new int[]{0,10,11,12,13})steps.add(new Step("sr-preset-"+preset,true,m->{CausticaConfig.Rt.DlssRr.SR_ONLY.set(true);CausticaConfig.Rt.DlssRr.SR_PRESET.set(preset);}));
            for(int quality:new int[]{2,1,3,5,0})steps.add(new Step("sr-quality-"+quality,true,m->CausticaConfig.Rt.DlssRr.QUALITY.set(quality)));
            for(int preset:new int[]{4,5,6,0})steps.add(new Step("rr-preset-"+preset,true,m->{CausticaConfig.Rt.DlssRr.SR_ONLY.set(false);CausticaConfig.Rt.DlssRr.PRESET.set(preset);}));
            steps.add(new Step("reconstruction-off",true,m->CausticaConfig.Rt.DlssRr.ENABLED.set(false)));
            steps.add(new Step("reconstruction-restored",true,m->CausticaConfig.Rt.DlssRr.ENABLED.set(true)));
            steps.add(new Step("resize-small",true,m->m.getWindow().setWindowed(1280,720)));
            steps.add(new Step("resize-restored",true,m->m.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080))));
            steps.add(new Step("maximized",true,m->org.lwjgl.glfw.GLFW.glfwMaximizeWindow(m.getWindow().handle())));
            steps.add(new Step("restored",true,m->{org.lwjgl.glfw.GLFW.glfwRestoreWindow(m.getWindow().handle());m.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080));}));
            steps.add(new Step("fullscreen",true,m->m.getWindow().toggleFullScreen()));
            steps.add(new Step("windowed",true,m->{m.getWindow().toggleFullScreen();m.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080));}));
            steps.add(new Step("resource-reload",true,m->reload=m.reloadResourcePacks()));
            steps.add(new Step("post-reload-off",false,m->{}));
            steps.add(new Step("post-reload-on",true,m->{}));
            steps.add(new Step("soak",true,m->{}));
        }
        if (Boolean.getBoolean("ce.resizeMatrix")) {
            // FG Off only: same scene measured at the launch size, after growing the window, after shrinking
            // back and after growing again, to compare a resized large window with its own repeat.
            int w=Integer.getInteger("ce.resizeWidth",2560),h=Integer.getInteger("ce.resizeHeight",1440);
            steps.clear();
            // ce.resizeFgGenerated>0 repeats the same window cycle with frame generation on (1 = 2x, 3 = 4x).
            int gen=Integer.getInteger("ce.resizeFgGenerated",0);
            boolean fg=gen>0;
            String p=fg?"fg-":"off-";
            steps.add(new Step(p+"start", fg, m -> {if(fg)requestGeneratedCount(gen);}));
            steps.add(new Step(p+"grown", fg, m -> m.getWindow().setWindowed(w,h)));
            steps.add(new Step(p+"shrunk", fg, m -> m.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080))));
            steps.add(new Step(p+"grown-again", fg, m -> m.getWindow().setWindowed(w,h)));
            for(int c=3;c<=Integer.getInteger("ce.resizeCycles",2);c++){
                steps.add(new Step(p+"shrunk-"+c, fg, m -> m.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080))));
                steps.add(new Step(p+"grown-"+c, fg, m -> m.getWindow().setWindowed(w,h)));
            }
        }
        if (Boolean.getBoolean("ce.mirrorTest")) {
            // Red nether brick wall (the installed mirror pack: smoothness 255, metal) 23 blocks ahead; PSR off/on, HDR readback per step.
            steps.clear();
            for (String label : new String[]{"m-off-1","m-off-2","m-off-3","m-off-4","m-off-5","m-on-1","m-on-2","m-on-3","m-on-4","m-on-5"})
                steps.add(new Step(label, false, m -> {
                    commands(m,"gamemode spectator @p","tick freeze","fill -52 92 398 -29 112 398 minecraft:red_nether_bricks","tp @p -40.5 101 375.5 0 8");
                    pin=new double[]{-40.5,101,375.5,0,8};
                    CausticaConfig.Rt.Clouds.MODE.set(0); // moving cloud shadows would swamp the temporal noise measure
                    CausticaConfig.Rt.Composite.MIRROR_PSR.set(label.contains("-on"));
                }));
        }
        if (Boolean.getBoolean("ce.leakTest")) {
            // Sealed 1-block stone shell (crosses chunk and section borders) under the noon sun: any light
            // inside is a leak. Spectator camera at the centre, 5 cm from a wall and inside the wall.
            steps.clear();
            String[][] views = {
                {"lk-center-c", "tp @p -50.5 123.6 390.5 -90 10", "1"},
                {"lk-center-n", "tp @p -50.5 123.6 390.5 -90 10", "0"},
                {"lk-wall-c", "tp @p -68.95 123.6 390.5 -30 5", "1"},
                {"lk-wall-n", "tp @p -68.95 123.6 390.5 -30 5", "0"},
                {"lk-inwall-c", "tp @p -69.5 123.6 390.5 -90 5", "1"},
                {"lk-ceil-c", "tp @p -50.5 127.95 390.5 -90 -5", "1"}};
            for (String[] v : views)
                steps.add(new Step(v[0], false, m -> {
                    commands(m, "gamemode spectator @p", "tick freeze", "time set 6000",
                            "fill -70 120 370 -31 128 409 minecraft:stone hollow", v[1]);
                    CausticaConfig.Rt.Composite.RADIANCE_CACHE.set(v[2].equals("1"));
                }));
        }
        if (Boolean.getBoolean("ce.caveTest")) {
            // Air pocket carved 30+ blocks deep in natural rock. tp sets the feet; the eye is 1.62 higher.
            steps.clear();
            String[][] views = {
                {"cv-center-c", "tp @p -40.5 31 390.5 0 0", "1"},
                {"cv-center-n", "tp @p -40.5 31 390.5 0 0", "0"},
                {"cv-wall-c", "tp @p -44.95 31 390.5 -30 0", "1"},
                {"cv-inwall-c", "tp @p -45.3 31 390.5 -90 0", "1"},
                {"cv-ceil-c", "tp @p -40.5 33.33 390.5 0 -10", "1"},
                {"cv-inceil-c", "tp @p -40.5 33.6 390.5 0 -10", "1"}};
            for (String[] v : views)
                steps.add(new Step(v[0], false, m -> {
                    commands(m, "gamemode spectator @p", "tick freeze", "time set 6000",
                            "fill -45 30 380 -35 34 400 minecraft:air", v[1]);
                    CausticaConfig.Rt.Composite.RADIANCE_CACHE.set(v[2].equals("1"));
                }));
        }
        if (Boolean.getBoolean("ce.coverShot")) {
            // Cover candidates: one scenic spot at several sun angles, DLAA, frozen world, HDR readback each.
            steps.clear();
            String[][] shots = {
                {"cover-morning-e", "time set 600", "tp @p -50.5 124 390.5 -90 6"},
                {"cover-evening-e", "time set 11600", "tp @p -50.5 124 390.5 -90 6"},
                {"cover-noon-e", "time set 5000", "tp @p -50.5 124 390.5 -90 8"},
                {"cover-sunset-w", "time set 12150", "tp @p -50.5 124 390.5 90 4"},
                {"cover-evening-ne", "time set 11400", "tp @p -68.5 124 390.5 -30 6"}};
            for (String[] v : shots)
                steps.add(new Step(v[0], false, m -> {
                    CausticaConfig.Rt.DlssRr.QUALITY.set(5);
                    // Remove the structures earlier test modes built in the replica world.
                    commands(m, "gamemode spectator @p", "tick freeze", "fill -70 120 370 -31 128 409 minecraft:air",
                            "fill -52 92 398 -29 112 398 minecraft:air", v[1], v[2]);
                    String[] t = v[2].split(" ");
                    pin = new double[]{Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]), Double.parseDouble(t[6])};
                }));
        }
        if (Boolean.getBoolean("ce.uiShot")) {
            // Opens each Caustica settings page and grabs a normal game screenshot of the previous one.
            steps.clear();
            String[] pages = {"modmenu", "search", "hold", "end"};
            for (int i = 0; i < pages.length; i++) {
                String page = pages[i];
                boolean grab = i > 0;
                steps.add(new Step("ui-" + page, false, m -> {
                    if (page.equals("search")) {
                        // Type into Mod Menu's search box so the Caustica entry is listed first.
                        for (Class<?> c = m.gui.screen().getClass(); c != null; c = c.getSuperclass())
                            for (var fld : c.getDeclaredFields())
                                if (net.minecraft.client.gui.components.EditBox.class.isAssignableFrom(fld.getType())) {
                                    try { fld.setAccessible(true); ((net.minecraft.client.gui.components.EditBox) fld.get(m.gui.screen())).setValue("Caustica"); }
                                    catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                                }
                        return;
                    }
                    if (page.equals("hold")) { net.minecraft.client.Screenshot.grab(m, false); return; } // capture lands next frame: keep the screen
                    if (page.equals("modmenu")) {
                        try {
                            m.gui.setScreen((net.minecraft.client.gui.screens.Screen) Class.forName("com.terraformersmc.modmenu.gui.ModsScreen")
                                    .getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null));
                        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                    } else if (!page.equals("end")) m.gui.setScreen(new dev.comfyfluffy.caustica.client.EnhancedOptionsScreen(null, page));
                    else m.gui.setScreen(null);
                }));
            }
        }
        if (Boolean.getBoolean("ce.glassShot")) {
            // Showcase: white room high in the sky under a rainbow stained-glass roof; sunlight throws coloured
            // patches over floor and walls, a little air fog makes the coloured shafts visible.
            steps.clear();
            String[] colors = {"red", "orange", "yellow", "lime", "cyan", "light_blue", "blue", "purple", "magenta", "pink"};
            java.util.List<String> build = new java.util.ArrayList<>(java.util.List.of(
                    "gamemode spectator @p", "tick freeze",
                    "fill -62 150 378 -38 160 402 minecraft:air",
                    "fill -60 150 380 -40 158 400 minecraft:white_concrete hollow",
                    "fill -59 151 381 -41 151 399 minecraft:smooth_quartz",
                    "setblock -52 152 392 minecraft:quartz_pillar", "setblock -52 153 392 minecraft:quartz_pillar",
                    "setblock -46 152 386 minecraft:white_concrete", "setblock -55 152 387 minecraft:glass",
                    "setblock -48 152 395 minecraft:sea_lantern"));
            // Separate rainbow panes in the east wall; the low morning sun throws distinct coloured shafts
            // across the dark room onto the floor and the west wall.
            for (int i = 0; i < colors.length; i++) {
                int z = 381 + i * 2;
                build.add("fill -40 154 " + z + " -40 156 " + z + " minecraft:" + colors[i] + "_stained_glass");
            }
            String[][] shots = {
                {"glass-a", "time set 700", "-58.5 151 398.5 -128 12", "4"},
                {"glass-b", "time set 1000", "-58.5 151 382.5 -55 14", "4"},
                {"glass-c", "time set 700", "-57.5 152 390.5 -95 6", "4"},
                {"glass-d", "time set 1300", "-50.5 151 399.0 -150 22", "3"}};
            for (String[] v : shots)
                steps.add(new Step(v[0], false, m -> {
                    CausticaConfig.Rt.DlssRr.QUALITY.set(5);
                    CausticaConfig.Rt.Visuals.FOG.set(Float.parseFloat(v[3]));
                    commands(m, build.toArray(String[]::new));
                    String[] t = v[2].split(" ");
                    commands(m, v[1], "tp @p " + v[2]);
                    pin = new double[]{Double.parseDouble(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4])};
                }));
        }
        if (Boolean.getBoolean("ce.fxTest")) {
            // ITRP detail sharpen + sunlight rims off/on, backlit morning view, pinned camera, HDR readback per step.
            steps.clear();
            for (String label : new String[]{"fx-off-1", "fx-sharp-1", "fx-rims-1", "fx-on-1", "fx-off-2", "fx-sharp-2", "fx-rims-2", "fx-on-2"})
                steps.add(new Step(label, false, m -> {
                    commands(m, "gamemode spectator @p", "tick freeze", "time set 900",
                            "fill -62 150 378 -38 160 402 minecraft:air", "tp @p -50.5 124 390.5 -90 6");
                    pin = new double[]{-50.5, 124, 390.5, -90, 6};
                    m.mouseHandler.releaseMouse(); // stray mouse movement must not turn the camera
                    // Fixed exposure and no clouds: steps differ only in the effect under test.
                    CausticaConfig.Rt.Clouds.MODE.set(0);
                    CausticaConfig.Rt.Exposure.MODE.set("manual");
                    CausticaConfig.Rt.Exposure.MANUAL_EV.set(0f);
                    float sharp = label.contains("-on") || label.contains("-sharp") ? 1f : 0f;
                    float rims = label.contains("-on") || label.contains("-rims") ? 1f : 0f;
                    try { CausticaConfig.Rt.Look.DETAIL_SHARPEN.set(sharp); CausticaConfig.Rt.Look.SUN_RIMS.set(rims); }
                    catch (NoSuchFieldError older) { } // older renderer: same view, its own defaults
                }));
        }
        if (Boolean.getBoolean("ce.mobTest")) {
            // Warden + Enderman a few blocks ahead in daylight; HDR readback per step for glow comparison.
            steps.clear();
            for (String label : new String[]{"mob-1", "mob-2", "mob-3"})
                steps.add(new Step(label, false, m -> {
                    if (label.equals("mob-1")) commands(m, "gamemode spectator @p", "time set 6000", "kill @e[type=!player]",
                            "fill -62 150 370 -30 165 402 minecraft:air", "fill -60 150 372 -32 150 400 minecraft:smooth_quartz",
                            "summon warden -47.5 151 386.5 {NoAI:1b,Rotation:[180f,0f]}",
                            "summon enderman -43.5 151 386.5 {Rotation:[180f,0f]}");
                    commands(m, "tp @p -45.5 151.2 378.5 0 8");
                    pin = new double[]{-45.5, 151.2, 378.5, 0, 8};
                    m.mouseHandler.releaseMouse();
                }));
        }
        if (Boolean.getBoolean("ce.fly")) {
            steps.clear();
            for (String label : new String[]{"fly-1","fly-2"}) steps.add(new Step(label, false, m -> commands(m,"gamemode spectator @p")));
        }
        if (System.getProperty("ce.abSweep") != null) {
            // Same-session A/B/A/B of one system property (read per frame by the renderer); the camera is
            // re-pinned at every step so all four windows see the identical view.
            String[] ab = System.getProperty("ce.abSweep").split(":");
            steps.clear();
            for (String label : new String[]{"a-1","b-1","a-2","b-2","a-3","b-3"})
                steps.add(new Step(label, false, m -> {
                    System.setProperty(ab[0], label.startsWith("a") ? ab[1] : ab[2]);
                    if(Boolean.getBoolean("ce.stableScene"))commands(m,"tp @p "+SCENE);
                }));
        }
        if (Boolean.getBoolean("ce.tileSweep")) {
            steps.clear();
            for (String label : new String[]{"t5-1","t8-1","t5-2","t8-2"})
                steps.add(new Step(label, false, m -> System.setProperty("caustica.rt.radianceCacheTile", label.substring(1, 2))));
        }
        if (Boolean.getBoolean("ce.skyLutSweep")) {
            steps.clear();
            for (String label : new String[]{"s-off-1","s-on-1","s-off-2","s-on-2"})
                steps.add(new Step(label, false, m -> CausticaConfig.Rt.Composite.SKY_LUT.set(label.contains("-on"))));
        }
        if (Boolean.getBoolean("ce.cacheSweep")) {
            // Same session, FG Off: radiance cache off/on twice, HDR readback per step for image comparison.
            steps.clear();
            for (String label : new String[]{"c-off-1","c-on-1","c-off-2","c-on-2"})
                steps.add(new Step(label, false, m -> CausticaConfig.Rt.Composite.RADIANCE_CACHE.set(label.contains("-on"))));
        }
        if (Boolean.getBoolean("ce.qualitySweep")) {
            // FG Off, fixed camera: each step changes one cost knob; HDR readbacks allow image comparison.
            steps.clear();
            steps.add(new Step("q-base", false, m -> {}));
            steps.add(new Step("q-bounces3", false, m -> CausticaConfig.Rt.Composite.MAX_BOUNCES.set(3)));
            steps.add(new Step("q-bounces2", false, m -> CausticaConfig.Rt.Composite.MAX_BOUNCES.set(2)));
            steps.add(new Step("q-base-again", false, m -> CausticaConfig.Rt.Composite.MAX_BOUNCES.set(4)));
        }
        if (Boolean.getBoolean("ce.residencyMatrix")) {
            steps.clear();
            steps.add(new Step("off-a", false, m -> {}));
            for (int count : new int[]{1, 2, 3})
                steps.add(new Step("mfg-" + (count + 1) + "x", true, m -> requestGeneratedCount(count)));
            steps.add(new Step("off-b", false, m -> {}));
            if (Boolean.getBoolean("ce.residencyTransitions")) {
                steps.add(new Step("sr-performance", false, m -> CausticaConfig.Rt.DlssRr.SR_ONLY.set(true)));
                steps.add(new Step("rr-restored", false, m -> CausticaConfig.Rt.DlssRr.SR_ONLY.set(false)));
                steps.add(new Step("resize-small", false, m -> m.getWindow().setWindowed(1280,720)));
                steps.add(new Step("resize-restored", false, m -> m.getWindow().setWindowed(1920,1080)));
                steps.add(new Step("mfg-4x-restored", true, m -> requestGeneratedCount(3)));
            }
        }
        if(Boolean.getBoolean("ce.mirrorAfterMfg")) {
            for(String label:new String[]{"mirror-reference-a","mirror-fast-a","mirror-fast-b","mirror-reference-b"})
                steps.add(new Step(label,false,m->{
                    if(label.equals("mirror-reference-a")) {
                        System.setProperty("ce.rrScene","true");
                        commands(m,"execute in minecraft:the_end run tp @p 48.5 181 55.5 180 35");
                    }
                    System.setProperty("caustica.test.fullReflectionGuides",Boolean.toString(label.contains("reference")));
                }));
        }
        String from=System.getProperty("ce.stepFrom", "");
        if(!from.isBlank()) {
            int first=-1;for(int i=0;i<steps.size();i++)if(steps.get(i).name.equals(from)){first=i;break;}
            if(first<0)throw new IllegalArgumentException("Unknown regression step: "+from);
            steps.subList(0,first).clear();
        }
        String through=System.getProperty("ce.stepTo", "");
        if(!through.isBlank()) {
            int last=-1;for(int i=0;i<steps.size();i++)if(steps.get(i).name.equals(through)){last=i;break;}
            if(last<0)throw new IllegalArgumentException("Unknown regression final step: "+through);
            steps.subList(last+1,steps.size()).clear();
        }
        String until=System.getProperty("ce.stepUntil", "");
        if(!until.isBlank()) {
            int last=-1;for(int i=0;i<steps.size();i++)if(steps.get(i).name.equals(until)){last=i;break;}
            if(last<0)throw new IllegalArgumentException("Unknown final regression step: "+until);
            steps.subList(last+1,steps.size()).clear();
        }
        try {
            // Real block-atlas occupancy: the stitcher rounds each side up to a power of two.
            var atlas=(net.minecraft.client.renderer.texture.TextureAtlas)mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            int maxX=0,maxY=0;long area=0;int count=0;
            for(var sprite:((dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor)atlas).caustica$sprites()){
                int w=sprite.contents().width(),h=sprite.contents().height();
                maxX=Math.max(maxX,sprite.getX()+w);maxY=Math.max(maxY,sprite.getY()+h);area+=(long)w*h;count++;}
            Files.writeString(OUT.resolve("atlas-occupancy.txt"),"sprites="+count+" area="+area+" maxX="+maxX+" maxY="+maxY+" texture="+atlas.getTexture().getWidth(0)+"x"+atlas.getTexture().getHeight(0));
        } catch(Throwable t){Files.writeString(OUT.resolve("atlas-occupancy.txt"),t.toString());}
        var conditions=new JsonObject();conditions.addProperty("bounces",CausticaConfig.Rt.Composite.MAX_BOUNCES.value());conditions.addProperty("spp",CausticaConfig.Rt.Composite.SPP.value());conditions.addProperty("quality",CausticaConfig.Rt.DlssRr.QUALITY.value());conditions.addProperty("requestedWidth",Integer.getInteger("ce.width",1920));conditions.addProperty("requestedHeight",Integer.getInteger("ce.height",1080));conditions.addProperty("preserveScene",Boolean.getBoolean("ce.preserveScene"));conditions.addProperty("gametestThreading",false);json("conditions",conditions);
        deadline=System.nanoTime()+Integer.getInteger("ce.initialWarmupSeconds",45)*1_000_000_000L;stage=2;
    }
    private static void next(Minecraft mc) throws Exception {
        if(++stepIndex==steps.size()){finishWorld(mc);return;}
        if(stepIndex==0 && Boolean.getBoolean("ce.startFullscreen") && !Boolean.getBoolean("ce.keepFullscreen") && mc.getWindow().isFullscreen()) {
            mc.getWindow().toggleFullScreen();mc.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080));
        }
        if(Boolean.getBoolean("ce.windowCycle")) {
            // The game's own fullscreen transitions re-establish the live swapchain/foreground state.
            // No desktop automation or fabricated focus flags.
            mc.getWindow().toggleFullScreen();mc.getWindow().toggleFullScreen();
            mc.getWindow().setWindowed(Integer.getInteger("ce.width",1920),Integer.getInteger("ce.height",1080));
        }
        Step step=steps.get(stepIndex);step.apply.accept(mc);
        if(Boolean.getBoolean("ce.menuFg")) {
            var options=dev.comfyfluffy.caustica.client.RtVideoOptions.categoryOptions("fg");
            @SuppressWarnings("unchecked") var option=(net.minecraft.client.OptionInstance<Integer>)options[options.length-1];
            option.set(step.fg?CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value():0);
        } else CausticaConfig.Rt.Fg.ENABLED.set(step.fg);
        var phase=new JsonObject();phase.addProperty("step",step.name);phase.addProperty("phase","warmup");json("phase",phase);
        // Ask GLFW for focus on this game's own window, without synthetic keys or desktop control.
        // A denied request remains visible in native/ETW evidence; never fake GLFW focus state.
        if(Boolean.getBoolean("ce.requestOwnWindowFocus")) org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());
        deadline=System.nanoTime()+(step.name.equals("mirror-reference-a")?60:Integer.getInteger("ce.warmupSeconds",10))*1_000_000_000L;stage=3;
    }
    private static void finishWorld(Minecraft mc) throws Exception {
        CausticaConfig.Rt.Fg.ENABLED.set(false);
        var server=mc.getSingleplayerServer();saved=server.submit(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"save-all flush"));stage=5;
    }
    private static void vram(JsonObject data) {
        var ctx=dev.comfyfluffy.caustica.rt.RtContext.currentOrNull();if(ctx==null)return;
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var pp=stack.mallocPointer(1);org.lwjgl.util.vma.Vma.vmaGetMemoryProperties(ctx.vma(),pp);
            var memory=org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.create(pp.get(0));
            var heaps=org.lwjgl.util.vma.VmaBudget.calloc(org.lwjgl.vulkan.VK10.VK_MAX_MEMORY_HEAPS,stack);org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(ctx.vma(),heaps);
            for(int i=0;i<memory.memoryHeapCount();i++){data.addProperty("heap"+i+"UsageMiB",heaps.get(i).usage()>>20);data.addProperty("heap"+i+"BudgetMiB",heaps.get(i).budget()>>20);data.addProperty("heap"+i+"CausticaMiB",heaps.get(i).statistics().allocationBytes()>>20);}
        }
    }
    /** Camera pinned every frame (x, y, z, yaw, pitch) so stray input cannot move a static capture. */
    private static int burstShots;
    private static volatile double[] pin;
    private static void tick(Minecraft mc) {
        try {
            double[] p=pin;
            if(p!=null&&mc.player!=null){mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);mc.player.setPos(p[0],p[1],p[2]);mc.player.setYRot((float)p[3]);mc.player.setXRot((float)p[4]);mc.player.yRotO=(float)p[3];mc.player.xRotO=(float)p[4];}
            if(stage>1&&stage<5&&mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
            switch(stage) {
                case 0 -> {
                    if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("UpdateModScreen"))mc.gui.setScreen(new TitleScreen());
                    if(!(mc.gui.screen() instanceof TitleScreen))return;
                    mc.options.pauseOnLostFocus=false;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot load replica");});stage=1;
                }
                case 1 -> {if(mc.level!=null&&mc.player!=null&&mc.gui.screen()==null)configure(mc);}
                case 2 -> {
                    if(Boolean.getBoolean("ce.rrScene")&&!rrSceneBuilt&&System.nanoTime()>=deadline-30_000_000_000L) {
                        // Dimension teleport tickets need server ticks before fill can address loaded chunks.
                        commands(mc,"gamemode creative",
                            "execute in minecraft:the_end run fill 40 179 40 56 179 56 minecraft:red_nether_bricks",
                            "execute in minecraft:the_end run fill 40 180 40 40 185 56 minecraft:white_concrete",
                            "execute in minecraft:the_end run fill 41 180 40 56 185 40 minecraft:red_concrete",
                            "execute in minecraft:the_end run fill 56 180 41 56 185 56 minecraft:blue_concrete",
                            "execute in minecraft:the_end run setblock 48 183 41 minecraft:glowstone",
                            "execute in minecraft:the_end run tp @p 48.5 181 55.5 180 35");
                        rrSceneBuilt=true;
                    }
                    if(System.nanoTime()>=deadline)next(mc);
                }
                case 3 -> {
                    // Runtime disables FG while unfocused; do not label a background measurement as
                    // a renderer regression. Keep the client alive so the real window can be activated.
                    if(!Boolean.getBoolean("ce.backgroundRegression") && !isForeground(mc)) {
                        if (Boolean.getBoolean("ce.requestOwnWindowFocus"))
                            org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());
                        deadline=System.nanoTime()+3_000_000_000L;return;
                    }
                    if(System.nanoTime()<deadline || (reload!=null&&!reload.isDone()))return;
                    if(reload!=null){reload.join();reload=null;}
                    measureWidth=mc.getWindow().getWidth();measureHeight=mc.getWindow().getHeight();viewportChanged=false;
                    Step step=steps.get(stepIndex);startNanos=System.nanoTime();startUtc=Instant.now();firstFrame=RtComposite.frameCounter();
                    observed=activeSamples=submittedSamples=foregroundSamples=correctMultiplierSamples=0;sampleNanos=0;states.setLength(0);states.append("elapsedSeconds,active,submitted,runtimeFramesAtQuery,generatedLastPresent,status,osForeground\n");
                    rrEvaluationsStart=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr.INSTANCE.javaEvaluateCalls();
                    srEvaluationsStart=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineSr.INSTANCE.javaEvaluateCalls();
                    var fgAtStart=RtStreamlineFg.INSTANCE;
                    timelineWaitCountStart=fgAtStart.timelineWaitCount();
                    timelineWaitNanosStart=fgAtStart.timelineWaitTotalNanos();
                    var terrainAtStart=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
                    terrainInstancesStart=terrainAtStart==null?0:terrainAtStart.staticInstances().size();
                    terrainRevisionStart=terrainAtStart==null?0:terrainAtStart.staticInstanceRevision();
                    dhSectionsStart=dev.comfyfluffy.caustica.client.DhLodCapture.sectionCount();
                    terrainBytesStart=dev.comfyfluffy.caustica.rt.terrain.TerrainPrimitiveStorage.rawUploadedBytes();
                    loopTimingStart=dev.comfyfluffy.caustica.rt.CausticaLoopProbe.snapshot();
                    apiTimingStart=dev.comfyfluffy.caustica.streamline.StreamlineCallTimings.snapshot();
                    GpuTimingSnapshot.begin();Cadence.begin();stage=4;
                    var phase=new JsonObject();phase.addProperty("step",step.name);phase.addProperty("phase","measure");json("phase",phase);
                }
                case 4 -> {
                    long now=System.nanoTime();var fg=RtStreamlineFg.INSTANCE;
                    viewportChanged|=measureWidth!=mc.getWindow().getWidth()||measureHeight!=mc.getWindow().getHeight();
                    if(!Boolean.getBoolean("ce.backgroundRegression") && !isForeground(mc)) {
                        var interrupted=new JsonObject();interrupted.addProperty("step",steps.get(stepIndex).name);interrupted.addProperty("reason","window lost foreground; repeat warmup and measurement");json("focus-interruption",interrupted);
                        deadline=now+3_000_000_000L;stage=3;return;
                    }
                    if(Boolean.getBoolean("ce.fly") && mc.player!=null) {
                        // Steady spectator flight through new terrain: chunk loading, BLAS builds, DH refinement.
                        double yaw=Math.toRadians(36.6746826171875),speed=Double.parseDouble(System.getProperty("ce.flySpeed","0.6"));
                        mc.player.getAbilities().flying=true;
                        mc.player.setYRot(36.6746826171875f);mc.player.setXRot(15f);
                        mc.player.setDeltaMovement(-Math.sin(yaw)*speed,0,Math.cos(yaw)*speed);
                    }
                    if(Boolean.getBoolean("ce.motion")) {
                        double angle=(now-startNanos)/1e9*Math.PI/6;
                        mc.player.setYRot((float)((Boolean.getBoolean("ce.rrScene")?180:36.6746826171875)+(Boolean.getBoolean("ce.scene2")?110:0)+20*Math.sin(angle)));
                        mc.player.setXRot((float)((Boolean.getBoolean("ce.rrScene")?35:42.36389923095703)+5*Math.sin(angle*.5)));
                    }
                    if(Boolean.getBoolean("ce.burst")&&steps.get(stepIndex).name.equals("walk-1")&&burstShots<10){GpuReadback.frame(OUT,"burst-"+(burstShots++));}
                    if(now>=sampleNanos){observed++;boolean foreground=isForeground(mc);if(foreground)foregroundSamples++;if(fg.isActive())activeSamples++;if(fg.isActive() && fg.framesActuallyPresented()==CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value()+1)correctMultiplierSamples++;if(fg.frameInputsSubmitted())submittedSamples++;
                        states.append((now-startNanos)/1e9).append(',').append(fg.isActive()).append(',').append(fg.frameInputsSubmitted()).append(',').append(fg.framesActuallyPresented()).append(',').append(fg.generatedFramesLastPresent()).append(',').append(fg.runtimeStatus()).append(',').append(foreground).append('\n');sampleNanos=now+1_000_000_000L;}
                    if(now-startNanos<(steps.get(stepIndex).name.equals("soak")?Integer.getInteger("ce.soakSeconds",180):(steps.get(stepIndex).name.matches("(?:scene2-)?(?:off|on)-[ab]|mfg-[234]x")?Integer.getInteger("ce.measureSeconds",30):Integer.getInteger("ce.switchSeconds",10)))*1_000_000_000L)return;
                    Step step=steps.get(stepIndex);var data=new JsonObject();
                    data.addProperty("label",step.name);data.addProperty("fgRequested",step.fg);data.addProperty("stableViewport",!viewportChanged);data.addProperty("startUtc",startUtc.toString());data.addProperty("endUtc",Instant.now().toString());data.addProperty("startQpcNanos",startNanos);data.addProperty("endQpcNanos",now);
                    data.addProperty("seconds",(now-startNanos)/1e9);data.addProperty("realFrames",RtComposite.frameCounter()-firstFrame);data.addProperty("realFps",(RtComposite.frameCounter()-firstFrame)/((now-startNanos)/1e9));data.addProperty("activeSamples",activeSamples);data.addProperty("observations",observed);data.addProperty("foregroundSamples",foregroundSamples);data.addProperty("representativeForegroundWindow",foregroundSamples>=observed*.8);data.addProperty("runtimeFramesLastQuery",fg.framesActuallyPresented());data.addProperty("generatedLastPresent",fg.generatedFramesLastPresent());data.addProperty("submittedGenerated",fg.nativeSubmittedMultiFrameCount());data.addProperty("requestedGenerated",CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value());data.addProperty("correctMultiplierSamples",correctMultiplierSamples);data.addProperty("status",fg.submissionStatus());data.addProperty("rrReady",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr.INSTANCE.isReady());data.addProperty("srReady",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineSr.INSTANCE.isReady());data.addProperty("srOnly",CausticaConfig.Rt.DlssRr.SR_ONLY.value());data.addProperty("rrEnabled",CausticaConfig.Rt.DlssRr.ENABLED.value());data.addProperty("quality",CausticaConfig.Rt.DlssRr.QUALITY.value());data.addProperty("srPresetRequested",CausticaConfig.Rt.DlssRr.SR_PRESET.value());data.addProperty("rrPresetRequested",CausticaConfig.Rt.DlssRr.PRESET.value());data.addProperty("width",mc.getWindow().getWidth());data.addProperty("height",mc.getWindow().getHeight());data.addProperty("rrEvaluateCalls",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr.INSTANCE.javaEvaluateCalls()-rrEvaluationsStart);data.addProperty("srEvaluateCalls",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineSr.INSTANCE.javaEvaluateCalls()-srEvaluationsStart);data.addProperty("attemptedTerrainRawBytes",dev.comfyfluffy.caustica.rt.terrain.TerrainPrimitiveStorage.rawUploadedBytes());data.addProperty("attemptedTerrainStoredBytes",dev.comfyfluffy.caustica.rt.terrain.TerrainPrimitiveStorage.storedUploadedBytes());data.addProperty("rrEvaluateResult",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr.INSTANCE.lastEvaluateResult());data.addProperty("srEvaluateResult",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineSr.INSTANCE.lastEvaluateResult());data.addProperty("rrFallback",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr.INSTANCE.fallbackActive());data.addProperty("srFallback",dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineSr.INSTANCE.fallbackActive());data.add("measuredPresentedFps",com.google.gson.JsonNull.INSTANCE);
                    var terrainAtEnd=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
                    data.addProperty("terrainInstancesStart",terrainInstancesStart);
                    data.addProperty("terrainInstancesEnd",terrainAtEnd==null?0:terrainAtEnd.staticInstances().size());
                    data.addProperty("terrainRevisionDelta",(terrainAtEnd==null?0:terrainAtEnd.staticInstanceRevision())-terrainRevisionStart);
                    data.addProperty("terrainUploadedRawBytesDelta",dev.comfyfluffy.caustica.rt.terrain.TerrainPrimitiveStorage.rawUploadedBytes()-terrainBytesStart);
                    data.addProperty("dhSectionsStart",dhSectionsStart);
                    data.addProperty("dhSectionsEnd",dev.comfyfluffy.caustica.client.DhLodCapture.sectionCount());
                    data.addProperty("dhCaptureStats",dev.comfyfluffy.caustica.client.DhLodCapture.stats());
                    data.addProperty("fgInputTimelineWaitCount",fg.timelineWaitCount()-timelineWaitCountStart);
                    data.addProperty("fgInputTimelineWaitMs",(fg.timelineWaitTotalNanos()-timelineWaitNanosStart)/1e6);
                    data.addProperty("fgInputTimelineWaitMaxMs",fg.timelineWaitMaximumNanos()/1e6);
                    data.addProperty("fgQueueFallback",fg.queueFallbackActive());
                    data.addProperty("fgQueueMode",fg.effectiveQueueMode());
                    long[] loopEnd=dev.comfyfluffy.caustica.rt.CausticaLoopProbe.snapshot();
                    data.addProperty("outerTickMs",(loopEnd[0]-loopTimingStart[0])/1e6);
                    data.addProperty("renderFrameMs",(loopEnd[1]-loopTimingStart[1])/1e6);
                    data.addProperty("betweenTicksMs",(loopEnd[2]-loopTimingStart[2])/1e6);
                    data.addProperty("outerTickCount",loopEnd[3]-loopTimingStart[3]);
                    data.addProperty("renderFrameCount",loopEnd[4]-loopTimingStart[4]);
                    data.addProperty("gameRendererMs",(loopEnd[5]-loopTimingStart[5])/1e6);
                    data.addProperty("commandSubmitMs",(loopEnd[6]-loopTimingStart[6])/1e6);
                    data.addProperty("commandSubmitCount",loopEnd[7]-loopTimingStart[7]);
                    data.addProperty("commandFenceAwaitMs",(loopEnd[8]-loopTimingStart[8])/1e6);
                    data.addProperty("commandFenceAwaitCount",loopEnd[9]-loopTimingStart[9]);
                    var api=new JsonObject();var apiEnd=dev.comfyfluffy.caustica.streamline.StreamlineCallTimings.snapshot();
                    for(int i=0;i<dev.comfyfluffy.caustica.streamline.StreamlineCallTimings.NAMES.length;i++) {
                        var cost=new JsonObject();cost.addProperty("wallMs",(apiEnd[2*i]-apiTimingStart[2*i])/1e6);cost.addProperty("calls",apiEnd[2*i+1]-apiTimingStart[2*i+1]);api.add(dev.comfyfluffy.caustica.streamline.StreamlineCallTimings.NAMES[i],cost);
                    }data.add("nativeApiWallCosts",api);vram(data);data.addProperty("dhLods",dev.comfyfluffy.caustica.rt.entity.RtDhLods.INSTANCE.stats());data.addProperty("windowW",mc.getWindow().getWidth());data.addProperty("windowH",mc.getWindow().getHeight());json(step.name,data);
                    Cadence.write(OUT.resolve(step.name+"-intervals.csv"));GpuTimingSnapshot.write(OUT.resolve(step.name+"-gpu.txt"));Files.writeString(OUT.resolve(step.name+"-states.csv"),states);
                    if(Boolean.getBoolean("ce.captureSteps"))GpuReadback.frame(OUT,step.name);
                    String invalid="";
                    if(step.fg&&activeSamples<observed*.8) {
                        if(foregroundSamples>=observed*.8)invalid="No generated frames observed while foreground";
                        else {unverifiedFgSteps++;data.addProperty("fgVerification","unverified-runtime-unfocused");json(step.name,data);}
                    }
                    if(!step.fg&&activeSamples>1)invalid="Generation remained active after FG Off";
                    if(CausticaConfig.Rt.DlssRr.ENABLED.value() && (RtComposite.frameCounter()>firstFrame || foregroundSamples>=observed*.8)) {
                        if(CausticaConfig.Rt.DlssRr.SR_ONLY.value()) {
                            var sr=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineSr.INSTANCE;
                            if(sr.javaEvaluateCalls()<=srEvaluationsStart || !sr.evaluationCompleted(sr.lastEvaluateResult()) || sr.fallbackActive())invalid="SR did not evaluate successfully";
                        } else {
                            var rr=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineRr.INSTANCE;
                            if(rr.javaEvaluateCalls()<=rrEvaluationsStart || !rr.evaluationCompleted(rr.lastEvaluateResult()) || rr.fallbackActive())invalid="RR did not evaluate successfully";
                        }
                    }
                    if(step.fg && foregroundSamples>=observed*.8 && correctMultiplierSamples<observed*.8)invalid="Requested multiplier not demonstrated by runtime";
                    if(Boolean.getBoolean("ce.readGuides") && (step.name.equals("off-b") || step.name.startsWith("guide-") || step.name.startsWith("mirror-"))) GpuReadback.guides(OUT,step.name);
                    if(!invalid.isEmpty()){failure=invalid+" in "+step.name;Files.writeString(OUT.resolve("regression-failure.txt"),failure);finishWorld(mc);}else next(mc);
                }
                case 5 -> {if(saved.isDone()){saved.get();mc.disconnectWithSavingScreen();stage=6;}}
                case 6 -> {if(mc.level==null){
                    if((Boolean.getBoolean("ce.extendedFg") || Boolean.getBoolean("ce.worldReenterAfterMatrix"))
                            && !reentered && failure.isEmpty()){
                        reentered=true;stepIndex=steps.size()-1;steps.add(new Step("world-reentered",true,m->{}));
                        mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot reenter replica");});stage=9;return;
                    }
                    var data=new JsonObject();data.addProperty("worldEntered",true);data.addProperty("normalWorldExit",true);data.addProperty("gametestThreading",false);data.addProperty("transitionChecksPassed",failure.isEmpty());data.addProperty("unverifiedFgSteps",unverifiedFgSteps);data.addProperty("fgRegressionPassed",failure.isEmpty()&&unverifiedFgSteps==0);json("run-completed",data);stage=7;}}
                case 7 -> {
                    if(Boolean.getBoolean("ce.prepareMfg")) {
                        // Same OptionInstance callback as the user's video-settings selection.
                        var options=dev.comfyfluffy.caustica.client.RtVideoOptions.categoryOptions("fg");
                        @SuppressWarnings("unchecked") var option=(net.minecraft.client.OptionInstance<Integer>)options[options.length-1];
                        option.set(3);
                        Files.writeString(OUT.resolve("menu-mfg-request.txt"),dev.comfyfluffy.caustica.streamline.ManagedMfg.status());
                    }
                    mc.stop();stage=8;
                }
                case 9 -> {if(mc.level!=null&&mc.player!=null&&mc.gui.screen()==null){if(!Boolean.getBoolean("ce.preserveScene"))commands(mc,"time set 6000","weather clear","tp @p "+SCENE);deadline=System.nanoTime()+20_000_000_000L;stage=2;}}
                default -> {}
            }
        } catch(Throwable error) {try{Files.writeString(OUT.resolve("regression-exception.txt"),error.toString());}catch(Exception ignored){}throw new RuntimeException(error);}
    }
}
