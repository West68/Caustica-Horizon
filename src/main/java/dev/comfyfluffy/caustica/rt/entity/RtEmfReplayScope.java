package dev.comfyfluffy.caustica.rt.entity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Keep synchronous RT model drawing separate from EMF's still-running submit phase, and make every RT
 * capture evaluate the full animation. EMF's distance LOD re-applies the previous result on skipped frames,
 * so the pose advances every other frame; DLSS-RR and frame generation reproject with per-frame motion
 * vectors (zero, then a double step), which turns that half-rate pose into visible limb twitching. The
 * user's EMF setting is untouched: LOD is suspended only while Caustica replays a model.
 * Render thread only.
 */
final class RtEmfReplayScope {
    private static final VarHandle MAIN, LAYER, ITEM_FRAME;
    private static final java.lang.reflect.Field LOD_DISTANCE;
    private static final Object EMF_CONFIG_HANDLER;
    private static final java.lang.reflect.Method GET_CONFIG;
    private static final java.lang.reflect.Method RESET_LOD_DECISION;
    private record LodScope(Object config,int distance) {}
    private static final java.util.ArrayDeque<LodScope> LOD_SCOPES=new java.util.ArrayDeque<>();
    static {
        VarHandle main=null,layer=null,item=null;
        java.lang.reflect.Field lod=null;Object handler=null;java.lang.reflect.Method getConfig=null,resetLod=null;
        if(FabricLoader.getInstance().isModLoaded("entity_model_features"))try {
            Class<?> state=Class.forName("traben.entity_model_features.models.animation.state.EMFState");
            var lookup=MethodHandles.publicLookup();
            main=lookup.findStaticVarHandle(state,"isMainPhase",boolean.class);
            layer=lookup.findStaticVarHandle(state,"isLayerPhase",boolean.class);
            item=lookup.findStaticVarHandle(state,"isInItemFrame",boolean.class);
            handler=Class.forName("traben.entity_model_features.EMF").getMethod("config").invoke(null);
            getConfig=handler.getClass().getMethod("getConfig");
            lod=Class.forName("traben.entity_model_features.config.EMFConfig").getField("animationLODDistance");
            resetLod=Class.forName("traben.entity_model_features.utils.EMFLODHandler").getMethod("setNullLodFrameSkipping");
        }catch(ReflectiveOperationException | LinkageError e){
            // An optional mod update must not make vanilla model capture fail to initialize.
            main=null;layer=null;item=null;lod=null;handler=null;getConfig=null;resetLod=null;
            org.slf4j.LoggerFactory.getLogger(RtEmfReplayScope.class)
                .warn("EMF replay phase isolation unavailable; retaining the existing replay path",e);
        }
        MAIN=main;LAYER=layer;ITEM_FRAME=item;LOD_DISTANCE=lod;EMF_CONFIG_HANDLER=handler;GET_CONFIG=getConfig;RESET_LOD_DECISION=resetLod;
    }
    private static int captureSavedLod = Integer.MIN_VALUE;
    /**
     * Suspend EMF animation LOD for the whole RT entity capture, not only the model replay. EMF's submit-phase
     * evaluation also frame-skips, and with retainDetailOnLowFps it does so for NEAR mobs whenever the real
     * (pre frame-generation) FPS is below 59 — always true under RT, never without it. Half-rate poses reprojected
     * by RR/FG made mobs bounce. The user's value is restored after the capture.
     */
    static void suspendLodForCapture() {
        if (LOD_DISTANCE == null || captureSavedLod != Integer.MIN_VALUE) return;
        try {
            Object config = GET_CONFIG.invoke(EMF_CONFIG_HANDLER);
            captureSavedLod = LOD_DISTANCE.getInt(config);
            LOD_DISTANCE.setInt(config, 0);
            RESET_LOD_DECISION.invoke(null);
        } catch (ReflectiveOperationException e) { captureSavedLod = Integer.MIN_VALUE; }
    }
    static void restoreLodAfterCapture() {
        if (captureSavedLod == Integer.MIN_VALUE) return;
        try { LOD_DISTANCE.setInt(GET_CONFIG.invoke(EMF_CONFIG_HANDLER), captureSavedLod); }
        catch (ReflectiveOperationException ignored) { }
        captureSavedLod = Integer.MIN_VALUE;
    }
    static int begin() {
        if(MAIN==null || Boolean.getBoolean("caustica.test.legacyEmfReplay"))return -1;
        int saved=((boolean)MAIN.get()?1:0)|((boolean)LAYER.get()?2:0)|((boolean)ITEM_FRAME.get()?4:0);
        // Vanilla prepareModel executes after LivingEntityRenderer.submit has returned.
        // Construct Submit first so DATA_IN still sees the original main/layer metadata.
        MAIN.set(false);
        // animationLODDistance 0 disables EMF's LOD frame skipping; the user's value is restored in end().
        Object lodConfig=null;int savedDistance=Integer.MIN_VALUE;
        try {
            Object config=GET_CONFIG.invoke(EMF_CONFIG_HANDLER);
            savedDistance=LOD_DISTANCE.getInt(config);lodConfig=config;
            LOD_DISTANCE.setInt(config,0);
        } catch(ReflectiveOperationException e) { lodConfig=null;savedDistance=Integer.MIN_VALUE; }
        LOD_SCOPES.push(new LodScope(lodConfig,savedDistance));
        try { RESET_LOD_DECISION.invoke(null); } // drop a skip decision cached by an earlier entity
        catch(ReflectiveOperationException ignored) { }
        return saved;
    }
    static void end(int saved) {
        if(saved<0)return;
        MAIN.set((saved&1)!=0);LAYER.set((saved&2)!=0);ITEM_FRAME.set((saved&4)!=0);
        LodScope lod=LOD_SCOPES.pop();
        if(lod.config()!=null) {
            try { LOD_DISTANCE.setInt(lod.config(),lod.distance()); }
            catch(ReflectiveOperationException ignored) { }
        }
        try { RESET_LOD_DECISION.invoke(null); }
        catch(ReflectiveOperationException ignored) { }
    }
    private RtEmfReplayScope() {}
}
