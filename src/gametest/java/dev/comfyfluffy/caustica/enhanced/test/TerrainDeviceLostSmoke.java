package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
import java.util.concurrent.Future;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import dev.comfyfluffy.caustica.rt.*;
/** Test-only streaming workload, using the ordinary client loop and an existing disposable save. */
final class TerrainDeviceLostSmoke {
 private static int phase,ticks,point;private static long begin,first;private static Future<?> saved;private static java.util.concurrent.CompletableFuture<?> reload;
 private static final int[][] route={{-352,145,400},{-224,112,400},{-40,85,374},{-480,120,256},{160,110,512},{-352,130,400},{-224,100,400},{-40,90,374},{256,100,640},{-512,128,384},{-352,145,400},{-40,85,374}};
 private static final Path out=Path.of(System.getProperty("ce.evidence"));
 static void start(){ClientTickEvents.END_CLIENT_TICK.register(TerrainDeviceLostSmoke::tick);}
 private static void command(Minecraft mc,String cmd){var s=mc.getSingleplayerServer();s.execute(()->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),cmd));}
 private static void tick(Minecraft mc){try{
  mc.options.pauseOnLostFocus=false;
  if(mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
  if(ticks-->0)return;
  if(phase==0){if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("UpdateModScreen"))mc.gui.setScreen(new TitleScreen());if(!(mc.gui.screen() instanceof TitleScreen))return;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Cannot open isolated world");});phase=1;ticks=20;}
  else if(phase==1){if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;mc.getWindow().setWindowed(Integer.getInteger("ce.outputWidth",1920),Integer.getInteger("ce.outputHeight",1200));mc.options.renderDistance().set(8);mc.options.simulationDistance().set(5);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());command(mc,"gamemode spectator @p");command(mc,"time set 6000");begin=System.nanoTime();first=RtComposite.frameCounter();GpuTimingSnapshot.begin();Cadence.begin();phase=2;ticks=120;}
  else if(phase==2){if(point>=route.length*Integer.getInteger("ce.streamRounds",1)){phase=3;return;}if(point==6&&reload==null&&!Boolean.getBoolean("ce.skipReload")){reload=mc.reloadResourcePacks();ticks=20;return;}if(reload!=null&&!reload.isDone()){ticks=20;return;}int[] p=route[(point++)%route.length];command(mc,"tp @p "+p[0]+" "+p[1]+" "+p[2]+" 35 20");Files.writeString(out.resolve("stream-progress.json"),"{\"point\":"+point+",\"width\":"+mc.getWindow().getWidth()+",\"height\":"+mc.getWindow().getHeight()+"}");ticks=120;}
  else if(phase==3){double seconds=(System.nanoTime()-begin)/1e9;Cadence.write(out.resolve("stream-intervals.csv"));GpuTimingSnapshot.write(out.resolve("stream-gpu.txt"));Files.writeString(out.resolve("stream-result.json"),"{\"waypoints\":"+point+",\"resourceReload\":"+(reload!=null&&reload.isDone())+",\"seconds\":"+seconds+",\"baseFps\":"+((RtComposite.frameCounter()-first)/seconds)+",\"fg\":false}");var s=mc.getSingleplayerServer();saved=s.submit(()->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),"save-all flush"));phase=4;ticks=5;}
  else if(phase==4){if(!saved.isDone())return;saved.get();mc.disconnectWithSavingScreen();phase=5;ticks=20;}
  else if(phase==5){if(mc.level!=null)return;Files.writeString(out.resolve("run-completed.json"),"{\"worldEntered\":true,\"normalWorldExit\":true,\"streamedWaypoints\":"+point+"}");phase=6;mc.stop();}
 }catch(Throwable e){try{Files.writeString(out.resolve("stream-failure.txt"),e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}}
}
