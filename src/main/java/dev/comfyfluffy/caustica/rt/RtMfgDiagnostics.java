package dev.comfyfluffy.caustica.rt;
import java.nio.file.*;
import com.google.gson.JsonParser;
/** Read-only external evidence. A Streamline status file never upgrades native NGX capability. */
public final class RtMfgDiagnostics {
 public static String summary(){
  Path p=Path.of(System.getProperty("caustica.rtxmfgStatus",net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("native-experiments/RTXMFG-Universal.status.json").toString()));
  boolean zh=net.minecraft.client.Minecraft.getInstance().options.languageCode.startsWith("zh");
  String prefix=zh?"后端：NGX Vulkan。":"Backend: NGX Vulkan. ";
  if(dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()) {
   var fg=dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE;
   return (zh?"后端：Streamline。运行时上限 ":"Backend: Streamline. Runtime maximum ")+(fg.multiFrameCountMax()+1)+"x"
     +(zh?"，最近实际呈现 ":", last actual presents ")+fg.framesActuallyPresented()
     +". "+dev.comfyfluffy.caustica.streamline.StreamlineRuntime.experimentalMfgStatus();
  }
  if(dev.comfyfluffy.caustica.CausticaConfig.Rt.Fg.STREAMLINE.value())prefix+=(zh?"已请求 Streamline；需重启或检查初始化日志。":"Streamline requested; restart or inspect initialization logs. ");

  if(!Files.isRegularFile(p))return prefix+(zh?"未找到外部MFG状态。":"No external MFG status.");
  try {
   if(Files.size(p)>65536)return prefix+(zh?"外部状态过大。":"External status too large.");
   var o=JsonParser.parseString(Files.readString(p)).getAsJsonObject();
   long age=System.currentTimeMillis()-Files.getLastModifiedTime(p).toMillis();
   String provider=o.has("activeWrapperPath")?o.get("activeWrapperPath").getAsString():"";
   boolean streamline=provider.contains("sl.dlss_g");
   if(age>60000 || age<0)return prefix+(zh?"RTXMFG记录已过期，不能证明当前可用。":"RTXMFG record is stale; no current capability evidence.");
   return prefix+(streamline?(zh?"外部为Streamline，未连接本NGX后端。":"External Streamline is not connected to this NGX backend."):(zh?"外部记录不能替代本后端能力探测。":"External evidence does not replace native capability probing."));
  }catch(Exception e){return prefix+(zh?"外部MFG状态无法读取。":"External MFG status unreadable.");}
 }
 private RtMfgDiagnostics(){}
}
