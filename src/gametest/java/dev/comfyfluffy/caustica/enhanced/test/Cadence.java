package dev.comfyfluffy.caustica.enhanced.test;
import java.nio.file.*;
public final class Cadence {
 private static boolean active;private static long previous;private static final StringBuilder csv=new StringBuilder();
 public static void begin(){previous=0;csv.setLength(0);csv.append("frameIntervalMs,fpsLimit,nativeFocused\n");active=true;}
 public static void frame(){if(!active)return;long now=System.nanoTime();var mc=net.minecraft.client.Minecraft.getInstance();if(previous!=0)csv.append((now-previous)/1e6).append(',').append(mc.getFramerateLimitTracker().getFramerateLimit()).append(',').append(org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(),org.lwjgl.glfw.GLFW.GLFW_FOCUSED)).append('\n');previous=now;}
 public static void write(Path p){active=false;try{Files.writeString(p,csv);}catch(Exception e){throw new RuntimeException(e);}}
}
