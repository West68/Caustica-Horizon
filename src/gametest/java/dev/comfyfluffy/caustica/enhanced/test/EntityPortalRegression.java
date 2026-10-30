package dev.comfyfluffy.caustica.enhanced.test;

import com.google.gson.JsonObject;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Future;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;

/** Disposable scene and raw mesh continuity evidence; never included in the renderer JAR. */
public final class EntityPortalRegression {
    private static final boolean ENABLED=Boolean.getBoolean("ce.entityPortalRegression");
    private static final Path OUT=Path.of(System.getProperty("ce.evidence", "."));
    private static final StringBuilder CSV=new StringBuilder("frame,id,type,vertices,rmsDelta,maxDelta\n");
    private static final Map<Integer,float[]> previous=new HashMap<>();
    private static int stage,frames;
    private static long deadline;
    private static Future<?> saved;
    static void start(){ClientTickEvents.END_CLIENT_TICK.register(EntityPortalRegression::tick);}
    private static Object field(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    public static void frame(){
        if(!ENABLED || stage!=3)return;
        try{
            var mc=Minecraft.getInstance();if(mc.level==null)return;
            var map=(it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>)field(RtEntities.INSTANCE,"prevVerts");
            for(var entity:mc.level.entitiesForRendering())if(entity instanceof net.minecraft.world.entity.Mob
                    && entity.getY()>179 && entity.getY()<186 && Math.abs(entity.getX())<5 && entity.getZ()>2 && entity.getZ()<6){
                Object mesh=map.get(entity.getId());if(mesh==null)continue;
                float[] v=(float[])field(mesh,"verts");int size=(int)field(mesh,"size");
                float[] old=previous.get(entity.getId());double sum=0,max=0;
                if(old!=null && old.length==size)for(int i=0;i<size;i++){double d=v[i]-old[i];sum+=d*d;max=Math.max(max,Math.abs(d));}
                CSV.append(frames).append(',').append(entity.getId()).append(',').append(entity.getType().toShortString()).append(',').append(size/3).append(',').append(Math.sqrt(sum/Math.max(1,size))).append(',').append(max).append('\n');
                previous.put(entity.getId(),Arrays.copyOf(v,size));
            }
            frames++;
        }catch(Exception e){throw new RuntimeException(e);}
    }
    private static void commands(Minecraft mc,String... lines){var server=mc.getSingleplayerServer();server.execute(()->{for(var s:lines)server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),s);});}
    private static void shot(Minecraft mc,String name){Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),image->{try(image){image.writeToFile(OUT.resolve(name+".png"));}catch(Exception e){throw new RuntimeException(e);}});}
    private static void portal(Minecraft mc)throws Exception{
        var out=new JsonObject();
        var cache=(Map<?,?>)field(RtEntities.INSTANCE,"beCache");
        var dispatcher=mc.getBlockEntityRenderDispatcher();
        for(int x=-3;x<=-1;x++)for(int z=11;z<=13;z++){
            var pos=new BlockPos(x,180,z);var be=mc.level.getBlockEntity(pos);if(be==null)continue;
            var p=new JsonObject();p.addProperty("type",be.getClass().getName());p.addProperty("inRtCache",cache.containsKey(pos.asLong()));
            p.addProperty("vanillaExtractNonNull",dispatcher.tryExtractRenderState(be,mc.getDeltaTracker().getGameTimeDeltaPartialTick(false),null,false)!=null);
            try{var c=Class.forName("dev.tr7zw.entityculling.access.Cullable");p.addProperty("cameraCulled",(boolean)c.getMethod("isCulled").invoke(be));}catch(ClassNotFoundException ignored){}
            out.add(pos.toShortString(),p);
        }
        Files.writeString(OUT.resolve("portal-capture.json"),out.toString());
    }
    private static void tick(Minecraft mc){
        try{
            mc.options.pauseOnLostFocus=false;
            if(mc.level!=null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)mc.gui.setScreen(null);
            switch(stage){
                case 0->{if(!(mc.gui.screen() instanceof TitleScreen))return;mc.createWorldOpenFlows().openWorld("CE-work-fixture",()->{throw new AssertionError("missing isolated fixture");});stage=1;}
                case 1->{if(mc.level==null||mc.player==null||mc.gui.screen()!=null)return;
                    mc.getWindow().setWindowed(1920,1080);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);
                    CausticaConfig.Rt.Fg.ENABLED.set(false);CausticaConfig.Rt.DlssRr.QUALITY.set(0);CausticaConfig.Rt.DlssRr.SR_ONLY.set(false);
                    commands(mc,"gamemode creative","time set 6000","weather clear","gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false","tp @p 0.5 181 7.5 180 12","fill -9 179 -2 9 179 17 stone","fill -9 180 -2 9 189 17 air","fill -7 180 0 7 187 0 red_nether_bricks","fill -3 180 11 -1 180 13 end_portal","kill @e[tag=ce_bugfix]","summon cow -3 180 4 {NoAI:1b,PersistenceRequired:1b,Tags:[\"ce_bugfix\"],Rotation:[0f,0f]}","summon cow 3 180 4 {NoAI:1b,PersistenceRequired:1b,Tags:[\"ce_bugfix\"],Rotation:[180f,0f]}","summon villager 0 180 4 {NoAI:1b,PersistenceRequired:1b,Tags:[\"ce_bugfix\"]}");
                    org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().handle());mc.mouseHandler.releaseMouse();deadline=System.nanoTime()+60_000_000_000L;stage=2;}
                case 2->{if(System.nanoTime()<deadline)return;portal(mc);shot(mc,"mirror");deadline=System.nanoTime()+20_000_000_000L;stage=3;}
                case 3->{if(System.nanoTime()<deadline)return;Files.writeString(OUT.resolve("entity-continuity.csv"),CSV);commands(mc,"tp @p 0.5 181 7.5 0 24");deadline=System.nanoTime()+5_000_000_000L;stage=4;}
                case 4->{if(System.nanoTime()<deadline)return;shot(mc,"portal-direct");saved=mc.getSingleplayerServer().submit(()->mc.getSingleplayerServer().getCommands().performPrefixedCommand(mc.getSingleplayerServer().createCommandSourceStack(),"save-all flush"));stage=5;}
                case 5->{if(!saved.isDone())return;saved.get();mc.disconnectWithSavingScreen();stage=6;}
                case 6->{if(mc.level!=null)return;Files.writeString(OUT.resolve("run-completed.json"),"{\"worldEntered\":true,\"normalWorldExit\":true,\"frames\":"+frames+"}");mc.stop();stage=7;}
            }
        }catch(Exception e){try{Files.writeString(OUT.resolve("entity-portal-failure.txt"),e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}
    }
}
