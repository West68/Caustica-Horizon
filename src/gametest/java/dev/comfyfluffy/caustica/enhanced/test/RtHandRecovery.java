package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.item.*;
import net.minecraft.world.InteractionHand;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.entity.*;
import dev.comfyfluffy.caustica.client.*;
/** Isolated test mod; uses the normal animated submit path, never desktop automation. */
final class RtHandRecovery {
 private static int stage,wait;private static long captured,frames;private static final Path out=Path.of(System.getProperty("ce.evidence","."));
 static void start(){ClientTickEvents.END_CLIENT_TICK.register(RtHandRecovery::tick);}
 private static void tick(Minecraft mc){try {
  if(mc.level!=null&&mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
  // Keep the scripted observation point above water; creative mode alone still permits gravity.
  if(stage>=2&&mc.player!=null){mc.player.getAbilities().flying=true;if(Boolean.getBoolean("ce.lockView")){mc.player.setYRot(36);mc.player.setXRot(stage==11?6:8);}}
  if(wait-->0)return;
  switch(stage){
   case 0 -> {mc.options.pauseOnLostFocus=false;if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("UpdateModScreen"))mc.gui.setScreen(new TitleScreen());if(!(mc.gui.screen() instanceof TitleScreen))return;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("Missing isolated world");});stage++;wait=20;}
   case 1 -> {if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);mc.options.renderDistance().set(6);mc.getWindow().setWindowed(1280,720);var server=mc.getSingleplayerServer();server.execute(()->{for(String cmd:new String[]{"gamemode creative @p","tp @p 9 68 254 36 8","time set 6000","weather clear"})server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),cmd);});stage++;wait=Integer.getInteger("ce.handWarmTicks",600);}
   case 2,4,6 -> {if(stage==6)System.setProperty("caustica.test.dhHitMarker","true");mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(stage==2?Items.DIAMOND_SWORD:stage==4?Items.STONE:Items.AIR));captured=RtFirstPerson.capturedFrames;frames=RtComposite.frameCounter();stage++;wait=100;}
   case 3,5,7 -> {if(DistantHorizonsCompat.textures().x()!=0)throw new AssertionError("Duplicate DH raster inputs still active");if(RtContext.currentOrNull()==null||RtComposite.INSTANCE.hasFailed()||RtComposite.frameCounter()-frames<10)throw new AssertionError("RT not rendering");long hands=RtFirstPerson.capturedFrames-captured;Files.writeString(out.resolve("hand-"+stage+".txt"),"capturedFrames="+hands+" lastTriangles="+RtFirstPerson.triangles+"\n"+DhLodCapture.stats()+"\n"+RtDhLods.INSTANCE.stats());if(hands<10)throw new AssertionError("Animated hands were not captured: "+hands);GpuReadback.frame(out,"hand-"+stage);if(stage==7){Files.writeString(out.resolve("dh-hardware-hits.txt"),RtDhLods.INSTANCE.readTestCounters());System.clearProperty("caustica.test.dhHitMarker");}stage++;wait=20;}
   case 8 -> {if(DhLodCapture.sectionCount()==0)throw new AssertionError("DH capture unavailable: "+DhLodCapture.stats());var server=mc.getSingleplayerServer();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"execute as @a in minecraft:overworld run tp @s 137 90 254 36 8"));frames=RtComposite.frameCounter();stage=10;wait=300;}
   case 10 -> {if(RtComposite.INSTANCE.hasFailed()||RtComposite.frameCounter()-frames<10||DhLodCapture.sectionCount()==0)throw new AssertionError("LOD movement stopped RT/capture");Files.writeString(out.resolve("lod-after-move.txt"),DhLodCapture.stats()+"\n"+RtDhLods.INSTANCE.stats());GpuReadback.frame(out,"lod-after-move");if(Boolean.getBoolean("ce.underwaterProbe")){var server=mc.getSingleplayerServer();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"execute as @a in minecraft:overworld run tp @s 9 60 254 36 6"));stage=11;wait=150;}else{mc.disconnectWithSavingScreen();stage=9;wait=40;}}
   case 11 -> {if(RtComposite.INSTANCE.hasFailed()||DhLodTextures.slot()==0)throw new AssertionError("Underwater RT/texture atlas unavailable");GpuReadback.frame(out,"underwater-boundary");Files.writeString(out.resolve("underwater-materials.txt"),"atlasSlot="+DhLodTextures.slot()+"\n"+RtDhLods.INSTANCE.stats());mc.disconnectWithSavingScreen();stage=9;wait=40;}
   case 9 -> {if(mc.level!=null)return;Files.writeString(out.resolve("run-completed.json"),"{\"rtRendering\":true,\"handCapture\":true,\"dhCapture\":true,\"visualAccepted\":false}");mc.stop();stage++;}
  }
 }catch(Throwable e){try{Files.writeString(out.resolve("hand-failure.txt"),e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}}
}
