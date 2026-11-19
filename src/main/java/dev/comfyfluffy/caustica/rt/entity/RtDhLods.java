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
    private long budget=BUDGET, nextBudgetFrame, growHoldUntil, fgSince=-1;
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
    // Sections dropped for budget only come back into free headroom; they may not evict others to return.
    // Timed retries let them push out the next-farthest ring, which then returned the same way (LOD flicker).
    // Value: squared distance at eviction; once the player has come much closer the section may push again.
    private final Map<Long,Double> budgetEvicted=new HashMap<>();
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
    // Diagnostics only (no behaviour change): why sections leave or never get built.
    private long evictBudget,evictUnwanted,replaced,budgetBelowBytes;
    private long lastStatsLog;
    private int instances,buildCursor,wantedSections;
    private final DhLodOrderCache selectionOrder=new DhLodOrderCache();
    private long lastCoverageKey=Long.MIN_VALUE;
    private final DhTerrainCoverage coverage=new DhTerrainCoverage();
    public DhTerrainCoverage coverage(){return coverage;}
    public List<Entry> prepare(RtContext ctx,double x,double z) {
        long t0=System.nanoTime();
        try{return prepare0(ctx,x,z);}finally{long d=System.nanoTime()-t0;prepSum+=d;if(d>prepMax)prepMax=d;}
    }
    private long prepSum,prepMax;
    private List<Entry> prepare0(RtContext ctx,double x,double z) {
        long frame=RtComposite.frameCounter();
        {long now=System.nanoTime();if(lastFrameNs!=0){long d=now-lastFrameNs;perfFrames++;perfSum+=d;if(d>perfMax)perfMax=d;if(d>50_000_000L)perfHitch++;}lastFrameNs=now;}
        for(var it=retired.iterator();it.hasNext();) {var r=it.next();if(frame>=r.frame){r.release.run();it.remove();}}
        Object level=Minecraft.getInstance().level;
        if(world!=level) {for(var e:cache.values())retire(e,frame);cache.clear();retryAfter.clear();budgetEvicted.clear();retried.clear();buildCursor=0;world=level;selectionOrder.clear();lastCoverageKey=Long.MIN_VALUE;}
        selected.clear();instances=0;
        if(level==null||!CausticaConfig.Rt.Visuals.DH_LODS.value())return selected;
        refreshBudget(ctx,frame);
        // Over budget: give back the farthest sections first (a few per frame, never a stall).
        for(int evicted=0;bytes>budget+(64L<<20) && !cache.isEmpty() && evicted<8;evicted++) {
            Map.Entry<Long,Entry> far=null;double farDistance=-1;
            for(var c:cache.entrySet()){double d=distance(c.getValue().snapshot,x,z);if(d>farDistance){farDistance=d;far=c;}}
            retire(far.getValue(),frame);cache.remove(far.getKey());retryAfter.put(far.getKey(),frame+600);budgetEvicted.put(far.getKey(),far.getValue()==null?0.0:distance(far.getValue().snapshot,x,z));evictBudget++;
        }
        if(bytes>budget)budgetBelowBytes++;
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
        if(System.nanoTime()-lastStatsLog>5_000_000_000L){lastStatsLog=System.nanoTime();
            dev.comfyfluffy.caustica.CausticaMod.LOGGER.info("[DH-RT] {}",stats());
            logPerf(ctx);}
        Set<Long> wanted=new HashSet<>();for(var s:snapshots)wanted.add(s.pos());
        // Retire obsolete parents only after their requested children can enter the TLAS.
        retryAfter.keySet().retainAll(wanted);
        budgetEvicted.keySet().retainAll(wanted);
        retried.keySet().retainAll(wanted);
        // DH owns its quality/quadtree. Keep every requested rectangle unchanged.
        // Nearest missing/revised leaves first; complete old parents cover staged children.
        var work=snapshots;
        // Stable admission: walk sections nearest-first and keep the prefix that fits the budget. Resident
        // sections keep their place up to the full budget, new ones must fit a lower bar (hysteresis), so a
        // standing player's set never changes. Log 2026-11-16: nearer sections evicting farther ones and the
        // freed scratch letting those return made ~20 rebuilds/s at a full budget, seen as LOD flicker.
        Set<Long> allowed=new HashSet<>();
        {long cum=0;boolean open=true;long soft=budget-(96L<<20);
         for(var s:snapshots){if(s.quads().isEmpty())continue;Entry r=cache.get(s.pos());
             long est=(long)(DhPackedQuads.bytes(s.quads().size())*costRatio);
             if(r!=null){long cost=Math.max(r.geometry.size+r.build.backing().size,r.snapshot.version()==s.version()?0:est);
                 if(cum+cost<=budget){cum+=cost;allowed.add(s.pos());}}
             else if(open && cum+est<=soft){cum+=est;allowed.add(s.pos());}
             else open=false;}}
        int attempts=0,quads=0;
        for(var s:work) {
            Entry e=cache.get(s.pos());
            if(s.quads().isEmpty())continue;
            int wantedResolution=64;
            Request oldRequest=retried.get(s.pos());
            if(oldRequest==null || oldRequest.version()!=s.version() || oldRequest.resolution()!=wantedResolution)retryAfter.remove(s.pos());
            if((e==null || e.snapshot.version()!=s.version() || e.resolution!=wantedResolution) && allowed.contains(s.pos()) && attempts<4 && quads<65536 && frame>=retryAfter.getOrDefault(s.pos(),0L)) {
                attempts++;meshAttempts++;retried.put(s.pos(),new Request(s.version(),wantedResolution));
                int meshResolution=64;var mesh=s;
                int count=mesh.quads().size();long required=DhPackedQuads.bytes(count);
                long buildBytes=RtAccel.dhBuildBytes(ctx,count);
                // Admission accounts actual driver sizes including in-flight scratch, never a lossy proxy.
                if(bytes+required+buildBytes>budget){
                    // Nearer sections outrank farther ones: a full budget must never leave the ground the player
                    // just walked off without LODs (seams that only a world reload cleared). Free farther
                    // sections; their memory returns after KEEP frames, then this section builds.
                    evictOutside(allowed,s,required+buildBytes,x,z,frame);
                    budgetRejects++;retryAfter.put(s.pos(),frame+KEEP+1);continue;
                }
                RtBuffer g=ctx.createBuffer(required,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,true,"DH section geometry");
                RtAccel.PersistentBuild pb=null;
                try {
                    DhPackedQuads.write(MemoryUtil.memByteBuffer(g.mapped,Math.toIntExact(required)),mesh);g.flush(0,required);
                    int n=mesh.quads().size();
                    pb=RtAccel.prepareDhBlasBuild(ctx,g.deviceAddress,n,"DH rectangle BLAS");
                    long actual=g.size+pb.backing().size+pb.scratch().size;
                    if(bytes+actual>budget){RtAccel.destroyEntityAccel(pb.accel(),pb.backing());pb.scratch().destroy();g.destroy();budgetRejects++;retryAfter.put(s.pos(),frame+120);continue;}
                    if(e!=null){retire(e,frame);replaced++;}
                    costRatio=costRatio*0.9+0.1*((double)(g.size+pb.backing().size)/Math.max(1L,required));
                    bytes+=actual;e=new Entry(mesh,g,pb,meshResolution);cache.put(s.pos(),e);builds++;
                    budgetEvicted.remove(s.pos());if(meshResolution<wantedResolution)retryAfter.put(s.pos(),frame+120);else retryAfter.remove(s.pos());quads+=n;
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
        for(var it=cache.entrySet().iterator();it.hasNext();) {var e=it.next();if(!keep.contains(e.getKey())){retire(e.getValue(),frame);it.remove();evictUnwanted++;}}
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
            // Frame generation allocates its own history after we size this budget; leave it room or DLSS-G
            // reports over-budget and the driver starts paging (FPS drop) on 8 GB cards.
            // The extra frame-generation reserve only matters until DLSS-G has allocated its history. Once it runs,
            // that memory is already in the heap usage above, so keeping the reserve counted it twice and pinned the
            // LOD budget at its 256 MB floor (log 2026-11-14: ~240 of ~410 wanted sections fit; the rest swapped).
            boolean fgPending=CausticaConfig.Rt.Fg.ENABLED.value() && (fgSince<0 || frame-fgSince<1200);
            if(CausticaConfig.Rt.Fg.ENABLED.value()){if(fgSince<0)fgSince=frame;}else fgSince=-1;
            long reserve=VRAM_RESERVE+(fgPending?(512L<<20):0L);
            long target=Math.max(MIN_BUDGET-(CausticaConfig.Rt.Fg.ENABLED.value()?(256L<<20):0L),Math.min(BUDGET,bytes+free-reserve));
            // Shrink at once under pressure, grow slowly: a budget that follows every transient allocation
            // built sections only to evict them a second later (thousands of wasted BLAS builds).
            // Small dips (<32 MB) are allocation jitter: following them evicted sections every refresh.
            // Log 2026-11-14: the budget sawed 256->288->320->256 MB every ~15 s. Each step up was filled with
            // builds, whose transient driver memory then pushed the target back down and evicted ~100 sections
            // (the flicker). After any shrink hold the budget for ~30 s, and grow only into clear headroom.
            if(target<budget-(32L<<20)){budget=target;growHoldUntil=frame+1800;}
            else if(frame>=growHoldUntil && target>budget+(64L<<20))budget=Math.min(target-(32L<<20),budget+(32L<<20));
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
        long size=e.geometry.size+e.build.backing().size;releasing+=size;
        retired.add(new Retired(frame+KEEP,()->{RtAccel.destroyEntityAccel(e.build.accel(),e.build.backing());e.geometry.destroy();bytes-=size;releasing-=size;}));
    }
    /** Bytes already retired but not yet returned (they come back KEEP frames after retirement). */
    private long releasing;
    private double costRatio=3.0;
    /** Free room for an admitted section by evicting only residents outside the admitted set, farthest first. */
    private void evictOutside(Set<Long> allowed,DhLodCapture.SectionSnapshot s,long need,double x,double z,long frame) {
        for(int evicted=0;bytes-releasing+need>budget && evicted<16;evicted++) {
            Map.Entry<Long,Entry> far=null;double farDistance=-1;
            for(var c:cache.entrySet()){if(c.getKey()==s.pos()||allowed.contains(c.getKey()))continue;double d=distance(c.getValue().snapshot,x,z);if(d>farDistance){farDistance=d;far=c;}}
            if(far==null)return;
            retire(far.getValue(),frame);cache.remove(far.getKey());evictBudget++;
        }
    }
    /** Evict the farthest resident sections that lie farther than {@code s} until {@code need} fits. */
    private void makeRoomFor(DhLodCapture.SectionSnapshot s,long need,double x,double z,long frame) {
        // Hysteresis: only sections clearly farther (1.5x, squared distances) give way, so the budget frontier
        // does not trade sections back and forth every time the player moves (LOD flicker).
        double near=distance(s,x,z)*2.25+256.0;
        for(int evicted=0;bytes-releasing+need>budget && evicted<16;evicted++) {
            Map.Entry<Long,Entry> far=null;double farDistance=near;
            for(var c:cache.entrySet()){if(c.getKey()==s.pos())continue;double d=distance(c.getValue().snapshot,x,z);if(d>farDistance){farDistance=d;far=c;}}
            if(far==null)return;
            retire(far.getValue(),frame);cache.remove(far.getKey());retryAfter.put(far.getKey(),frame+600);budgetEvicted.put(far.getKey(),far.getValue()==null?0.0:distance(far.getValue().snapshot,x,z));evictBudget++;
        }
    }
    /** Caller has quiesced the context, matching all other entity cache shutdowns. */
    public void shutdown() {
        for(var r:retired)r.release.run();retired.clear();
        for(var e:cache.values()){RtAccel.destroyEntityAccel(e.build.accel(),e.build.backing());e.geometry.destroy();}
        if(counters!=null){counters.destroy();counters=null;}
        DhLodMaterials.INSTANCE.shutdown();
        cache.clear();retryAfter.clear();budgetEvicted.clear();retried.clear();selected.clear();world=null;selectionOrder.clear();bytes=0;instances=0;buildCursor=0;wantedSections=0;
    }
    /** Menu ticks release LOD resources once the existing graphics timeline proves completion, without waiting. */
    public void releaseIfWorldClosed(RtContext ctx) {
        if(world==null || Minecraft.getInstance().level!=null || Minecraft.getInstance().getConnection()!=null)return;
        var executor=ctx.gpuExecutor();
        if(executor.completedGraphicsValue()<executor.latestGraphicsUseValue())return;
        shutdown();DhLodCapture.clear();
    }
    public String stats(){return "rawInstances="+selected.stream().filter(e->e.resolution==64).count()+" instances="+instances+" wanted="+wantedSections+" resident="+cache.size()+" bytes="+bytes+" builds="+builds+" evictions="+evictions+" meshAttempts="+meshAttempts+" budgetRejects="+budgetRejects+" budget="+budget
        +" evictBudget="+evictBudget+" evictUnwanted="+evictUnwanted+" replaced="+replaced+" framesOverBudget="+budgetBelowBytes;}
    private long lastFrameNs,perfFrames,perfSum,perfMax,perfHitch;
    /** Diagnostics: frame pacing, VRAM heap, terrain size, server tick and Java heap every 5 s. */
    private jdk.jfr.Recording jfr;private long jfrLastDump;
    /** Diagnostics: continuous JFR sampling (last 15 min), dumped to <game>/perf-jfr/rec.jfr every 60 s. */
    private void jfrTick() {
        try {
            if(jfr==null){jfr=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));jfr.setMaxAge(java.time.Duration.ofMinutes(15));jfr.setMaxSize(400L<<20);jfr.setToDisk(true);jfr.setName("caustica-perf");jfr.start();jfrLastDump=System.nanoTime();
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.info("[PERF] JFR recording started");return;}
            if(System.nanoTime()-jfrLastDump>60_000_000_000L){jfrLastDump=System.nanoTime();
                var dir=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("perf-jfr");java.nio.file.Files.createDirectories(dir);
                jfr.dump(dir.resolve("rec.jfr"));}
        } catch(Throwable t){dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn("[PERF] JFR failed: {}",t.toString());jfr=null;jfrLastDump=Long.MAX_VALUE/2;}
    }
    private void logPerf(RtContext ctx) {
        if(Boolean.getBoolean("caustica.diag.jfr") && jfrLastDump<Long.MAX_VALUE/4)jfrTick();
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var pp=stack.mallocPointer(1);
            org.lwjgl.util.vma.Vma.vmaGetMemoryProperties(ctx.vma(),pp);
            var memory=org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.create(pp.get(0));
            var heaps=org.lwjgl.util.vma.VmaBudget.calloc(VK_MAX_MEMORY_HEAPS,stack);
            org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(ctx.vma(),heaps);
            var sb=new StringBuilder("[PERF] ");
            if(perfFrames>0)sb.append("fps=").append(String.format("%.1f",perfFrames*1e9/perfSum)).append(" maxFrameMs=").append(perfMax/1_000_000).append(" hitches50ms=").append(perfHitch);
            for(int i=0;i<memory.memoryHeapCount();i++){var h=heaps.get(i);
                sb.append(" heap").append(i).append((memory.memoryHeaps(i).flags()&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)!=0?"(vram)":"(sys)")
                  .append("=").append(h.usage()>>20).append("/").append(h.budget()>>20).append("MB rt=").append(h.statistics().blockBytes()>>20).append("/alloc=").append(h.statistics().allocationBytes()>>20);}
            sb.append(' ').append(dev.comfyfluffy.caustica.rt.terrain.RtTerrain.memoryStats());
            var rt=Runtime.getRuntime();sb.append(" javaHeapMB=").append((rt.totalMemory()-rt.freeMemory())>>20).append("/").append(rt.maxMemory()>>20);
            var server=Minecraft.getInstance().getSingleplayerServer();
            if(server!=null)sb.append(" serverMspt=").append(String.format("%.1f",server.getAverageTickTimeNanos()/1e6));
            if(perfFrames>0)sb.append(" dhPrepAvgMs=").append(String.format("%.2f",prepSum/1e6/perfFrames)).append(" dhPrepMaxMs=").append(prepMax/1_000_000);
            sb.append(" dhBuilds=").append(builds).append(" ").append(dev.comfyfluffy.caustica.rt.EnhancedGpuTimings.summary());
            var p=Minecraft.getInstance().player;if(p!=null)sb.append(" x=").append(p.getBlockX()).append(" z=").append(p.getBlockZ());
            dev.comfyfluffy.caustica.CausticaMod.LOGGER.info(sb.toString());
        } catch(Throwable t){dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn("[PERF] stats failed: {}",t.toString());}
        perfFrames=perfSum=perfMax=perfHitch=0;prepSum=prepMax=0;
    }
    private RtDhLods() {}
}
