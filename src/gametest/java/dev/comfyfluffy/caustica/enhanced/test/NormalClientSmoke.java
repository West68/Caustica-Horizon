package dev.comfyfluffy.caustica.enhanced.test;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.*;
import dev.comfyfluffy.caustica.rt.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;

/** Isolated test-mod only. Normal client event loop, no GameTest server/client phaser. */
final class NormalClientSmoke {
 private static int stage,wait;private static long first,start;private static java.util.concurrent.Future<?> saved;
 private static final AtomicInteger pending=new AtomicInteger();
 private static final Path out=Path.of(System.getProperty("ce.evidence","."));
 static void start(){ClientTickEvents.END_CLIENT_TICK.register(NormalClientSmoke::tick);}
 private static void commands(Minecraft mc,String... commands){var server=mc.getSingleplayerServer();server.execute(()->{for(String cmd:commands)server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),cmd);});}
 private static void shot(Minecraft mc,String name){pending.incrementAndGet();Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),image->{try(image){image.writeToFile(out.resolve(name+".png"));}catch(Exception e){throw new RuntimeException(e);}finally{pending.decrementAndGet();}});}
 private static void next(int ticks)throws Exception{stage++;wait=ticks;Files.writeString(out.resolve("normal-stage.json"),"{\"stage\":"+stage+"}");}
 private static void tick(Minecraft mc){try{
  if(mc.level!=null && (stage<=7 || stage>=11) && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
  if(wait-->0)return;
  switch(stage){
   case 0 -> {mc.options.pauseOnLostFocus=false;if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("UpdateModScreen"))mc.gui.setScreen(new TitleScreen());if(!(mc.gui.screen() instanceof TitleScreen))return;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot load replica world");});next(20);}
   case 1 -> {if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;mc.options.pauseOnLostFocus=false;mc.getWindow().setWindowed(System.getProperty("ce.mode","").equals("1440p-ultra")?2560:1920,System.getProperty("ce.mode","").equals("1440p-ultra")?1440:1080);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);mc.options.fov().set(70);CausticaConfig.Rt.Fg.ENABLED.set(false);RtVideoOptions.applyVisualPreset(true);CausticaConfig.Rt.DlssRr.QUALITY.set(System.getProperty("ce.mode","").equals("1440p-ultra")?3:0);org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());commands(mc,"forceload add -16 -16 16 16","fill -12 100 -12 12 108 12 air","fill -12 99 -12 12 99 12 stone_bricks","fill -7 100 -6 7 108 -6 black_concrete","fill -1 103 -5 1 105 -5 sea_lantern","time set 6000","weather clear","gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false","gamemode spectator @p","tp @p 0 101 8 180 0");next(200);
    if(Boolean.getBoolean("ce.waterOnly")) {
      commands(mc,"fill -12 99 14 12 99 38 stone_bricks","fill -12 100 14 12 100 38 water","tp @p 0 104 10 0 15");stage=7;wait=180;
    }
   }
   case 2 -> {shot(mc,"normal-noon");GpuReadback.frame(out,"normal-noon");CausticaConfig.Rt.Look.BRIGHTNESS.set(1.25f);next(30);}
   case 3 -> {shot(mc,"normal-brightness-changed");GpuReadback.frame(out,"normal-brightness-changed");CausticaConfig.Rt.Look.BRIGHTNESS.set(1f);commands(mc,"time set 12000");next(160);}
   case 4 -> {shot(mc,"normal-sunset");GpuReadback.frame(out,"normal-sunset");commands(mc,"time set 18000");next(160);}
   case 5 -> {shot(mc,"normal-night");GpuReadback.frame(out,"normal-night");commands(mc,"time set 6000","weather rain");next(850);}
   case 6 -> {shot(mc,"normal-rain");commands(mc,"weather clear","fill -12 99 14 12 99 38 stone_bricks","fill -12 100 14 12 100 38 water","tp @p 0 104 10 0 15");next(180);}
   case 7 -> {if(mc.gui.screen()!=null){mc.gui.setScreen(null);wait=20;return;}Files.writeString(out.resolve("water-state.json"),"{\"screen\":null,\"width\":"+mc.getWindow().getWidth()+",\"height\":"+mc.getWindow().getHeight()+"}");shot(mc,"normal-water");mc.gui.setScreen(new OptionsScreen(new TitleScreen(),mc.options,false));next(10);}
   case 8 -> {shot(mc,"normal-options-entry");EnhancedSettingsCheck.click(mc.gui.screen(),"caustica.enhanced.entry");next(10);}
   case 9 -> {shot(mc,"normal-settings-home");mc.gui.setScreen(new EnhancedOptionsScreen(new TitleScreen(),"clouds"));next(10);}
   case 10 -> {shot(mc,"normal-settings-clouds");mc.gui.setScreen(null);mc.getWindow().setWindowed(1280,720);next(100);}
   case 11 -> {shot(mc,"normal-small");boolean ultra=System.getProperty("ce.mode","").equals("1440p-ultra");mc.getWindow().setWindowed(ultra?2560:1920,ultra?1440:1080);next(120);}
   case 12 -> {if(mc.getWindow().getWidth()!=(System.getProperty("ce.mode","").equals("1440p-ultra")?2560:1920))throw new AssertionError("Wrong output extent");shot(mc,"normal-resized-back");GpuTimingSnapshot.begin();Cadence.begin();first=RtComposite.frameCounter();start=System.nanoTime();next(200);}
   case 13 -> {double seconds=(System.nanoTime()-start)/1e9;long frames=RtComposite.frameCounter()-first;Cadence.write(out.resolve("normal-intervals.csv"));GpuTimingSnapshot.write(out.resolve("normal-gpu.txt"));Files.writeString(out.resolve("normal-performance.json"),"{\"fps\":"+(frames/seconds)+",\"seconds\":"+seconds+",\"fg\":false,\"width\":"+mc.getWindow().getWidth()+",\"height\":"+mc.getWindow().getHeight()+",\"fullGpuFrameMeasured\":false}");CausticaConfig.save();var server=mc.getSingleplayerServer();saved=server.submit(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"save-all flush"));next(5);}
   case 14 -> {if(!saved.isDone()||pending.get()!=0)return;saved.get();mc.disconnectWithSavingScreen();next(20);}
   case 15 -> {if(mc.level!=null)return;Files.writeString(out.resolve("run-completed.json"),"{\"worldEntered\":true,\"normalWorldExit\":true,\"gameTestThreading\":false,\"resizeTested\":true}");next(5);}
   case 16 -> {mc.stop();stage++;}
   default -> {}
  }
 }catch(Throwable e){try{Files.writeString(out.resolve("normal-failure.txt"),e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}}
}
