package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
import java.util.*;
import dev.comfyfluffy.caustica.enhanced.test.mixin.WindowAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
/** Installed-JAR baseline. No renderer implementation from IterationRTX. */
public final class BaselineTest implements FabricClientGameTest {
    static void size(ClientGameTestContext c,int width,int height){
        c.runOnClient(mc->{long h=mc.getWindow().handle();org.lwjgl.glfw.GLFW.glfwRestoreWindow(h);mc.getWindow().setWindowed(width,height);org.lwjgl.glfw.GLFW.glfwFocusWindow(h);});c.waitTicks(20);
        c.runOnClient(mc->{try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            var w=stack.mallocInt(1);var h=stack.mallocInt(1);long handle=mc.getWindow().handle();org.lwjgl.glfw.GLFW.glfwPollEvents();
            var access=(WindowAccessor)(Object)mc.getWindow();org.lwjgl.glfw.GLFW.glfwGetWindowSize(handle,w,h);access.ce$width(w.get(0));access.ce$height(h.get(0));org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(handle,w,h);access.ce$fbWidth(w.get(0));access.ce$fbHeight(h.get(0));
        }mc.resizeGui();mc.invalidateSurfaceConfiguration();});
        // Fail loudly instead of silently measuring a wrong resolution (an earlier baseline run was
        // crash-labelled 1080p while the window was actually 2560x1440, which invalidated it).
        c.runOnClient(mc->{int aw=mc.getWindow().getWidth(),ah=mc.getWindow().getHeight();
            if(aw!=width||ah!=height)throw new AssertionError("Window resize failed: actual "+aw+"x"+ah+" != requested "+width+"x"+height);});
        c.waitTicks(160);
    }
    private static void profile(String method,Path out){
        if(!Boolean.getBoolean("caustica.enhanced.profile"))return;
        try{var type=Class.forName("dev.comfyfluffy.caustica.rt.EnhancedGpuTimings");if(out==null)type.getMethod(method).invoke(null);else type.getMethod(method,Path.class).invoke(null,out);}catch(Exception e){throw new RuntimeException(e);}
    }
    public void runTest(ClientGameTestContext c){if(Boolean.getBoolean("ce.integration")){new FidelityIntegrationTest().runTest(c);return;}try{
        Path out=Path.of(System.getProperty("ce.evidence"));Files.createDirectories(out);
        var origin=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("caustica").orElseThrow().getOrigin().getPaths().getFirst();
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(origin)));
        if(!sha.equalsIgnoreCase(System.getProperty("ce.expectedSha")))throw new AssertionError("Wrong installed JAR");
        Files.writeString(out.resolve("renderer-origin.txt"),origin+"\nSHA256="+sha);
        c.waitFor(mc->mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen,600);
        c.runOnClient(mc->mc.createWorldOpenFlows().openWorld("CE-benchmark",()->{throw new AssertionError("Cannot open copied world");}));
        c.waitFor(mc->mc.level!=null && mc.player!=null && mc.gui.screen()==null,1800);
        var server=c.computeOnClient(mc->mc.getSingleplayerServer());var commands=new net.fabricmc.fabric.impl.client.gametest.context.TestServerContextImpl(server);
        for(String cmd:new String[]{"time set 6000","weather clear","gamerule minecraft:advance_time false","gamerule minecraft:advance_weather false","tp @p -40.57037839689626 80 374.25722861887783 36.6746826171875 42.36389923095703"})commands.runCommand(cmd);
        c.runOnClient(mc->{mc.options.fov().set(70);mc.options.framerateLimit().set(260);mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);});c.waitTicks(600);
        // DLAA=5 is native input with RR. 4 is NOT a valid RR quality enum.
        String modeName=System.getProperty("ce.mode","1080p-performance");
        int[][] modes=switch(modeName){case "1440p-ultra"->new int[][]{{2560,1440,3}};case "1080p-native"->new int[][]{{1920,1080,5}};default->new int[][]{{1920,1080,0}};};
        for(int[] mode:modes){
            size(c,mode[0],mode[1]);c.runOnClient(mc->{System.setProperty("caustica.rt.dlssRr.quality",Integer.toString(mode[2]));CausticaConfig.Rt.DlssRr.QUALITY.reloadFromSystemProperties();});c.waitTicks(200);
            for(int repeat=0;repeat<2;repeat++){
                String name=mode[0]+"x"+mode[1]+"-q"+mode[2]+"-r"+repeat;
                c.runOnClient(mc->{Cadence.begin();profile("begin",null);});long first=RtComposite.frameCounter(),start=System.nanoTime();c.waitTicks(600);
                long frames=RtComposite.frameCounter()-first;double seconds=(System.nanoTime()-start)/1e9;
                c.runOnClient(mc->{Cadence.write(out.resolve(name+"-intervals.csv"));profile("write",out.resolve(name+"-gpu.csv"));});
                String state=c.computeOnClient(mc->"{\"baseFps\":"+(frames/seconds)+",\"seconds\":"+seconds+",\"rtFrames\":"+frames+",\"limit\":"+mc.getFramerateLimitTracker().getFramerateLimit()+",\"focused\":"+(org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(),org.lwjgl.glfw.GLFW.GLFW_FOCUSED)!=0)+",\"bounces\":"+CausticaConfig.Rt.Composite.MAX_BOUNCES.value()+",\"spp\":"+CausticaConfig.Rt.Composite.SPP.value()+",\"fg\":false,\"rr\":true,\"quality\":"+mode[2]+",\"fullGpuTimeMeasured\":false,\"actualWidth\":"+mc.getWindow().getWidth()+",\"actualHeight\":"+mc.getWindow().getHeight()+",\"fbWidth\":"+mc.getWindow().getWidth()+",\"guiScale\":"+mc.getWindow().getGuiScale()+",\"requestedWidth\":"+mode[0]+",\"requestedHeight\":"+mode[1]+"}");
                Files.writeString(out.resolve(name+".json"),state);Files.copy(c.takeScreenshot(name),out.resolve(name+".png"),StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(out.resolve("completed.json"),"{\"worldEntered\":true,\"baselineMatrixComplete\":true}");
        new net.fabricmc.fabric.impl.client.gametest.context.TestSingleplayerContextImpl(c,null,server).close();
    }catch(Exception e){throw new RuntimeException(e);}}
}
