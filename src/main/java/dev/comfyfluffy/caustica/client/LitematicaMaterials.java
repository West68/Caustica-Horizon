package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.*;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.accel.*;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import java.nio.*;
import java.util.*;

/** Independent raster material binding. The large canonical images are borrowed, never copied. */
final class LitematicaMaterials {
    static final int PAGES=8;
    // RG halves then BA halves of the BC5 material pages (RtBlockMaterials.PageImages.views order).
    private static final String[] CHANNELS={"Surface", "Normal", "Spec", "SurfaceB", "NormalB", "SpecB"};
    private static final BindGroupLayout EXTRA=layout();
    private static final Map<RenderPipeline,RenderPipeline> PIPELINES=new IdentityHashMap<>();
    private static final List<BorrowedView> images=new ArrayList<>();
    private static BorrowedView depth;
    private static long depthImage;
    private static RtBuffer index;
    private static GpuBuffer params,projection;
    private static final ByteBuffer PARAM_DATA=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
    private static final ByteBuffer PROJ_DATA=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private static final Vector3f LIGHT=new Vector3f(),RADIANCE=new Vector3f();
    private static final Matrix4f VIEW=new Matrix4f();
    private static boolean pbrReady;

    private static BindGroupLayout layout() {
        var b=BindGroupLayout.builder().withUniform("CausticaSchematic",UniformType.UNIFORM_BUFFER);
        for(int p=0;p<PAGES;p++)for(String channel:CHANNELS)b.withSampler("Ce"+channel+p);
        return b.withSampler("CeDepth").build();
    }
    private static final class Pipeline extends RenderPipeline {
        Pipeline(RenderPipeline p) {
            super(Identifier.fromNamespaceAndPath("caustica","schematic/"+p.getLocation().getNamespace()+"/"+p.getLocation().getPath()),
                    Identifier.fromNamespaceAndPath("caustica","litematica_pbr"),Identifier.fromNamespaceAndPath("caustica","litematica_pbr"),
                    p.getShaderDefines(),groups(p),SchematicOverlayState.colors(p.getColorTargetStates()),p.getDepthStencilState(),p.getPolygonMode(),
                    p.isCull(),p.getVertexFormatBindings(),p.getPrimitiveTopology(),p.getSortKey());
        }
        private static List<BindGroupLayout> groups(RenderPipeline p) {
            var list=new ArrayList<>(p.getBindGroupLayouts()); list.add(EXTRA);return List.copyOf(list);
        }
    }
    private static final Map<RenderPipeline,RenderPipeline> OVERLAYS=new IdentityHashMap<>();
    private static final class OverlayPipeline extends RenderPipeline {
        OverlayPipeline(RenderPipeline p) {
            super(Identifier.fromNamespaceAndPath("caustica","schematic_overlay/"+p.getLocation().getNamespace()+"/"+p.getLocation().getPath()),
                    p.getVertexShader(),p.getFragmentShader(),p.getShaderDefines(),p.getBindGroupLayouts(),
                    SchematicOverlayState.colors(p.getColorTargetStates()),p.getDepthStencilState(),p.getPolygonMode(),p.isCull(),
                    p.getVertexFormatBindings(),p.getPrimitiveTopology(),p.getSortKey());
        }
    }
    static RenderPipeline overlayPipeline(RenderPipeline p) {return OVERLAYS.computeIfAbsent(p,OverlayPipeline::new);}
    private static final BindGroupLayout DEPTH_LAYOUT=BindGroupLayout.builder()
            .withUniform("CausticaSchematic",UniformType.UNIFORM_BUFFER).withSampler("CeDepth").build();
    private static final Map<RenderPipeline,RenderPipeline> DEPTH_OVERLAYS=new IdentityHashMap<>();
    /** Same vertex stage and blending as the overlay; the fragment stage adds the RT world depth test. */
    private static final class DepthOverlayPipeline extends RenderPipeline {
        DepthOverlayPipeline(RenderPipeline p) {
            super(Identifier.fromNamespaceAndPath("caustica","schematic_overlay_depth/"+p.getLocation().getNamespace()+"/"+p.getLocation().getPath()),
                    p.getVertexShader(),Identifier.fromNamespaceAndPath("caustica","schematic_overlay_depth"),p.getShaderDefines(),
                    depthGroups(p),SchematicOverlayState.colors(p.getColorTargetStates()),p.getDepthStencilState(),p.getPolygonMode(),p.isCull(),
                    p.getVertexFormatBindings(),p.getPrimitiveTopology(),p.getSortKey());
        }
        private static List<BindGroupLayout> depthGroups(RenderPipeline p) {
            var list=new ArrayList<>(p.getBindGroupLayouts());list.add(DEPTH_LAYOUT);return List.copyOf(list);
        }
    }
    static void setOverlay(RenderPass pass,RenderPipeline original) {
        if(params==null || depth==null) {pass.setPipeline(overlayPipeline(original));return;}
        pass.setPipeline(DEPTH_OVERLAYS.computeIfAbsent(original,DepthOverlayPipeline::new));
        pass.setUniform("CausticaSchematic",params.slice());
        pass.bindTexture("CeDepth",depth,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    /** Vanilla owns only a tiny sentinel allocation. Its destroy() uses its private image field,
     * not vkImage(), so it cannot free the borrowed canonical image. Views are owned here. */
    private static final class BorrowedTexture extends VulkanGpuTexture {
        private final long borrowed;
        BorrowedTexture(VulkanDevice device,long image,GpuFormat format,int mips) {
            super(device,GpuTexture.USAGE_TEXTURE_BINDING,"Caustica borrowed schematic texture",format,
                    1 << (mips-1),1 << (mips-1),1,mips);
            borrowed=image;
        }
        @Override public long vkImage() { return borrowed; }
    }
    private static final class BorrowedView extends VulkanGpuTextureView {
        final BorrowedTexture owner;
        /** Set for a BC5 material page: Blaze3D has no BC formats, so its own view is built on a 1x1
         * RGBA8 stand-in and every bind uses the RT-owned view of the real page instead. */
        private final long override;
        static BorrowedView borrow(VulkanDevice device,long image,GpuFormat format,int mips) {
            return borrow(device,image,format,mips,0L);
        }
        static BorrowedView borrow(VulkanDevice device,long image,GpuFormat format,int mips,long override) {
            var owner=new BorrowedTexture(device,image,format,mips);
            try {return new BorrowedView(device,owner,mips,override);}
            catch(Throwable error) {owner.destroy();throw error;}
        }
        private BorrowedView(VulkanDevice device,BorrowedTexture owner,int mips,long override) {
            super(device,owner,0,mips);this.owner=owner;this.override=override;
        }
        @Override public long vkImageView() { return override!=0L?override:super.vkImageView(); }
        void release() { destroy();owner.destroy(); }
    }
    private static dev.comfyfluffy.caustica.rt.material.RtMaterialStandIn standIn;
    static void prepare(Matrix4fc view,Matrix4fc proj,int width,int height) {
        RtContext ctx=RtContext.currentOrNull();
        if(ctx==null)return;
        var gpu=RenderSystem.getDevice();
        if(params==null)params=gpu.createBuffer(()->"Schematic PBR parameters",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,80);
        if(projection==null)projection=gpu.createBuffer(()->"Schematic world projection",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,64);
        var worldDepth=RtComposite.INSTANCE.schematicDepth();
        if(worldDepth==null)return;
        if(depthImage!=worldDepth.image) {
            // Old depth bindings are released at the renderer's idle resize/epoch boundary.
            if(depth!=null)throw new IllegalStateException("Schematic depth epoch was not retired");
            depth=BorrowedView.borrow(ctx.device(),worldDepth.image,GpuFormat.RGBA16_FLOAT,1);depthImage=worldDepth.image;
        }
        if(index==null)createIndex(ctx);
        pbrReady=index!=null && !images.isEmpty();
        VIEW.set(view);RtComposite.INSTANCE.schematicLight(LIGHT);VIEW.transformDirection(LIGHT).normalize();
        RtComposite.INSTANCE.schematicRadiance(RADIANCE);
        PARAM_DATA.clear();
        long address=index==null?0:index.deviceAddress;
        PARAM_DATA.putInt((int)address).putInt((int)(address>>>32)).putInt(SchematicUvIndex.GRID).putInt(pbrReady?1:0);
        PARAM_DATA.putFloat(LIGHT.x).putFloat(LIGHT.y).putFloat(LIGHT.z).putFloat(0);
        PARAM_DATA.putFloat(.25f*CausticaConfig.Rt.Material.PARALLAX_STRENGTH.value())
                .putFloat(CausticaConfig.Rt.Material.PARALLAX.value()).putFloat(width).putFloat(height);
        PARAM_DATA.putFloat(RADIANCE.x).putFloat(RADIANCE.y).putFloat(RADIANCE.z).putFloat(1);
        PARAM_DATA.putFloat(CausticaConfig.Rt.Material.SMOOTH_STEPS.value()).putFloat(CausticaConfig.Rt.Material.SMOOTH_HEIGHT_NORMAL.value()?1:0)
                .putFloat(CausticaConfig.Rt.Material.SMOOTH_SHADOW.value()?1:0).putFloat(0).flip();
        var enc=gpu.createCommandEncoder();enc.writeToBuffer(params.slice(),PARAM_DATA);
        PROJ_DATA.clear();proj.get(0,PROJ_DATA);PROJ_DATA.position(0).limit(64);enc.writeToBuffer(projection.slice(),PROJ_DATA);
    }
    private static void createIndex(RtContext ctx) {
        var pages=RtBlockMaterials.INSTANCE.pageImages();
        if(pages.isEmpty())return;
        if(pages.size()>PAGES)dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn(
                "Schematic PBR exposes {} of {} material pages; remaining pages keep their base texture",PAGES,pages.size());
        var tiles=new ArrayList<SchematicUvIndex.Tile>();
        for(var item:RtBlockMaterials.INSTANCE.preparedEntries().entrySet()) {
            var s=item.getKey();var e=item.getValue();
            // More than eight pages remain visible with their base material; never sample another page.
            if(e.pageIndex()<0 || e.pageIndex()>=PAGES)continue;
            tiles.add(new SchematicUvIndex.Tile(s.getU0(),s.getV0(),s.getU1(),s.getV1(),
                    e.materialU(),e.materialV(),e.materialDu(),e.materialDv(),e.pageIndex(),e.maxLod(),e.features()));
        }
        tiles.sort(Comparator.comparingDouble(SchematicUvIndex.Tile::u0).thenComparingDouble(SchematicUvIndex.Tile::v0));
        var data=SchematicUvIndex.build(tiles);
        index=ctx.createBuffer(data.remaining(),VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true,"schematic material UV index");
        MemoryUtil.memByteBuffer(index.mapped,data.remaining()).put(data);index.flush();
        if(standIn==null)standIn=dev.comfyfluffy.caustica.rt.material.RtMaterialStandIn.create(ctx);
        for(int p=0;p<PAGES;p++) {
            var page=pages.get(Math.min(p,pages.size()-1));
            for(long view:page.views())
                images.add(BorrowedView.borrow(ctx.device(),standIn.image(),GpuFormat.RGBA8_UNORM,1,view));
        }
    }
    static void setPipeline(RenderPass pass,RenderPipeline original) {
        if(params==null || depth==null || !pbrReady) {setOverlay(pass,original);return;}
        RenderPipeline pipeline=PIPELINES.computeIfAbsent(original,Pipeline::new);
        pass.setPipeline(pipeline);
        pass.setUniform("CausticaSchematic",params.slice());
        var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR,true);
        for(int p=0;p<PAGES;p++)for(int c=0;c<CHANNELS.length;c++)pass.bindTexture("Ce"+CHANNELS[c]+p,images.get(p*CHANNELS.length+c),sampler);
        pass.bindTexture("CeDepth",depth,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    static GpuBuffer projection() { return projection; }
    private static final BindGroupLayout DEPTH_FILL_LAYOUT=BindGroupLayout.builder().withSampler("CeDepth").build();
    private static final RenderPipeline DEPTH_FILL=RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("pipeline/caustica_rt_depth_fill")
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("caustica","rt_depth_fill"))
            .withBindGroupLayout(DEPTH_FILL_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_NONE))
            .withDepthStencilState(new DepthStencilState(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS,true))
            .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
            .build();
    private static GpuTexture depthFillTexture;
    private static GpuTextureView depthFillView;
    /** Fill {@code target}'s depth from the RT guide depth (prepare() must have run this frame). */
    static boolean fillDepth(RenderTarget target) {
        if(depth==null || !target.useDepth || target.getDepthTexture()==null)return false;
        GpuTexture texture=target.getDepthTexture();
        if(texture!=depthFillTexture) {
            if(depthFillView!=null)depthFillView.close();
            depthFillView=RenderSystem.getDevice().createTextureView(texture);depthFillTexture=texture;
        }
        var enc=RenderSystem.getDevice().createCommandEncoder();
        try(RenderPass pass=enc.createRenderPass(()->"Caustica RT depth fill",target.getColorTextureView(),Optional.empty(),
                depthFillView,java.util.OptionalDouble.empty())) {
            pass.setPipeline(DEPTH_FILL);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("CeDepth",depth,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3,1,0,0);
        }
        return true;
    }
    static void releaseDepth() {
        if(depth!=null)depth.release();depth=null;depthImage=0;
    }
    static void releaseMaterials() {
        for(var view:images)view.release();images.clear();
        if(index!=null)index.destroy();index=null;pbrReady=false;
    }
    static void destroy() {
        releaseDepth();releaseMaterials();
        if(standIn!=null){standIn.destroy();standIn=null;}
        if(params!=null)params.close();if(projection!=null)projection.close();
        params=null;projection=null;PIPELINES.clear();OVERLAYS.clear();DEPTH_OVERLAYS.clear();
        if(depthFillView!=null)depthFillView.close();depthFillView=null;depthFillTexture=null;
    }
    private LitematicaMaterials() {}
}
