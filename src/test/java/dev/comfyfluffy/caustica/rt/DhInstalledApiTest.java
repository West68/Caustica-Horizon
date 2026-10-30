package dev.comfyfluffy.caustica.rt;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.Modifier;
import org.objectweb.asm.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;
/** Only load metadata (initialize=false). Never executes a DH/Minecraft static initializer. */
class DhInstalledApiTest {
 @Test void installedDhProvidesVersionSpecificBridgeAndCaptureTargets() throws Exception {
  String configured=System.getenv("CAUSTICA_DH_JAR");
  Assumptions.assumeTrue(configured!=null,"Set CAUSTICA_DH_JAR for installed-DH ABI validation");
  Path jar=Path.of(configured);assertTrue(Files.isRegularFile(jar));
  try(URLClassLoader loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader())) {
   Class<?> client=type(loader,"core.api.internal.ClientApi"),params=type(loader,"core.render.RenderParams");
   assertTrue(Modifier.isStatic(client.getField("INSTANCE").getModifiers()));
   assertTrue(Modifier.isStatic(client.getField("RENDER_STATE").getModifiers()));
   assertEquals(params,client.getDeclaredField("RENDER_PARAMS").getType());
   assertEquals(void.class,client.getMethod("renderLods").getReturnType());
   assertEquals(void.class,client.getMethod("renderDeferredLodsForShaders").getReturnType());
   Class<?> state=client.getField("RENDER_STATE").getType();
   for(String field:new String[]{"mcProjectionMatrix","mcModelViewMatrix","clientLevelWrapper","vanillaFogEnabled"})assertNotNull(state.getField(field));
   Class<?> builder=type(loader,"core.dataObjects.render.bufferBuilding.LodQuadBuilder");
   for(String name:new String[]{"opaqueQuads","transparentQuads"})assertEquals(java.util.ArrayList[].class,builder.getDeclaredField(name).getType());
   assertEquals(byte.class,type(loader,"core.pos.DhSectionPos").getMethod("getDetailLevel",long.class).getReturnType());
   Class<?> section=type(loader,"core.render.QuadTree.LodRenderSection");
   assertEquals(builder,section.getDeclaredMethod("getAndBuildRenderData").getReturnType());
   var resolver=dev.comfyfluffy.caustica.client.DhLodCapture.class.getDeclaredMethod("levelWrapperField",Class.class);
   resolver.setAccessible(true);
   var wrapperField=(java.lang.reflect.Field)resolver.invoke(null,section);
   assertEquals(type(loader,"core.wrapperInterfaces.world.IClientLevelWrapper"),wrapperField.getType());
   assertEquals(boolean.class,section.getMethod("getRenderingEnabled").getReturnType());
   Class<?> atlas=type(loader,"common.render.blaze.wrappers.texture.BlazeBlockTextureAtlas");
   assertEquals(void.class,atlas.getMethod("uploadPendingTiles").getReturnType());
   assertEquals(com.mojang.blaze3d.textures.GpuTextureView.class,atlas.getMethod("getTextureWrapper").getReturnType().getMethod("getTextureView").getReturnType());
   Class<?> proxyType=type(loader,"api.interfaces.render.IDhApiRenderProxy");
   boolean rasterApi=Arrays.stream(proxyType.getMethods()).anyMatch(m->m.getName().equals("getDhColorTextureBlazeWrapper"));
   if(!rasterApi) {
    assertNotNull(type(loader,"common.render.blaze.BlazeDhMetaRenderer").getMethod("applyToMcTexture",params));
    assertNotNull(type(loader,"common.wrappers.world.ClientLevelWrapper").getMethod("getMinHeight"));
    return;
   }
   Class<?> matrix=params.getField("dhMvmProjMatrix").getType();
   assertEquals(void.class,matrix.getMethod("putValuesInArray",float[].class).getReturnType());
   Class<?> proxy=type(loader,"api.interfaces.render.IDhApiRenderProxy");
   for(String name:new String[]{"isNativeRenderer","getDeferTransparentRendering","getDhColorTextureBlazeWrapper","getDhDepthTextureBlazeWrapper","getDepthRange","getDepthDirection"})assertNotNull(proxy.getMethod(name));
   assertNotNull(type(loader,"api.DhApi$Delayed").getField("renderProxy"));
   Class<?> result=type(loader,"api.objects.DhApiResult");assertNotNull(result.getField("success"));assertNotNull(result.getField("payload"));
   assertEquals(com.mojang.blaze3d.textures.GpuTextureView.class,type(loader,"common.render.blaze.wrappers.texture.BlazeTextureWrapper").getMethod("getTextureView").getReturnType());
   assertNotNull(type(loader,"common.wrappers.McObjectConverter").getMethod("convert",org.joml.Matrix4fc.class));
   assertNotNull(type(loader,"common.wrappers.world.ClientLevelWrapper").getMethod("getWrapper",net.minecraft.client.multiplayer.ClientLevel.class));
   for(String name:new String[]{"copyToMcTexture","clearDhDepthAndColorTextures"})assertEquals(void.class,type(loader,"common.render.blaze.BlazeDhMetaRenderer").getMethod(name,params).getReturnType());
   assertNotNull(type(loader,"common.render.blaze.BlazeDhTerrainRenderer").getDeclaredField("frameIndexMod8"));
   assertEquals(void.class,type(loader,"common.render.blaze.postProcessing.BlazeDhTaaRenderer").getMethod("render",params).getReturnType());
   assertEquals(void.class,type(loader,"common.render.blaze.postProcessing.BlazeDhFogRenderer").getMethod("render",params,type(loader,"api.methods.events.sharedParameterObjects.DhApiFogRenderParam")).getReturnType());
  }
 }
 @Test void nativeMeansOpenGlAndMustNotExcludeBlazeVulkan() throws Exception {
  String jar=System.getenv("CAUSTICA_DH_JAR");
  Assumptions.assumeTrue(jar!=null,"Set CAUSTICA_DH_JAR for installed-DH validation");
  try(var zip=new java.util.zip.ZipFile(jar)) {
   for(String backend:new String[]{"blaze/Blaze","openGl/Gl"}) {
    String entry="com/seibel/distanthorizons/common/render/"+backend+"DhRenderApiDefinition.class";
    List<Integer> instructions=new ArrayList<>();
    try(var in=zip.getInputStream(zip.getEntry(entry))) {
     new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override public MethodVisitor visitMethod(int a,String name,String desc,String sig,String[] exceptions) {
       return name.equals("isNativeRenderer")&&desc.equals("()Z")?new MethodVisitor(Opcodes.ASM9) {
        @Override public void visitInsn(int opcode){instructions.add(opcode);}
       }:null;
      }
     },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
    }
    assertEquals(List.of(backend.startsWith("blaze")?Opcodes.ICONST_0:Opcodes.ICONST_1,Opcodes.IRETURN),instructions);
   }
  }
  String source=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/client/DistantHorizonsCompat.java"));
  assertTrue(source.contains("proxy==null || (boolean)api.nativeRenderer.invoke(proxy)"));
 }
 @Test void cloudOverrideTargetsItsEnableFlagAndStillDeactivatesCachedGroups() throws Exception {
  String jar=System.getenv("CAUSTICA_DH_JAR");Assumptions.assumeTrue(jar!=null);
  List<String> flags=new ArrayList<>(),calls=new ArrayList<>();
  try(var zip=new java.util.zip.ZipFile(jar);var in=zip.getInputStream(zip.getEntry("com/seibel/distanthorizons/core/render/renderer/CloudRenderHandler.class"))) {
   new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
    @Override public MethodVisitor visitMethod(int a,String name,String desc,String sig,String[] exceptions) {
     return name.equals("preRender")?new MethodVisitor(Opcodes.ASM9) {
      String lastField;
      @Override public void visitFieldInsn(int op,String owner,String name,String desc){if(op==Opcodes.GETSTATIC)lastField=name;}
      @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean itf) {
       calls.add(name+desc);
       if(owner.equals("java/lang/Boolean")&&name.equals("booleanValue"))flags.add(lastField);
      }
     }:null;
    }
   },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
  }
  assertEquals("enableCloudRendering",flags.getFirst());
  assertTrue(calls.contains("setActive(Z)V"));
  String mixin=Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/mixin/DhCloudMixin.java"));
  assertTrue(mixin.contains("ordinal=0"));assertTrue(mixin.contains("!DistantHorizonsCompat.capturing()"));
  assertFalse(mixin.contains("ci.cancel()"));
 }
 private static Class<?> type(ClassLoader loader,String suffix) throws ClassNotFoundException {
  return Class.forName("com.seibel.distanthorizons."+suffix,false,loader);
 }
}
