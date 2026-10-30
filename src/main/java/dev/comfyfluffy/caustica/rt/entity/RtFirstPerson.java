package dev.comfyfluffy.caustica.rt.entity;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.slf4j.LoggerFactory;
/** Captures final animated hand/item submissions once, including EMF player extensions. */
public final class RtFirstPerson {
    private static final org.slf4j.Logger LOGGER=LoggerFactory.getLogger("Caustica");
    private static final boolean INSTALLED=FabricLoader.getInstance().isModLoaded("punchy")
        || FabricLoader.getInstance().isModLoaded("entity_model_features");
    /**
     * Opt-in diagnosis of the whole first-person chain (-Dcaustica.enhanced.punchyProbe=true). When off every
     * counter below is skipped entirely, so normal play pays nothing for it.
     */
    private static final boolean PROBE=Boolean.getBoolean("caustica.enhanced.punchyProbe");
    private static boolean collecting;
    private static final org.joml.Matrix4f handProjection = new org.joml.Matrix4f();
    private static boolean projectionCaptured;
    public static void captureProjection(net.minecraft.client.renderer.Projection projection) {
        if (INSTALLED) { projection.getMatrix(handProjection); projectionCaptured = true; }
    }
    static org.joml.Matrix4fc projection() { return projectionCaptured ? handProjection : null; }
    public static long capturedFrames;
    public static int triangles;
    private static long probeFrames,probeEnabledFrames,probeCollectingFrames,probeGeometryFrames,probeTris,probeLastLog;
    private static final class State {
        static final RtEntityCapture CAPTURE=new RtEntityCapture();
        static final RtEntityCollector COLLECTOR=new RtEntityCollector();
    }
    public static boolean enabled(){return INSTALLED && !Boolean.getBoolean("caustica.enhanced.disablePunchyCapture")
        && RtEntities.enabled() && VanillaRenderController.INSTANCE.shouldCaptureRtFirstPerson()
        && dev.comfyfluffy.caustica.client.BetterPlayerModelCompat.realFirstPerson();}
    /** True while the opt-in first-person probe is active; gates all diagnostic counters on the hot paths. */
    public static boolean probe(){return PROBE;}
    public static void beginFrame(){
        if(!INSTALLED)return;
        State.CAPTURE.reset();collecting=false;triangles=0;projectionCaptured=false;
        if(PROBE){probeFrames++;if(enabled())probeEnabledFrames++;}
    }
    public static void begin(){collecting=true;State.COLLECTOR.begin(State.CAPTURE,false);State.COLLECTOR.probeHand(PROBE);if(PROBE)probeCollectingFrames++;}
    public static SubmitNodeCollector collector(SubmitNodeCollector fallback){return collecting?State.COLLECTOR:fallback;}
    public static boolean collecting(){return collecting;}
    static RtEntityCapture capture(){return INSTALLED?State.CAPTURE:null;}
    public static void end(){
        State.COLLECTOR.probeHand(false);
        State.COLLECTOR.begin(null,false);
        collecting=false;
        triangles=State.CAPTURE.idx.size()/3;
        if(triangles>0)capturedFrames++;
        if(PROBE)probeFrame();
    }
    /**
     * One aggregated line per second: it separates "the hook never ran" (enabled/collecting stay 0) from
     * "the hook ran but the collector never received anything" (collector counters stay 0) from "geometry
     * was captured but the RT scene dropped it" (tris &gt; 0 while nothing appears on screen).
     */
    private static void probeFrame(){
        if(triangles>0){probeGeometryFrames++;probeTris+=triangles;}
        long now=System.nanoTime();
        if(probeLastLog==0L)probeLastLog=now;
        else if(now-probeLastLog<1_000_000_000L)return;
        probeLastLog=now;
        LOGGER.info("[punchyProbe] frames={} enabled={} collecting={} withGeometry={} tris={} lastFrameTris={} || collector model={} cubes={} kinds={} item={} custom={} block={} outline={} ordered={}",
            probeFrames,probeEnabledFrames,probeCollectingFrames,probeGeometryFrames,probeTris,triangles,
            RtEntityCollector.probeModel,RtEntityCollector.probeModelCubes,RtEntityCollector.probeModelKinds,RtEntityCollector.probeItem,RtEntityCollector.probeCustomGeometry,
            RtEntityCollector.probeBlockModel,RtEntityCollector.probeShapeOutline,RtEntityCollector.probeOrdered);
        LOGGER.info("[punchyProbe:hand] attempts={} disabled={} null={} empty={} buildFull={} keyCollide={} capturedTris={} uploadedByReuse={} uploadedByAppend={}",
            RtEntities.probeHandAttempts,RtEntities.probeHandDisabled,RtEntities.probeHandNull,RtEntities.probeHandEmpty,RtEntities.probeHandBuildFull,RtEntities.probeHandKeyCollide,RtEntities.probeHandTris,RtEntities.probeHandReused,RtEntities.probeHandFallback);
        long timedFrames=RtEntities.probeHandTimedFrames;
        LOGGER.info("[punchyProbe:cost] handCaptureAndUpload avg={}us over {} frames",
            timedFrames==0?0:RtEntities.probeHandNanos/timedFrames/1000L,timedFrames);
        var player=Minecraft.getInstance().player;
        LOGGER.info("[punchyProbe:state] held={} offhand={} using={} swinging={} firstPerson={}",
            player==null?"none":player.getMainHandItem().getItem().toString(),
            player==null?"none":player.getOffhandItem().getItem().toString(),
            player!=null&&player.isUsingItem(),player!=null&&player.swinging,
            Minecraft.getInstance().options.getCameraType().isFirstPerson());
        RtEntityCollector.resetProbeCounters();
        RtEntities.resetHandProbeCounters();
    }
    public static void onReload(){if(INSTALLED){State.COLLECTOR.clearCaches();State.CAPTURE.reset();}}
    private RtFirstPerson(){}
}
