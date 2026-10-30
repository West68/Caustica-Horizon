package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.DhLodCapture;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Immutable per-section BLAS cache. Builds join the existing frame submission, never submit/wait alone. */
public final class RtDhLods {
    public static final RtDhLods INSTANCE=new RtDhLods();
    public static final int MAX_SECTIONS=1024;
    private static final long BUDGET=768L*1024*1024;
    // DH keeps generating distant sections for minutes, so a fixed 768 MiB slowly filled an 8 GB card past
    // its budget (DLSS-G eWarnOutOfVRAM, driver paging, FPS sinking over time). Track real headroom instead.
    private static final long MIN_BUDGET=512L*1024*1024, VRAM_RESERVE=768L*1024*1024;
    private long budget=BUDGET, nextBudgetFrame;
    private static final int KEEP=6;
    public static final class Entry {
        public final DhLodCapture.SectionSnapshot snapshot;
        public final RtBuffer geometry;
        public final RtAccel.PersistentBuild build;
        public boolean pending=true;
        public final int resolution;
        Entry(DhLodCapture.SectionSnapshot s,RtBuffer g,RtAccel.PersistentBuild b,int resolution){snapshot=s;geometry=g;build=b;this.resolution=resolution;}
    }
    private record Retired(long frame,Runnable release) {}
    private final Map<Long,Entry> cache=new HashMap<>();
    private final Map<Long,Long> retryAfter=new HashMap<>();
    private record Request(long version,int resolution) {}
    private final Map<Long,Request> retried=new HashMap<>();
    private final List<Retired> retired=new ArrayList<>();
    private final ArrayList<Entry> selected=new ArrayList<>();
    private Object world;
    private RtBuffer counters;
    public long counterAddress(RtContext ctx) {
        if(!Boolean.getBoolean("caustica.test.dhHitMarker"))return 0;
        if(counters==null){counters=ctx.createBuffer(16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true,"DH test hit counters");MemoryUtil.memSet(counters.mapped,0,16);counters.flush(0,16);}
        return counters.deviceAddress;
    }
    public String readTestCounters() {
        if(counters==null)return "unallocated";
        var ctx=RtContext.currentOrNull();com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();ctx.waitIdle();
        org.lwjgl.util.vma.Vma.vmaInvalidateAllocation(ctx.vma(),counters.allocation,0,16);
        return "totalHits="+Integer.toUnsignedLong(MemoryUtil.memGetInt(counters.mapped))+" primaryHits="+Integer.toUnsignedLong(MemoryUtil.memGetInt(counters.mapped+4))+" waterHits="+Integer.toUnsignedLong(MemoryUtil.memGetInt(counters.mapped+8))+" shadowHits="+Integer.toUnsignedLong(MemoryUtil.memGetInt(counters.mapped+12));
    }
    private long bytes,builds,evictions,meshAttempts,budgetRejects;
    private int instances,buildCursor,wantedSections;
    private final DhLodOrderCache selectionOrder=new DhLodOrderCache();
    private long lastCoverageKey=Long.MIN_VALUE;
    private final DhTerrainCoverage coverage=new DhTerrainCoverage();
    public DhTerrainCoverage coverage(){return coverage;}
    public List<Entry> prepare(RtContext ctx,double x,double z) {
        long frame=RtComposite.frameCounter();
        for(var it=retired.iterator();it.hasNext();) {var r=it.next();if(frame>=r.frame){r.release.run();it.remove();}}
        Object level=Minecraft.getInstance().level;
        if(world!=level) {for(var e:cache.values())retire(e,frame);cache.clear();retryAfter.clear();retried.clear();buildCursor=0;world=level;selectionOrder.clear();lastCoverageKey=Long.MIN_VALUE;}
        selected.clear();instances=0;
        if(level==null||!CausticaConfig.Rt.Visuals.DH_LODS.value())return selected;
        refreshBudget(ctx,frame);
        // Over budget: give back the farthest sections first (a few per frame, never a stall).
        for(int evicted=0;bytes>budget+(64L<<20) && !cache.isEmpty() && evicted<8;evicted++) {
            Map.Entry<Long,Entry> far=null;double farDistance=-1;
            for(var c:cache.entrySet()){double d=distance(c.getValue().snapshot,x,z);if(d>farDistance){farDistance=d;far=c;}}
            retire(far.getValue(),frame);cache.remove(far.getKey());retryAfter.put(far.getKey(),frame+600);
        }
        var snapshots=DhLodCapture.activeSections(level);
        var mc=Minecraft.getInstance();int radius=Math.max(1,mc.options.getEffectiveRenderDistance());
        int minY=Math.floorDiv(mc.level.getMinY(),16),height=(mc.level.getHeight()+15)/16;
        var nearTerrain=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
        // Rebuilding the near-terrain coverage walks every ready section (tens of thousands); do it only when
        // that set, the player's chunk or the window changed.
        int coverX=(mc.player.getBlockX()>>4)-radius,coverZ=(mc.player.getBlockZ()>>4)-radius;
        long coverageKey=(((((nearTerrain==null?-1L:nearTerrain.readyRevision())*31L+System.identityHashCode(nearTerrain))*31L+coverX)*31L+coverZ)*31L+radius)*31L+minY*131L+height;
        if(coverageKey!=lastCoverageKey) {
            coverage.reset(coverX,minY,coverZ,radius*2+1,height,radius*2+1);
            if(nearTerrain!=null)nearTerrain.visitReadySections(coverage::add);
            lastCoverageKey=coverageKey;
        }
        snapshots.removeIf(s->coverage.covers(s.minBlockX(),s.minBlockZ(),1<<s.detailLevel()));
        // Ordering only decides build priority and which sections survive the MAX_SECTIONS cap. When the
        // section set, its versions and the player's chunk are unchanged the previous order is still valid,
        // so skip the O(n log n) sort that otherwise runs on every frame, including frames with no rebuild.
        long selectionSignature=0xcbf29ce484222325L;
        for(var s:snapshots)selectionSignature=(selectionSignature^s.pos())*0x100000001b3L^(s.version()*31L);
        selectionSignature=selectionSignature*31L+(mc.player.getBlockX()>>4);
        selectionSignature=selectionSignature*31L+(mc.player.getBlockZ()>>4);
        selectionOrder.apply(snapshots,selectionSignature,x,z);
        if(snapshots.size()>MAX_SECTIONS)snapshots.subList(MAX_SECTIONS,snapshots.size()).clear();
        wantedSections=snapshots.size();
        Set<Long> wanted=new HashSet<>();for(var s:snapshots)wanted.add(s.pos());
        // Retire obsolete parents only after their requested children can enter the TLAS.
        retryAfter.keySet().retainAll(wanted);
        retried.keySet().retainAll(wanted);
        // DH owns its quality/quadtree. Keep every requested rectangle unchanged.
        // Nearest missing/revised leaves first; complete old parents cover staged children.
        var work=snapshots;
        int attempts=0,quads=0;
        for(var s:work) {
            Entry e=cache.get(s.pos());
            if(s.quads().isEmpty())continue;
            int wantedResolution=64;
            Request oldRequest=retried.get(s.pos());
            if(oldRequest==null || oldRequest.version()!=s.version() || oldRequest.resolution()!=wantedResolution)retryAfter.remove(s.pos());
            if((e==null || e.snapshot.version()!=s.version() || e.resolution!=wantedResolution) && attempts<4 && quads<65536 && frame>=retryAfter.getOrDefault(s.pos(),0L)) {
                attempts++;meshAttempts++;retried.put(s.pos(),new Request(s.version(),wantedResolution));
                int meshResolution=64;var mesh=s;
                int count=mesh.quads().size();long required=DhPackedQuads.bytes(count);
                long buildBytes=RtAccel.dhBuildBytes(ctx,count);
                // Admission accounts actual driver sizes including in-flight scratch, never a lossy proxy.
                if(bytes+required+buildBytes>budget){budgetRejects++;retryAfter.put(s.pos(),frame+30);continue;}
                RtBuffer g=ctx.createBuffer(required,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,true,"DH section geometry");
                RtAccel.PersistentBuild pb=null;
                try {
                    DhPackedQuads.write(MemoryUtil.memByteBuffer(g.mapped,Math.toIntExact(required)),mesh);g.flush(0,required);
                    int n=mesh.quads().size();
                    pb=RtAccel.prepareDhBlasBuild(ctx,g.deviceAddress,n,"DH rectangle BLAS");
                    long actual=g.size+pb.backing().size+pb.scratch().size;
                    if(bytes+actual>budget){RtAccel.destroyEntityAccel(pb.accel(),pb.backing());pb.scratch().destroy();g.destroy();budgetRejects++;retryAfter.put(s.pos(),frame+120);continue;}
                    if(e!=null)retire(e,frame);
                    bytes+=actual;e=new Entry(mesh,g,pb,meshResolution);cache.put(s.pos(),e);builds++;
                    if(meshResolution<wantedResolution)retryAfter.put(s.pos(),frame+120);else retryAfter.remove(s.pos());quads+=n;
                    RtBuffer scratch=pb.scratch();
                    retired.add(new Retired(frame+KEEP,()->{scratch.destroy();bytes-=scratch.size;}));
                } catch(Throwable failure) {if(pb!=null){RtAccel.destroyEntityAccel(pb.accel(),pb.backing());pb.scratch().destroy();}g.destroy();throw failure;}
            }
        }
        var resident=new HashMap<Long,DhLodCapture.SectionSnapshot>();
        cache.forEach((key,e)->resident.put(key,e.snapshot));
        // Zero-quad results supersede old geometry without issuing an invalid zero-size build.
        for(var s:snapshots)if(s.quads().isEmpty())resident.put(s.pos(),s);
        var publication=DhLodSelection.handoff(snapshots,resident,coverage::covers);
        Set<Long> keep=new HashSet<>(wanted);
        for(var s:publication)if(!s.quads().isEmpty() && selected.size()<MAX_SECTIONS){selected.add(cache.get(s.pos()));keep.add(s.pos());}
        for(var s:snapshots)if(s.quads().isEmpty())keep.remove(s.pos());
        for(var it=cache.entrySet().iterator();it.hasNext();) {var e=it.next();if(!keep.contains(e.getKey())){retire(e.getValue(),frame);it.remove();}}
        instances=selected.size();return selected;
    }
    private void refreshBudget(RtContext ctx,long frame) {
        if(frame<nextBudgetFrame)return;
        nextBudgetFrame=frame+60;
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var pp=stack.mallocPointer(1);
            org.lwjgl.util.vma.Vma.vmaGetMemoryProperties(ctx.vma(),pp);
            var memory=org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.create(pp.get(0));
            var heaps=org.lwjgl.util.vma.VmaBudget.calloc(VK_MAX_MEMORY_HEAPS,stack);
            org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(ctx.vma(),heaps);
            long free=Long.MAX_VALUE;
            for(int i=0;i<memory.memoryHeapCount();i++)
                if((memory.memoryHeaps(i).flags()&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)!=0)
                    free=Math.min(free,heaps.get(i).budget()-heaps.get(i).usage());
            if(free==Long.MAX_VALUE)return;
            long target=Math.max(MIN_BUDGET,Math.min(BUDGET,bytes+free-VRAM_RESERVE));
            // Shrink at once under pressure, grow slowly: a budget that follows every transient allocation
            // built sections only to evict them a second later (thousands of wasted BLAS builds).
            budget=target<budget?target:Math.min(target,budget+(32L<<20));
        }
    }
    private static double distance(DhLodCapture.SectionSnapshot s,double x,double z) {
        return DhLodSelection.distance(s,x,z);
    }
    /** Include staged children even while a parent still supplies their visible coverage. */
    public void recordPending(java.util.function.Consumer<RtAccel.PreparedBlas> sink) {
        for(var e:cache.values())if(e.pending){sink.accept(e.build.op());e.pending=false;}
    }
    private void retire(Entry e,long frame) {
        evictions++;
        retired.add(new Retired(frame+KEEP,()->{RtAccel.destroyEntityAccel(e.build.accel(),e.build.backing());e.geometry.destroy();bytes-=e.geometry.size+e.build.backing().size;}));
    }
    /** Caller has quiesced the context, matching all other entity cache shutdowns. */
    public void shutdown() {
        for(var r:retired)r.release.run();retired.clear();
        for(var e:cache.values()){RtAccel.destroyEntityAccel(e.build.accel(),e.build.backing());e.geometry.destroy();}
        if(counters!=null){counters.destroy();counters=null;}
        DhLodMaterials.INSTANCE.shutdown();
        cache.clear();retryAfter.clear();retried.clear();selected.clear();world=null;selectionOrder.clear();bytes=0;instances=0;buildCursor=0;wantedSections=0;
    }
    /** Menu ticks release LOD resources once the existing graphics timeline proves completion, without waiting. */
    public void releaseIfWorldClosed(RtContext ctx) {
        if(world==null || Minecraft.getInstance().level!=null || Minecraft.getInstance().getConnection()!=null)return;
        var executor=ctx.gpuExecutor();
        if(executor.completedGraphicsValue()<executor.latestGraphicsUseValue())return;
        shutdown();DhLodCapture.clear();
    }
    public String stats(){return "rawInstances="+selected.stream().filter(e->e.resolution==64).count()+" instances="+instances+" wanted="+wantedSections+" resident="+cache.size()+" bytes="+bytes+" builds="+builds+" evictions="+evictions+" meshAttempts="+meshAttempts+" budgetRejects="+budgetRejects+" budget="+budget;}
    private RtDhLods() {}
}
