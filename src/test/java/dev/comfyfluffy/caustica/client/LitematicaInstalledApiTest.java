package dev.comfyfluffy.caustica.client;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads installed bytecode only; does not initialize the mod or Minecraft. */
class LitematicaInstalledApiTest {
    private static Path installed() {
        String path=System.getenv("CAUSTICA_LITEMATICA_JAR");
        Assumptions.assumeTrue(path!=null,"Set CAUSTICA_LITEMATICA_JAR for real installed ABI verification");
        return Path.of(path);
    }
    private static ClassReader read(ZipFile zip,String name)throws Exception {
        try(var input=zip.getInputStream(zip.getEntry(name+".class"))) {return new ClassReader(input);}
    }
    @Test void installedPublicMethodsMatchTheBridge()throws Exception {
        try(var zip=new ZipFile(installed().toFile())) {
            var methods=new HashMap<String,String>();
            read(zip,"fi/dy/masa/litematica/render/LitematicaRenderer").accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    if((a&Opcodes.ACC_PUBLIC)!=0)methods.put(n,d);return null;
                }
            },ClassReader.SKIP_CODE);
            for(String name:List.of("getInstance","getWorldRenderer","updateCameraState","updateConfigState","piecewisePrepare",
                    "piecewiseUpdate","scheduleTranslucentSorting","capturePreMainValues","uploadRemainingBuffers",
                    "piecewisePrepareBlockLayers","piecewiseDrawBlockLayerGroup","piecewiseRenderOverlay"))assertTrue(methods.containsKey(name),name);
            assertEquals("(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",methods.get("piecewiseDrawBlockLayerGroup"));
            assertEquals("(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/CameraRenderState;)V",methods.get("updateCameraState"));
        }
    }
    @Test void installedDrawInstructionsContainBothMixinTargets()throws Exception {
        try(var zip=new ZipFile(installed().toFile())) {
            var calls=new HashSet<String>();
            for(String type:List.of("ChunkRenderBatchDraw","WorldRendererSchematic"))
                read(zip,"fi/dy/masa/litematica/render/schematic/"+type).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                        if(!n.equals("draw")&&!n.equals("drawOverlayInternal"))return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean itf) {calls.add(owner+";"+name+desc);}
                        };
                    }
                },0);
            assertTrue(calls.contains("net/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"));
            assertTrue(calls.contains("com/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"));
            assertTrue(calls.contains("fi/dy/masa/malilib/render/RenderUtils;mainTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"));
        }
    }
    @Test void borrowedTextureCannotDestroyCanonicalImageThroughVirtualAccessor()throws Exception {
        try(var input=getClass().getClassLoader().getResourceAsStream("com/mojang/blaze3d/vulkan/VulkanGpuTexture.class")) {
            assertNotNull(input);var calls=new ArrayList<String>();var fields=new ArrayList<String>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    return n.equals("destroy")?new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int op,String owner,String n,String desc) {fields.add(n);}
                        @Override public void visitMethodInsn(int op,String owner,String n,String desc,boolean itf) {calls.add(n);}
                    }:null;
                }
            },0);
            assertTrue(fields.contains("vkImage"));assertTrue(fields.contains("vmaAllocation"));
            assertTrue(calls.contains("vmaDestroyImage"));assertFalse(calls.contains("vkImage"));
        }
    }
}
