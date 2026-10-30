package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.*;
public final class FidelityIntegrationTest implements FabricClientGameTest {
 public void runTest(ClientGameTestContext c){try{
  if(Boolean.getBoolean("ce.threadWatchdog")) {
   Thread watchdog=new Thread(()->{while(true){try{Thread.sleep(60000);var text=new StringBuilder();Thread.getAllStackTraces().forEach((t,stack)->{text.append(t.getName()).append(" ").append(t.getState()).append("\n");for(var e:stack)text.append("  ").append(e).append("\n");});Files.writeString(Path.of(System.getProperty("ce.evidence"),"threads-watchdog.txt"),text);}catch(Exception ignored){return;}}},"CE-test-watchdog");
   watchdog.setDaemon(true);watchdog.start();
  }
  Path out=Path.of(System.getProperty("ce.evidence"));Files.createDirectories(out);
  var origin=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("caustica").orElseThrow().getOrigin().getPaths().getFirst();var sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(origin)));
  if(!sha.equalsIgnoreCase(System.getProperty("ce.expectedSha")))throw new AssertionError("Wrong installed renderer");Files.writeString(out.resolve("renderer-origin.txt"),origin+"\n"+sha);
  c.waitFor(mc->{var screen=mc.gui.screen();if(screen!=null&&screen.getClass().getName().equals("com.seibel.distanthorizons.common.wrappers.gui.updater.UpdateModScreen")){mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());}return mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen;},1200);
  c.runOnClient(mc->mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot open test world");}));c.waitFor(mc->mc.level!=null&&mc.player!=null&&mc.gui.screen()==null,1800);
  var server=c.computeOnClient(mc->mc.getSingleplayerServer());var control=new net.fabricmc.fabric.impl.client.gametest.context.TestServerContextImpl(server);
  c.runOnClient(mc->{mc.options.fov().set(70);mc.options.framerateLimit().set(260);mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);});
  for(String cmd:new String[]{"time set 6000","weather clear","gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false","tp @p -40.57037839689626 80 374.25722861887783 36.6746826171875 42.36389923095703"})control.runCommand(cmd);c.waitTicks(300);
  boolean ultra=System.getProperty("ce.mode","").equals("1440p-ultra");BaselineTest.size(c,ultra?2560:1920,ultra?1440:1080);
  c.runOnClient(mc->{mc.options.enableVsync().set(false);CausticaConfig.Rt.DlssRr.QUALITY.set(ultra?3:0);});c.waitTicks(200);
  Files.writeString(out.resolve("conditions.json"),c.computeOnClient(mc->"{\"width\":"+mc.getWindow().getWidth()+",\"height\":"+mc.getWindow().getHeight()+",\"bounces\":"+CausticaConfig.Rt.Composite.MAX_BOUNCES.value()+",\"spp\":"+CausticaConfig.Rt.Composite.SPP.value()+",\"rrQuality\":"+CausticaConfig.Rt.DlssRr.QUALITY.value()+",\"fg\":false,\"fullGpuFrameMeasured\":false}"));
  if(Boolean.getBoolean("ce.testUi")) EnhancedSettingsCheck.run(c,out);
  if(!Boolean.getBoolean("ce.skipReadback")) c.runOnClient(mc->{try{GpuReadback.palette(out);}catch(Exception e){throw new RuntimeException(e);}});
  if(!Boolean.getBoolean("ce.skipPerformance")) for(int state:(Boolean.getBoolean("ce.quickPerformance")?new int[]{0,3}:new int[]{0,1,2,3,3,2,1,0})){
   final int variant=state;c.runOnClient(mc->{CausticaConfig.Rt.Look.ITRP.set(variant!=0);CausticaConfig.Rt.Clouds.MODE.set(Math.max(0,variant-1));});c.waitTicks(80);
   String name="forest-v"+state+"-"+System.nanoTime();c.runOnClient(mc->{GpuTimingSnapshot.begin();Cadence.begin();});long first=RtComposite.frameCounter(),start=System.nanoTime();c.waitTicks(Integer.getInteger("ce.windowTicks",200));long frames=RtComposite.frameCounter()-first;double seconds=(System.nanoTime()-start)/1e9;
   c.runOnClient(mc->{Cadence.write(out.resolve(name+"-intervals.csv"));GpuTimingSnapshot.write(out.resolve(name+"-gpu.txt"));});
   Files.writeString(out.resolve(name+".json"),"{\"variant\":"+state+",\"fps\":"+(frames/seconds)+",\"seconds\":"+seconds+",\"bounces\":4,\"fg\":false}");
  }
  if(!Boolean.getBoolean("ce.performanceOnly")) {
  for(String cmd:new String[]{"forceload add -16 -16 16 16","fill -12 100 -12 12 108 12 air","fill -12 99 -12 12 99 12 stone_bricks","fill -7 100 -6 7 108 -6 black_concrete","fill -1 103 -5 1 105 -5 sea_lantern","gamemode spectator @p","tp @p 0 101 8 180 0"})control.runCommand(cmd);c.waitTicks(100);
  String[][] scenes={{"noon","6000","clear"},{"sunset","12000","clear"},{"night","18000","clear"},{"rain","6000","rain"}};
  for(String[] scene:scenes){control.runCommand("time set "+scene[1]);control.runCommand("weather "+scene[2]);c.waitTicks(scene[2].equals("rain")?850:160);
   for(boolean look:new boolean[]{false,true}){c.runOnClient(mc->{CausticaConfig.Rt.Look.ITRP.set(look);CausticaConfig.Rt.Clouds.MODE.set(look?2:0);});c.waitTicks(160);String name=scene[0]+"-look"+look;Files.copy(c.takeScreenshot(name),out.resolve(name+".png"),StandardCopyOption.REPLACE_EXISTING);c.runOnClient(mc->{try{GpuReadback.frame(out,name);}catch(Exception e){throw new RuntimeException(e);}});}
  }
  control.runCommand("weather clear");control.runCommand("time set 6000");control.runCommand("tp @p 0 101 8 180 -35");c.runOnClient(mc->{CausticaConfig.Rt.Look.ITRP.set(true);CausticaConfig.Rt.Clouds.MODE.set(2);});c.waitTicks(160);Files.copy(c.takeScreenshot("clouds"),out.resolve("clouds.png"),StandardCopyOption.REPLACE_EXISTING);c.waitTicks(160);Files.copy(c.takeScreenshot("clouds-moving"),out.resolve("clouds-moving.png"),StandardCopyOption.REPLACE_EXISTING);
  for(String cmd:new String[]{"fill -12 99 14 12 99 38 stone_bricks","fill -12 100 14 12 100 38 water","tp @p 0 104 10 0 15"})control.runCommand(cmd);c.waitTicks(200);
  Files.copy(c.takeScreenshot("water"),out.resolve("water.png"),StandardCopyOption.REPLACE_EXISTING);

  c.runOnClient(mc->CausticaConfig.save());Files.writeString(out.resolve("completed.json"),"{\"worldEntered\":true,\"visualAccepted\":false,\"fg\":false}");
  }
  if(Boolean.getBoolean("ce.testCloudCost")) {
   for(String cmd:new String[]{"weather clear","time set 6000","gamemode spectator @p","tp @p 0 101 8 180 -35"})control.runCommand(cmd);
   c.runOnClient(mc->CausticaConfig.Rt.Look.ITRP.set(true));c.waitTicks(160);
   for(int mode:new int[]{0,1,2,2,1,0}) {
    c.runOnClient(mc->CausticaConfig.Rt.Clouds.MODE.set(mode));c.waitTicks(80);
    String name="sky-cloud-"+mode+"-"+System.nanoTime();c.runOnClient(mc->{GpuTimingSnapshot.begin();Cadence.begin();});long first=RtComposite.frameCounter(),start=System.nanoTime();c.waitTicks(200);long frames=RtComposite.frameCounter()-first;double sec=(System.nanoTime()-start)/1e9;
    c.runOnClient(mc->{Cadence.write(out.resolve(name+"-intervals.csv"));GpuTimingSnapshot.write(out.resolve(name+"-gpu.txt"));});Files.writeString(out.resolve(name+".json"),"{\"mode\":"+mode+",\"fps\":"+(frames/sec)+",\"seconds\":"+sec+"}");Files.copy(c.takeScreenshot(name),out.resolve(name+".png"),StandardCopyOption.REPLACE_EXISTING);
   }
  }
  if(Boolean.getBoolean("ce.testFg")) {
   c.runOnClient(mc->mc.gui.setScreen(new dev.comfyfluffy.caustica.client.EnhancedOptionsScreen(new net.minecraft.client.gui.screens.TitleScreen(),"fg")));c.waitTicks(20);
   Files.copy(c.takeScreenshot("settings"),out.resolve("settings.png"),StandardCopyOption.REPLACE_EXISTING);
   var fg=dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE;
   Files.writeString(out.resolve("fg-capabilities.json"),"{\"probed\":"+fg.capabilitiesProbed()+",\"available\":"+fg.isAvailable()+",\"maxGeneratedFrames\":"+fg.multiFrameCountMax()+",\"streamlineInitialized\":"+dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()+",\"rtxmfgModuleLoaded\":"+dev.comfyfluffy.caustica.streamline.StreamlineRuntime.experimentalMfgLoaded()+"}");
   c.runOnClient(mc->mc.gui.setScreen(null));
    if(dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()||fg.isAvailable()) {
     // Same scene for every multiplier: the forest viewpoint used by the FG-off cadence loop, so the
     // off/2x/3x/4x numbers are directly comparable rather than cross-scene guesses.
     for(String cmd:new String[]{"time set 6000","weather clear","tp @p -40.57037839689626 80 374.25722861887783 36.6746826171875 42.36389923095703"})control.runCommand(cmd);
     c.waitTicks(240);
     measureFg(c,out,"fg-off",0);
     // 2x must be enabled first: slDLSSGGetState cannot report a ceiling until the proxy swapchain exists.
     c.runOnClient(mc->{CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(1);CausticaConfig.Rt.Fg.ENABLED.set(true);});
     c.waitTicks(400);
     int measuredMax=Math.min(5,Math.max(1,fg.multiFrameCountMax()));
     for(int generated=1;generated<=measuredMax;generated++) {
      final int count=generated;
      c.runOnClient(mc->{CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(count);CausticaConfig.Rt.Fg.ENABLED.set(true);});
      // Enabling or changing the multiplier reconfigures the swapchain; 300 ticks covers the rebuild and
      // the runtime's own state sampling before the measurement window starts.
      c.waitTicks(300);
      measureFg(c,out,"fg-"+(count+1)+"x",count);
     }
     c.runOnClient(mc->{CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(1);CausticaConfig.Rt.Fg.ENABLED.set(true);});c.waitTicks(200);
    Files.writeString(out.resolve("fg-evaluation.json"),"{\"requestedMultiplier\":2,\"featureReady\":"+fg.isReady()+",\"successfulSdkEvaluations\":"+fg.successfulEvaluations()+",\"failed\":"+fg.hasFailed()+",\"displayAccepted\":false}");
    var presenter=RtFramePresenter.INSTANCE;
    Files.writeString(out.resolve("fg-present-requests.json"),"{\"acceptedExtraPresentCalls\":"+presenter.acceptedExtraPresentCalls()+",\"lastQueuePresentResult\":"+presenter.lastPresentResult()+",\"presenterFailed\":"+presenter.hasFailed()+",\"actualDisplayFramesMeasured\":false}");
    Files.copy(c.takeScreenshot("fg-on"),out.resolve("fg-on.png"),StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(out.resolve("fg-error.txt"),fg.failureReason());
    c.runOnClient(mc->CausticaConfig.Rt.Fg.ENABLED.set(false));c.waitTicks(80);
   }
  }
  if(Boolean.getBoolean("ce.testParallax")) {
   for(String cmd:new String[]{"weather clear","time set 6000","fill -6 99 0 6 105 0 bricks","tp @p 0 101 8 165 5"})control.runCommand(cmd);
   c.runOnClient(mc->{CausticaConfig.Rt.Look.ITRP.set(true);CausticaConfig.Rt.Clouds.MODE.set(1);});c.waitTicks(100);
   for(int mode:new int[]{0,1,2,2,1,0}) {
    c.runOnClient(mc->CausticaConfig.Rt.Material.PARALLAX.set(mode));c.waitTicks(80);
    String name="parallax-"+mode+"-"+System.nanoTime();c.runOnClient(mc->{GpuTimingSnapshot.begin();Cadence.begin();});long first=RtComposite.frameCounter(),start=System.nanoTime();c.waitTicks(200);long frames=RtComposite.frameCounter()-first;double sec=(System.nanoTime()-start)/1e9;
    c.runOnClient(mc->{Cadence.write(out.resolve(name+"-intervals.csv"));GpuTimingSnapshot.write(out.resolve(name+"-gpu.txt"));});Files.writeString(out.resolve(name+".json"),"{\"mode\":"+mode+",\"fps\":"+(frames/sec)+",\"seconds\":"+sec+"}");Files.copy(c.takeScreenshot(name),out.resolve(name+".png"),StandardCopyOption.REPLACE_EXISTING);
   }
  }
  if(Boolean.getBoolean("ce.testPunchy")) {
   control.runCommand("gamemode creative @p");control.runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_pickaxe");
   c.runOnClient(mc->mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));c.waitTicks(100);
   long captured=dev.comfyfluffy.caustica.rt.entity.RtFirstPerson.capturedFrames;
   Files.copy(c.takeScreenshot("punchy-idle"),out.resolve("punchy-idle.png"),StandardCopyOption.REPLACE_EXISTING);
   c.runOnClient(mc->mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND));c.waitTicks(3);
   Files.copy(c.takeScreenshot("punchy-swing"),out.resolve("punchy-swing.png"),StandardCopyOption.REPLACE_EXISTING);c.waitTicks(20);
   Files.writeString(out.resolve("punchy-rt.json"),c.computeOnClient(mc->"{\"enabled\":"+dev.comfyfluffy.caustica.rt.entity.RtFirstPerson.enabled()+",\"capturedFramesDelta\":"+(dev.comfyfluffy.caustica.rt.entity.RtFirstPerson.capturedFrames-captured)+",\"rtTriangles\":"+dev.comfyfluffy.caustica.rt.entity.RtFirstPerson.triangles+",\"reflectionAccepted\":false}"));
  }
  c.runOnClient(mc->{CausticaConfig.Rt.Fg.ENABLED.set(false);CausticaConfig.save();});
  control.runCommand("save-all flush");Files.writeString(out.resolve("test-phase.txt"),"before-world-close");new net.fabricmc.fabric.impl.client.gametest.context.TestSingleplayerContextImpl(c,null,server).close();Files.writeString(out.resolve("test-phase.txt"),"after-world-close");
  c.waitFor(mc->mc.level==null,1200);c.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);Files.writeString(out.resolve("run-completed.json"),"{\"worldEntered\":true,\"normalWorldExit\":true}");
 }catch(Throwable e){try{Path failure=Path.of(System.getProperty("ce.evidence"));Files.writeString(failure.resolve("failure-screen.txt"),c.computeOnClient(mc->String.valueOf(mc.gui.screen())));Files.copy(c.takeScreenshot("integration-failure"),failure.resolve("failure.png"),StandardCopyOption.REPLACE_EXISTING);}catch(Throwable ignored){}throw new RuntimeException(e);}}

 /**
  * One fixed-length measurement window. Real FPS is counted from the composite frame serial; the displayed
  * rate comes from the runtime's own reported presented-frame count, never from realFps multiplied by a
  * per-present guess. Both are written with the raw counters so a window can be re-checked later.
  */
 private static String measureFg(ClientGameTestContext c,Path out,String label,int generated) {
  if(Boolean.getBoolean("ce.focusWindow")) {c.runOnClient(mc->{long h=mc.getWindow().handle();org.lwjgl.glfw.GLFW.glfwShowWindow(h);org.lwjgl.glfw.GLFW.glfwFocusWindow(h);});c.waitTicks(40);}
  c.runOnClient(mc->{GpuTimingSnapshot.begin();Cadence.begin();});
  long firstFrame=RtComposite.frameCounter();
  long firstPresented=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.presentedFramesAccumulated();
  java.time.Instant startUtc=java.time.Instant.now();
  long start=System.nanoTime();
  c.waitTicks(Integer.getInteger("ce.fgWindowTicks",200));
  long frames=RtComposite.frameCounter()-firstFrame;
  long presented=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.presentedFramesAccumulated()-firstPresented;
  double seconds=(System.nanoTime()-start)/1e9;
  double realFps=frames/seconds;
  // SDK observations are not an independent presentation counter.
  String name=label+"-"+System.nanoTime();
  c.runOnClient(mc->{Cadence.write(out.resolve(name+"-intervals.csv"));GpuTimingSnapshot.write(out.resolve(name+"-gpu.txt"));});
  String json="{\"label\":\""+label+"\",\"generated\":"+generated+",\"realFps\":"+realFps
   +",\"displayedFps\":null,\"frames\":"+frames+",\"sdkStateFrameSum\":"+presented
   +",\"seconds\":"+seconds+",\"startUtc\":\""+startUtc+"\",\"endUtc\":\""+java.time.Instant.now()+"\",\"maxSupported\":"+dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.multiFrameCountMax()+",\"submitted\":"+dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.nativeSubmittedMultiFrameCount()+"}";
  try{Files.writeString(out.resolve(name+".json"),json);}catch(Exception e){throw new RuntimeException(e);}
  return json;
 }
}
