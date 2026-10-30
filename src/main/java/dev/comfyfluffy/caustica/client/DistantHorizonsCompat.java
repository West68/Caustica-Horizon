package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import java.lang.reflect.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.*;

/** Version-checked optional DH 3.3.3 Blaze bridge. Owns no LOD geometry, images or acceleration structures. */
public final class DistantHorizonsCompat {
    private static final boolean INSTALLED=FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static boolean capturing,cleared,failed,logged;
    private static long lastColor,lastDepth;
    private static int unreadyFrames;
    private static long attempts;
    private static String validation="not called";
    public static String diagnostics(){return "attempts="+attempts+" validation="+validation+" failed="+failed;}
    public static boolean hardwareOnly(){return capturing && api!=null && api.hardwareOnly;}
    private static Api api;
    private static Boolean previousSecondary;
    private static final Matrix4f projection=new Matrix4f(),vp=new Matrix4f(),inverse=new Matrix4f();
    private static final float[] matrixRows=new float[16];
    private static final Int4 NONE=new Int4(0,0,0,0);
    private static Int4 textures=NONE;
    private DistantHorizonsCompat() {}
    public static String shaderVariant(String base){return INSTALLED?base.replace(".rgen.spv","_dh.rgen.spv"):base;}
    public static boolean capturing(){return capturing;}
    /** DH's own raster output (color/depth targets, SSAO, fades, fog, TAA, boxes) is never read while Caustica RT
     *  draws the world: LOD geometry is captured on DH's build thread and traced in hardware. Skipping it saves
     *  ~13 screen-sized targets (~200 MiB at 2560x1440) and their per-frame passes. A failed RT returns to vanilla. */
    public static boolean rasterUnused(){return capturing || dev.comfyfluffy.caustica.client.VanillaRenderController.rtRuntimeWorkRequested();}
    public static void texturesCleared(){if(capturing)cleared=true;}
    public static Matrix4fc viewProjection(){return vp;}
    public static Matrix4fc inverseViewProjection(){return inverse;}
    public static Int4 textures(){return textures;}

    public static void prepare(RtContext ctx,Matrix4fc worldProjection,Matrix4fc view,float jitterX,float jitterY,
            int renderWidth,int renderHeight) {
        boolean previouslyActive=textures.x()!=0;
        boolean secondary=CausticaConfig.Rt.Visuals.DH_SECONDARY.value();
        if(previousSecondary!=null && previousSecondary!=secondary)dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.requestHistoryReset();
        previousSecondary=secondary;
        try { prepareInputs(ctx,worldProjection,view,jitterX,jitterY,renderWidth,renderHeight); }
        finally {
            if(previouslyActive!=(textures.x()!=0))dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.requestHistoryReset();
        }
    }
    private static void prepareInputs(RtContext ctx,Matrix4fc worldProjection,Matrix4fc view,float jitterX,float jitterY,
            int renderWidth,int renderHeight) {
        textures=NONE;
        if(!INSTALLED || failed || !CausticaConfig.Rt.Visuals.DH_LODS.value()
                || !VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame())return;
        try {
            if(api==null)api=new Api();
            attempts++;
            Object proxy=api.renderProxy.get(null);
            // DH "native" means its legacy OpenGL renderer, NOT Minecraft's Blaze3D backend.
            // BlazeDhRenderApiDefinition.isNativeRenderer() returns false even on Vulkan.
            if(proxy==null || (boolean)api.nativeRenderer.invoke(proxy))return;
            Object state=api.state.get(null);
            DhProjectionMath.jittered(worldProjection,jitterX,jitterY,renderWidth,renderHeight,projection);
            Object oldProjection=api.stateProjection.get(state),oldView=api.stateView.get(state),oldLevel=api.stateLevel.get(state);
            boolean oldFog=api.stateFog.getBoolean(state);
            // Only our scoped call suppresses native DH's copy/fog/TAA; no user setting is modified.
            cleared=false;capturing=true;
            try {
                api.stateProjection.set(state,api.convert.invoke(null,projection));
                api.stateView.set(state,api.convert.invoke(null,view));
                api.stateLevel.set(state,api.levelWrapper.invoke(null,Minecraft.getInstance().level));
                api.stateFog.setBoolean(state,false);
                api.render.invoke(api.client);
                if((boolean)api.deferTransparent.invoke(proxy))api.renderDeferred.invoke(api.client);
            } finally {
                capturing=false;
                api.stateProjection.set(state,oldProjection);api.stateView.set(state,oldView);
                api.stateLevel.set(state,oldLevel);api.stateFog.setBoolean(state,oldFog);
            }
            validation=String.valueOf(api.validation.get(api.client));
            if(attempts==1 || attempts==300)CausticaMod.LOGGER.info("DH geometry bridge: {}",diagnostics());
            if(api.hardwareOnly){DhLodTextures.prepare(ctx);return;} // Shared unlit atlas, never DH prelit scene color.
            if(!cleared) {
                if(++unreadyFrames==300)CausticaMod.LOGGER.warn("DH has not produced a fresh offscreen pass; check DH rendering/LOD readiness and compatibility mixin diagnostics");
                return; // Disabled/not-ready world: never reuse stale LOD textures.
            }
            unreadyFrames=0;
            GpuTextureView color=view(api.color.invoke(proxy)),depth=view(api.depth.invoke(proxy));
            if(!(color instanceof VulkanGpuTextureView cv) || !(depth instanceof VulkanGpuTextureView dv)
                    || cv.isClosed() || dv.isClosed())return;
            if(color.getWidth(0)!=depth.getWidth(0) || color.getHeight(0)!=depth.getHeight(0))return;
            Object params=api.params.get(null);
            Object matrix=api.combined.get(params);
            api.putArray.invoke(matrix,(Object)matrixRows);
            DhProjectionMath.fromRows(matrixRows,vp);
            inverse.set(vp).invert();
            if(!inverse.isFinite())return;
            long c=cv.vkImageView(),d=dv.vkImageView();
            if((lastColor!=0 && lastColor!=c) || (lastDepth!=0 && lastDepth!=d)) {
                RenderSystem.getDevice().createCommandEncoder().submit();ctx.waitIdle();
            }
            int cs=RtEntityTextures.INSTANCE.externalSlot("dh.color",c),ds=RtEntityTextures.INSTANCE.externalSlot("dh.depth",d);
            if(cs==0 || ds==0)throw new IllegalStateException("No bindless slots available for DH");
            String range=((Enum<?>)api.depthRange.invoke(proxy)).name();
            String direction=((Enum<?>)api.depthDirection.invoke(proxy)).name();
            if(!range.equals("ZERO_TO_POS_ONE") && !range.equals("NEG_ONE_TO_POS_ONE"))throw new IllegalStateException("Unknown DH depth range "+range);
            if(!direction.equals("REVERSE_Z") && !direction.equals("FORWARD_Z"))throw new IllegalStateException("Unknown DH depth direction "+direction);
            textures=new Int4(cs,ds,range.equals("NEG_ONE_TO_POS_ONE")?1:0,direction.equals("REVERSE_Z")?1:0);
            lastColor=c;lastDepth=d;
            if(!logged) {logged=true;CausticaMod.LOGGER.info("DH 3.3.3 Vulkan LOD inputs ready: {}x{}, raster-only; no far TLAS",color.getWidth(0),color.getHeight(0));}
        } catch(Throwable t) {
            capturing=false;failed=true;textures=NONE;
            CausticaMod.LOGGER.error("DH optional bridge disabled; Caustica RT remains active",t);
        }
    }
    private static GpuTextureView view(Object result) throws ReflectiveOperationException {
        if(!(boolean)api.success.get(result))return null;
        Object wrapper=api.payload.get(result);
        return wrapper==null?null:(GpuTextureView)api.textureView.invoke(wrapper);
    }
    private static final class Api {
        final Object client;
        final boolean hardwareOnly;
        final Field validation,state,params,renderProxy,stateProjection,stateView,stateLevel,stateFog,combined,success,payload;
        final Method render,renderDeferred,deferTransparent,convert,levelWrapper,nativeRenderer,color,depth,depthRange,depthDirection,putArray,textureView;
        Api() throws ReflectiveOperationException {
            String version=FabricLoader.getInstance().getModContainer("distanthorizons").orElseThrow().getMetadata().getVersion().getFriendlyString();
            if(!version.startsWith("3.2.0") && !version.startsWith("3.3.3"))throw new IllegalStateException("Unsupported DH geometry ABI "+version);
            // Captured geometry is already present in the TLAS. A simultaneous prelit DH raster
            // candidate can win the depth comparison against that water surface, bypassing
            // Caustica Fresnel, refraction, waves and absorption at the near/far boundary.
            hardwareOnly=true;
            Class<?> clientApi=Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi");
            validation=clientApi.getField("lastRenderParamValidationMessage");
            client=clientApi.getField("INSTANCE").get(null);state=clientApi.getField("RENDER_STATE");
            params=clientApi.getDeclaredField("RENDER_PARAMS");params.setAccessible(true);
            render=clientApi.getMethod("renderLods");renderDeferred=clientApi.getMethod("renderDeferredLodsForShaders");
            Class<?> rs=state.getType();stateProjection=rs.getField("mcProjectionMatrix");stateView=rs.getField("mcModelViewMatrix");
            stateLevel=rs.getField("clientLevelWrapper");stateFog=rs.getField("vanillaFogEnabled");
            Class<?> converter=Class.forName("com.seibel.distanthorizons.common.wrappers.McObjectConverter");
            convert=converter.getMethod("convert",Matrix4fc.class);
            levelWrapper=Class.forName("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper")
                    .getMethod("getWrapper",net.minecraft.client.multiplayer.ClientLevel.class);
            renderProxy=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy");
            Class<?> rp=Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy");
            deferTransparent=rp.getMethod("getDeferTransparentRendering");
            nativeRenderer=rp.getMethod("isNativeRenderer");
            if(hardwareOnly) {color=depth=depthRange=depthDirection=putArray=textureView=null; combined=success=payload=null;return;}
            color=rp.getMethod("getDhColorTextureBlazeWrapper");
            depth=rp.getMethod("getDhDepthTextureBlazeWrapper");depthRange=rp.getMethod("getDepthRange");depthDirection=rp.getMethod("getDepthDirection");
            Class<?> result=Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult");success=result.getField("success");payload=result.getField("payload");
            textureView=Class.forName("com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper").getMethod("getTextureView");
            combined=params.getType().getField("dhMvmProjMatrix");putArray=combined.getType().getMethod("putValuesInArray",float[].class);
        }
    }
}
