package dev.comfyfluffy.caustica.enhanced.test;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.*;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.entity.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
/** Isolated normal client: measure LOD refinement and dimension round trips without desktop control. */
final class DhRefinementRegression {
 private static final Path OUT=Path.of(System.getProperty("ce.evidence"));
 private static int stage,ticks,elapsed;private static long firstFrame,start;
 private static Object field(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private static void command(Minecraft mc,String cmd){var server=mc.getSingleplayerServer();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),cmd));}
 static void start(){ClientTickEvents.END_CLIENT_TICK.register(DhRefinementRegression::tick);}
 @SuppressWarnings("unchecked") private static void sample(Minecraft mc,boolean detailed)throws Exception {
  var sources=DhLodCapture.activeSections(mc.level);
  var lods=RtDhLods.INSTANCE;
  var cache=(Map<Long,RtDhLods.Entry>)field(lods,"cache");
  var selected=(List<RtDhLods.Entry>)field(lods,"selected");
  int need=0,raw=0,closeDemand=0,closeReady=0;long sourceQuads=0,renderQuads=0;
  StringBuilder details=new StringBuilder("pos,detail,distance,sourceQuads,cachedQuads,resolution,selected,versionMatches\n");
  for(var s:sources){double d=DhLodSelection.distance(s,mc.player.getX(),mc.player.getZ());if(d<512.0*512&&s.quads().size()>2048)need++;sourceQuads+=s.quads().size();var e=cache.get(s.pos());
   if(d<128.0*128&&!s.quads().isEmpty()&&!lods.coverage().covers(s.minBlockX(),s.minBlockZ(),1<<s.detailLevel())){closeDemand++;if(e!=null&&e.resolution==64&&e.snapshot.version()==s.version()&&selected.contains(e))closeReady++;}
   if(detailed)details.append(s.pos()).append(',').append(s.detailLevel()).append(',').append(java.lang.Math.sqrt(d)).append(',').append(s.quads().size()).append(',').append(e==null?0:e.snapshot.quads().size()).append(',').append(e==null?0:e.resolution).append(',').append(e!=null&&selected.contains(e)).append(',').append(e!=null&&e.snapshot.version()==s.version()).append('\n');}
  for(var e:selected){if(e.resolution==64)raw++;renderQuads+=e.snapshot.quads().size();}
  var terrain=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
  int near=terrain==null?0:terrain.staticInstances().size();
  String line=elapsed+","+stage+","+mc.level.dimension().identifier()+","+mc.player.getX()+","+mc.player.getZ()+","+sources.size()+","+need+","+raw+","+selected.size()+","+sourceQuads+","+renderQuads+","+near+","+closeDemand+","+closeReady+","+RtComposite.INSTANCE.hasFailed()+",\""+lods.stats()+"\"\n";
  Files.writeString(OUT.resolve("refinement.csv"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  if(detailed){Files.writeString(OUT.resolve("sections-"+stage+".csv"),details);Files.writeString(OUT.resolve("state-"+stage+".txt"),DhLodCapture.stats()+"\n"+lods.stats()+"\n"+lods.readTestCounters()+"\n"+DistantHorizonsCompat.diagnostics());GpuReadback.frame(OUT,"scene-"+stage);}
 }
 private static void tick(Minecraft mc){try {
  if(mc.level!=null&&mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
  if(stage==0){mc.options.pauseOnLostFocus=false;if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("UpdateModScreen"))mc.gui.setScreen(new TitleScreen());if(!(mc.gui.screen() instanceof TitleScreen))return;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Isolated fixture unavailable");});stage=1;return;}
  if(stage==1){if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(120);mc.options.renderDistance().set(6);mc.getWindow().setWindowed(Integer.getInteger("ce.dhWidth",1280),Integer.getInteger("ce.dhHeight",720));CausticaConfig.Rt.Visuals.DH_LODS.set(true);CausticaConfig.Rt.Fg.ENABLED.set(false);command(mc,"gamemode creative @p");command(mc,"gamerule minecraft:advance_time false");command(mc,"gamerule minecraft:advance_weather false");command(mc,"time set 6000");command(mc,"weather clear");command(mc,"execute as @p in minecraft:overworld run tp @s 9 75 254 36 8");if(Boolean.getBoolean("ce.dhFarOnly")){command(mc,"execute as @p in minecraft:overworld run tp @s 198 118 131 36 8");stage=8;}else stage=2;ticks=0;start=System.nanoTime();firstFrame=RtComposite.frameCounter();Files.writeString(OUT.resolve("refinement.csv"),"tick,stage,dimension,x,z,active,nearRawDemand,rawSelected,selected,sourceQuads,renderQuads,nearInstances,closeDemand,closeReady,rtFailed,stats\n");return;}
  if(stage>=9){if(mc.level==null&&++ticks>=40){Files.writeString(OUT.resolve("dh-after-disconnect.txt"),RtDhLods.INSTANCE.stats());Files.writeString(OUT.resolve("run-completed.json"),"{\"dimensionRoundTrip\":"+!Boolean.getBoolean("ce.dhFarOnly")+",\"visualAccepted\":false}");mc.stop();stage=99;}return;}
  if(mc.level==null||mc.player==null)return;
  net.minecraft.client.KeyMapping.releaseAll();mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);mc.player.setXRot(8);mc.player.setYRot(36);
  ticks++;elapsed++;if(ticks%40==0)sample(mc,false);
  if(Boolean.getBoolean("ce.dhResize")&&stage==8&&ticks>=240&&ticks<=880&&ticks%8==0){
   int[][] sizes={{1280,720},{2560,1440},{960,540},{1920,1080},{1600,900},{2560,1440},{854,480},{1920,1080}};
   int[] size=sizes[(ticks/8)%sizes.length];
   Files.writeString(OUT.resolve("resize-events.csv"),ticks+","+mc.getWindow().getWidth()+","+mc.getWindow().getHeight()+","+size[0]+","+size[1]+","+RtComposite.frameCounter()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
   mc.getWindow().setWindowed(size[0],size[1]);
  }
  if(stage==8&&ticks==400){System.setProperty("caustica.test.dhHitMarker","false");Cadence.begin();GpuTimingSnapshot.begin();}
  int duration=stage==2?600:stage==7?800:stage==8?1600:400;
  if(ticks<duration)return;
  if(stage==8){Cadence.write(OUT.resolve("far-steady-cadence.csv"));GpuTimingSnapshot.write(OUT.resolve("far-steady-gpu.json"));}
  sample(mc,true);
  if(RtComposite.INSTANCE.hasFailed())throw new AssertionError("RT failed");
  if(stage==2)command(mc,"execute as @p in minecraft:overworld run tp @s 265 75 254 36 8");
  if(stage==3)command(mc,"execute as @p in minecraft:the_end run tp @s 82 75 -36 36 8");
  if(stage==4)command(mc,"execute as @p in minecraft:overworld run tp @s 9 75 254 36 8");
  if(stage==5)command(mc,"execute as @p in minecraft:the_end run tp @s 82 75 -36 36 8");
  if(stage==6)command(mc,"execute as @p in minecraft:overworld run tp @s 9 75 254 36 8");
  if(stage==7)command(mc,"execute as @p in minecraft:overworld run tp @s 198 118 131 36 8");
  if(stage==8){Files.writeString(OUT.resolve("cadence.json"),"{\"seconds\":"+((System.nanoTime()-start)/1e9)+",\"realFrames\":"+(RtComposite.frameCounter()-firstFrame)+"}");mc.disconnectWithSavingScreen();stage=9;ticks=0;return;}
  stage++;ticks=0;
 }catch(Throwable e){try{Files.writeString(OUT.resolve("refinement-failure.txt"),e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}}
}
