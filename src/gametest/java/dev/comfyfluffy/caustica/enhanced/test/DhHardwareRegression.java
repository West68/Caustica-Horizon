package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.*;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.entity.RtDhLods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
/** Test-mod only: actual Minecraft transitions, GPU readback and frame cadence, no desktop control. */
final class DhHardwareRegression {
 private static int stage,wait,resize;private static long start,first;private static java.util.concurrent.Future<?> saved;private static java.util.concurrent.Future<int[]> waterSearch;
 private static final Path out=Path.of(System.getProperty("ce.evidence","."));
 static void start(){ClientTickEvents.END_CLIENT_TICK.register(DhHardwareRegression::tick);}
 private static void command(Minecraft mc,String... cmds){var s=mc.getSingleplayerServer();s.execute(()->{for(String c:cmds)s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),c);});}
 private static void stats(String name)throws Exception {Files.writeString(out.resolve(name+".txt"),DhLodCapture.stats()+"\n"+RtDhLods.INSTANCE.stats()+"\n"+DistantHorizonsCompat.diagnostics());}
 private static void begin(){GpuTimingSnapshot.begin();Cadence.begin();first=RtComposite.frameCounter();start=System.nanoTime();}
 private static void end(String name)throws Exception {double seconds=(System.nanoTime()-start)/1e9;Cadence.write(out.resolve(name+"-intervals.csv"));GpuTimingSnapshot.write(out.resolve(name+"-gpu.txt"));Files.writeString(out.resolve(name+".json"),"{\"fps\":"+((RtComposite.frameCounter()-first)/seconds)+",\"seconds\":"+seconds+"}");stats(name);}
 private static void tick(Minecraft mc){try {
  if(mc.level!=null&&mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
  if(mc.level!=null&&stage>=2&&RtContext.currentOrNull()==null)throw new AssertionError("Vulkan RT context unavailable; do not accept OpenGL fallback as a test");
  if(wait-->0)return;
  switch(stage) {
   case 0 -> {mc.options.pauseOnLostFocus=false;if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("UpdateModScreen"))mc.gui.setScreen(new TitleScreen());if(!(mc.gui.screen() instanceof TitleScreen))return;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot open isolated world");});stage++;wait=20;}
   case 1 -> {if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);mc.options.renderDistance().set(6);CausticaConfig.Rt.Fg.ENABLED.set(false);CausticaConfig.Rt.Visuals.DH_LODS.set(true);mc.getWindow().setWindowed(Boolean.getBoolean("ce.dhPerfOnly")?1920:1280,Boolean.getBoolean("ce.dhPerfOnly")?1080:720);command(mc,"gamemode spectator @p","time set 6000","weather clear",Boolean.getBoolean("ce.dhPerfOnly")?"tp @p 9 66 254 36 8":"tp @p -40 140 374 36 10");stage++;wait=Integer.getInteger("ce.dhWarmTicks",1200);}
   case 2 -> {if(Boolean.getBoolean("ce.dhPerfOnly")){begin();stage=25;wait=1200;break;}stats("capture");if(DhLodCapture.sectionCount()==0)throw new AssertionError("No DH sections; "+DistantHorizonsCompat.diagnostics());System.setProperty("caustica.test.dhHitMarker","true");stage++;wait=40;}
   case 3 -> {Files.writeString(out.resolve("hardware-hits.txt"),RtDhLods.INSTANCE.readTestCounters());GpuReadback.frame(out,"dh-hit-marker");command(mc,"time set 11000");stage=18;wait=100;}
   case 18 -> {Files.writeString(out.resolve("low-sun-hardware-hits.txt"),RtDhLods.INSTANCE.readTestCounters());System.clearProperty("caustica.test.dhHitMarker");command(mc,"time set 6000");if(Boolean.getBoolean("ce.dhShadowOnly")){mc.disconnectWithSavingScreen();stage=19;wait=40;}else{stage=4;wait=100;}}
   case 19 -> {if(mc.level!=null)return;stats("shadow-world-exit");Files.writeString(out.resolve("run-completed.json"),"{\"dhShadowOnly\":true,\"visualAccepted\":false}");mc.stop();stage=99;}
   case 25 -> {end("water-1080-clean");mc.disconnectWithSavingScreen();stage=26;wait=40;}
   case 26 -> {if(mc.level!=null)return;stats("performance-world-exit");Files.writeString(out.resolve("run-completed.json"),"{\"dhPerformanceOnly\":true,\"visualAccepted\":false}");mc.stop();stage=99;}
   case 4 -> {GpuReadback.frame(out,"dh-normal");begin();stage++;wait=600;}
   case 5 -> {end("dh-720");var server=mc.getSingleplayerServer();waterSearch=server.submit(()->{
    var level=server.overworld();int px=(int)mc.player.getX(),pz=(int)mc.player.getZ();int[] best=null;int scoreBest=-1;
    for(int dz=-128;dz<=128;dz+=8)for(int dx=-128;dx<=128;dx+=8){int x=px+dx,z=pz+dz;if(!level.hasChunk(x>>4,z>>4))continue;
     int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)-1;
     if(!level.getFluidState(new net.minecraft.core.BlockPos(x,y,z)).is(net.minecraft.tags.FluidTags.WATER))continue;
     int score=0;for(int[] d:new int[][]{{8,0},{-8,0},{0,8},{0,-8}})if(level.hasChunk((x+d[0])>>4,(z+d[1])>>4)&&level.getFluidState(new net.minecraft.core.BlockPos(x+d[0],y,z+d[1])).is(net.minecraft.tags.FluidTags.WATER))score++;
     if(score>scoreBest){scoreBest=score;best=new int[]{x,y,z};}
    }return best;});stage=20;wait=10;}
   case 20 -> {if(!waterSearch.isDone())return;int[] p=waterSearch.get();if(p==null){Files.writeString(out.resolve("water-not-found.txt"),"No loaded natural water; visual seam remains unverified");stage=6;break;}Files.writeString(out.resolve("water-position.txt"),java.util.Arrays.toString(p));command(mc,"tp @p "+p[0]+" "+(p[1]+4)+" "+p[2]+" 36 8");stage=21;wait=400;}
   case 21 -> {System.setProperty("caustica.test.dhHitMarker","true");stage=22;wait=40;}
   case 22 -> {Files.writeString(out.resolve("water-hardware-hits.txt"),RtDhLods.INSTANCE.readTestCounters());System.clearProperty("caustica.test.dhHitMarker");stage=23;wait=100;}
   case 23 -> {GpuReadback.frame(out,"water-normal");begin();stage=24;wait=600;}
   case 24 -> {end("water-720");stage=6;wait=20;}
   case 6 -> {if(resize>=12){stage++;wait=20;break;}int[][] sizes={{1920,1080},{2560,1440},{960,540},{1280,720}};int[] s=sizes[resize%4];mc.getWindow().setWindowed(s[0],s[1]);Files.writeString(out.resolve("resize-"+resize+".txt"),s[0]+"x"+s[1]);resize++;wait=50;}
   case 7 -> {org.lwjgl.glfw.GLFW.glfwMaximizeWindow(mc.getWindow().handle());stage++;wait=100;}
   case 8 -> {org.lwjgl.glfw.GLFW.glfwRestoreWindow(mc.getWindow().handle());mc.getWindow().setWindowed(1920,1080);stage++;wait=150;}
   case 9 -> {begin();stage++;wait=1200;}
   case 10 -> {end("dh-1080-soak");GpuReadback.frame(out,"dh-final");var s=mc.getSingleplayerServer();saved=s.submit(()->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),"save-all flush"));stage++;wait=10;}
   case 11 -> {if(!saved.isDone())return;saved.get();mc.disconnectWithSavingScreen();stage++;wait=20;}
   case 12 -> {if(mc.level!=null)return;stats("world-exit");mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot reopen isolated world");});stage++;wait=20;}
   case 13 -> {if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;stage++;wait=600;}
   case 14 -> {stats("world-reentered");if(DhLodCapture.sectionCount()==0||RtDhLods.INSTANCE.stats().startsWith("instances=0 "))throw new AssertionError("DH failed to resume after world reentry");mc.disconnectWithSavingScreen();stage++;wait=40;}
   case 15 -> {if(mc.level!=null)return;stats("final-world-exit");Files.writeString(out.resolve("run-completed.json"),"{\"dhHardwareTest\":true,\"resizes\":12,\"reenteredWorld\":true,\"visualAccepted\":false}");mc.stop();stage++;}
  }
 }catch(Throwable e){try{stats("failure-state");Files.writeString(out.resolve("dh-failure.txt"),e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}}
}
