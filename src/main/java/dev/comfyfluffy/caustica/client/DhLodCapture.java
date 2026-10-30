package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU snapshot of Distant Horizons LOD quads, taken before they are packed into GPU vertex buffers.
 *
 * <p>DH is not a compile dependency of Caustica (the existing DH mixins are {@code @Pseudo} string
 * targets), so the quad fields and the section-position helpers are read through cached reflection. That
 * happens only when DH rebuilds a section, never per frame.
 *
 * <p>Coordinates come from DH's own {@code DhSectionPos} helpers ({@code getMinCornerBlockX/Z},
 * {@code getDetailLevel}); {@code pos} is never hand-decoded and no offset is added on top, so the section
 * origin cannot be double-applied.
 *
 * <p>Water is classified through DH's own {@code EDhApiBlockMaterial.WATER} entry, not by assuming that any
 * transparent quad is water.
 */
public final class DhLodCapture {

    /** One DH quad in section-local coordinates; two triangles. */
    public record LodQuad(short x, short y, short z, short widthEastWest, short widthNorthSouthOrHeight,
            int color, short textureTileId, byte materialId, byte skyLight, byte blockLight,
            int direction, boolean transparent, boolean water, boolean vegetation) {
        public LodQuad(short x,short y,short z,short widthEastWest,short widthNorthSouthOrHeight,int color,
                short textureTileId,byte materialId,byte skyLight,byte blockLight,int direction,boolean transparent,boolean water) {
            this(x,y,z,widthEastWest,widthNorthSouthOrHeight,color,textureTileId,materialId,skyLight,blockLight,direction,transparent,water,false);
        }
    }

    /** Immutable per-section geometry snapshot. */
    public record SectionSnapshot(long pos, int detailLevel, int minBlockX, int minBlockZ,
            List<LodQuad> quads, int waterQuads, long version, int minY) {
        public int triangles() {
            return quads.size() * 2;
        }
    }

    private static final DhSnapshotStore<SectionSnapshot> STORE=new DhSnapshotStore<>();
    private static final DhWorldNamespaces WORLDS=new DhWorldNamespaces();
    private static final AtomicLong GENERATION=new AtomicLong();
    private static final AtomicLong VERSION = new AtomicLong();
    private static final AtomicLong CAPTURED_SECTIONS = new AtomicLong();
    private static final AtomicLong CAPTURED_QUADS = new AtomicLong();
    private static final AtomicLong CAPTURED_TRIANGLES = new AtomicLong();
    private static final AtomicLong CAPTURED_WATER_QUADS = new AtomicLong();
    private static final AtomicLong EMPTY_CAPTURES = new AtomicLong();
    private static final AtomicLong REFLECTION_FAILURES = new AtomicLong();
    private static final AtomicLong UNLOADS = new AtomicLong();

    private static volatile Refs refs;
    private static volatile boolean abiUnavailable;

    private DhLodCapture() {
    }

    /** Called on DH's build thread with the finished builder. Copies immediately; retains nothing of DH's. */
    public static void capture(long pos, Object builder, Object owner) {
        long generation=GENERATION.get();
        if (builder == null) {
            return;
        }
        Refs r = refs();
        if (r == null) {
            return;
        }
        try {
            Object[] opaque=(Object[])r.opaque.get(builder),transparent=(Object[])r.transparent.get(builder);
            var quads = new DhPackedSourceQuads.Builder(quadCapacity(opaque)+quadCapacity(transparent));
            int water = 0;
            water += copy(r, opaque, false, quads);
            water += copy(r, transparent, true, quads);
            // An empty finished child is ready coverage, not an unfinished/missing child.
            // Dropping it prevents a cached parent from ever handing off to its fine siblings.
            if (quads.size()==0) EMPTY_CAPTURES.incrementAndGet();
            Object wrapper=r.levelWrapper.get(owner);
            Object level=r.wrappedLevel.invoke(wrapper);
            int minY=((Number)r.minHeight.invoke(wrapper)).intValue();
            SectionSnapshot snapshot = new SectionSnapshot(pos,
                    ((Number) r.getDetailLevel.invoke(null, pos)).intValue(),
                    (int) r.getMinBlockX.invoke(null, pos),
                    (int) r.getMinBlockZ.invoke(null, pos),
                    quads.build(), water, VERSION.incrementAndGet(), minY);
            Object namespace=namespace(level);
            if(generation!=GENERATION.get() || namespace==null)return;
            SectionSnapshot previous=STORE.get(namespace,pos);
            if(sameGeometry(previous,snapshot))snapshot=previous;
            STORE.put(namespace,pos,owner,snapshot);
            if (CAPTURED_SECTIONS.incrementAndGet() == 1) CausticaMod.LOGGER.info("DH first LOD snapshot: section={} detail={} quads={} water={}",pos,snapshot.detailLevel(),quads.size(),water);
            CAPTURED_QUADS.addAndGet(quads.size());
            CAPTURED_TRIANGLES.addAndGet((long) quads.size() * 2L);
            CAPTURED_WATER_QUADS.addAndGet(water);
        } catch (Throwable failure) {
            if (REFLECTION_FAILURES.incrementAndGet() == 1) CausticaMod.LOGGER.error("DH LOD snapshot capture failed", failure);
            unload(pos,owner);
        }
    }

    private static int quadCapacity(Object[] groups){int n=0;if(groups!=null)for(Object group:groups)if(group instanceof List<?> list)n=Math.addExact(n,list.size());return n;}

    @SuppressWarnings("unchecked")
    private static int copy(Refs r, Object[] source, boolean transparent, DhPackedSourceQuads.Builder out) {
        if (source == null) {
            return 0;
        }
        int water = 0;
        for (Object element : source) {
            if (!(element instanceof List<?> list)) {
                continue;
            }
            for (Object raw : list) {
                if (raw == null) {
                    continue;
                }
                try {
                    byte material = r.material.getByte(raw);
                    boolean wet = transparent && isWater(r, material);
                    if (wet) {
                        water++;
                    }
                    Object dir = r.direction.get(raw);
                    out.add(r.x.getShort(raw), r.y.getShort(raw), r.z.getShort(raw),
                            r.widthEastWest.getShort(raw), r.widthNorthSouth.getShort(raw),
                            r.color.getInt(raw), r.textureTileId.getShort(raw), material,
                            r.skyLight.getByte(raw), r.blockLight.getByte(raw),
                            r.faceIndex.getInt(dir),
                            transparent, wet, r.vegetation[Byte.toUnsignedInt(material)]);
                } catch (Throwable unreadable) {
                    // An ABI/read failure is not proof of empty space. Reject the whole snapshot;
                    // otherwise a failed copy could falsely certify the child as ready-empty.
                    throw new IllegalStateException("DH quad snapshot is incomplete",unreadable);
                }
            }
        }
        return water;
    }

    /** DH block material class for RT shading: 0 plain, 1 light source (lamps, lava), 2 metal. */
    private static final byte[] MATERIAL_CLASS = new byte[256];
    public static int materialClass(int material) { return MATERIAL_CLASS[material & 255]; }

    private static boolean isWater(Refs r, byte material) {
        try {
            Object value = r.fromIndex.invoke(null, Byte.toUnsignedInt(material));
            return value instanceof Enum<?> e && "WATER".equals(e.name());
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static synchronized Refs refs() {
        if (abiUnavailable) return null;
        Refs current = refs;
        if (current != null) {
            return current;
        }
        try {
            current = new Refs();
            refs = current;
            return current;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            abiUnavailable = true;
            if (REFLECTION_FAILURES.incrementAndGet() == 1) CausticaMod.LOGGER.error("DH LOD capture ABI resolution failed; keeping near-field RT active", unavailable);
            return null;
        }
    }

    /** A section is closing; drop its snapshot so unloads do not accumulate. */
    public static void unload(long pos,Object section) {
        STORE.remove(pos,section);
        UNLOADS.incrementAndGet();
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        STORE.clear();WORLDS.clear();
        // Versions stay monotonic across world changes; GPU caches must never alias an old version.
        CAPTURED_SECTIONS.set(0L);
        CAPTURED_QUADS.set(0L);
        CAPTURED_TRIANGLES.set(0L);
        CAPTURED_WATER_QUADS.set(0L);
        EMPTY_CAPTURES.set(0L);
        REFLECTION_FAILURES.set(0L);
        UNLOADS.set(0L);
    }

    /** Bumped on every captured LOD mesh: stops changing once DH has nothing left to rebuild. */
    public static long version() { return VERSION.get(); }

    public static int sectionCount() {
        return STORE.count(currentLevel());
    }

    public static SectionSnapshot section(long pos) {
        return STORE.get(currentLevel(),pos);
    }

    public static List<SectionSnapshot> sections() {
        return STORE.values(currentLevel(),(owner,snapshot)->true);
    }

    /** Mutable selection for distance filtering, including while optional DH capture is unavailable. */
    public static List<SectionSnapshot> activeSections(Object level) {
        Refs r=refs; if(r==null)return new ArrayList<>();
        return STORE.values(namespace(level),(section,snapshot)->{
            try {return (boolean)r.renderEnabled.invoke(section);}
            catch(ReflectiveOperationException e) {if(REFLECTION_FAILURES.incrementAndGet()==1)CausticaMod.LOGGER.error("DH active section query failed",e);return false;}
        });
    }
    private static Object namespace(Object level){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc==null)return null;
        Object connection=mc.getConnection();
        if(level!=null && level==mc.level)return WORLDS.current(connection,level,mc.level.dimension());
        // Only owners already observed in this connection may publish to a cached dimension.
        return WORLDS.captured(connection,level);
    }
    private static Object currentLevel(){var mc=net.minecraft.client.Minecraft.getInstance();return mc==null?null:namespace(mc.level);}

    public static String stats() {
        return "capturedSections=" + CAPTURED_SECTIONS.get()
                + " liveSections=" + STORE.count(currentLevel())
                + " quads=" + CAPTURED_QUADS.get()
                + " triangles=" + CAPTURED_TRIANGLES.get()
                + " waterQuads=" + CAPTURED_WATER_QUADS.get()
                + " emptyCaptures=" + EMPTY_CAPTURES.get()
                + " reflectionFailures=" + REFLECTION_FAILURES.get()
                + " unloads=" + UNLOADS.get();
    }

    static boolean sameGeometry(SectionSnapshot a,SectionSnapshot b) {
        return a!=null && a.pos()==b.pos() && a.detailLevel()==b.detailLevel()
            && a.minBlockX()==b.minBlockX() && a.minBlockZ()==b.minBlockZ() && a.minY()==b.minY()
            && a.waterQuads()==b.waterQuads() && sameRtQuads(a.quads(),b.quads());
    }
    /** DH light updates do not alter our unlit geometry/materials; Caustica traces that lighting itself. */
    private static boolean sameRtQuads(List<LodQuad> a,List<LodQuad> b) {
        if(a instanceof DhPackedSourceQuads x && b instanceof DhPackedSourceQuads y)return x.sameRtGeometry(y);
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++){
            var x=a.get(i);var y=b.get(i);
            if(x==y)continue;
            if(x.x()!=y.x() || x.y()!=y.y() || x.z()!=y.z()
                || x.widthEastWest()!=y.widthEastWest() || x.widthNorthSouthOrHeight()!=y.widthNorthSouthOrHeight()
                || x.color()!=y.color() || x.textureTileId()!=y.textureTileId() || x.materialId()!=y.materialId()
                || x.direction()!=y.direction() || x.transparent()!=y.transparent() || x.water()!=y.water()
                || x.vegetation()!=y.vegetation())return false;
        }
        return true;
    }

    // DH 3.2 -> 3.3 renamed this field; resolve by the declared API, never binary offsets.
    static Field levelWrapperField(Class<?> section) throws NoSuchFieldException {
        Field field;
        try { field = section.getDeclaredField("clientLevelWrapper"); }
        catch (NoSuchFieldException olderVersion) { field = section.getDeclaredField("levelWrapper"); }
        field.setAccessible(true);
        return field;
    }

    /** Cached DH reflection handles; resolved once. */
    private static final class Refs {
        final Class<?> quad;
        final boolean[] vegetation=new boolean[256];
        final Field opaque, transparent, levelWrapper, faceIndex;
        final Method minHeight,wrappedLevel,renderEnabled;
        final Field x, y, z, widthEastWest, widthNorthSouth, color, textureTileId, material, skyLight,
                blockLight, direction;
        final Method getDetailLevel, getMinBlockX, getMinBlockZ, fromIndex;

        Refs() throws ReflectiveOperationException {
            quad = Class.forName(
                    "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.BufferQuad");
            Class<?> builder = Class.forName("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder");
            Class<?> section=Class.forName("com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection");
            levelWrapper=levelWrapperField(section);
            renderEnabled=section.getMethod("getRenderingEnabled");
            Class<?> wrapper=Class.forName("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper");
            minHeight=wrapper.getMethod("getMinHeight");wrappedLevel=wrapper.getMethod("getWrappedMcObject");
            faceIndex=Class.forName("com.seibel.distanthorizons.core.enums.EDhDirection").getField("faceIndex");
            opaque = builder.getDeclaredField("opaqueQuads"); opaque.setAccessible(true);
            transparent = builder.getDeclaredField("transparentQuads"); transparent.setAccessible(true);
            x = field("x");
            y = field("y");
            z = field("z");
            widthEastWest = field("widthEastWest");
            widthNorthSouth = field("widthNorthSouthOrHeight");
            color = field("color");
            textureTileId = field("textureTileId");
            material = field("irisBlockMaterialId");
            skyLight = field("skyLight");
            blockLight = field("blockLight");
            direction = field("direction");
            Class<?> sectionPos = Class.forName("com.seibel.distanthorizons.core.pos.DhSectionPos");
            getDetailLevel = sectionPos.getMethod("getDetailLevel", long.class);
            getMinBlockX = sectionPos.getMethod("getMinCornerBlockX", long.class);
            getMinBlockZ = sectionPos.getMethod("getMinCornerBlockZ", long.class);
            Class<?> blockMaterial = Class.forName(
                    "com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial");
            fromIndex = blockMaterial.getMethod("getFromIndex", int.class);
            Field index=blockMaterial.getField("index");
            for(String name:new String[]{"LEAVES","GRASS"})vegetation[Byte.toUnsignedInt(index.getByte(blockMaterial.getField(name).get(null)))]=true;
            // Optional names: an older DH without one of them simply leaves that class empty.
            for(String name:new String[]{"ILLUMINATED","LAVA","METAL"}) {
                try { MATERIAL_CLASS[Byte.toUnsignedInt(index.getByte(blockMaterial.getField(name).get(null)))]=(byte)(name.equals("METAL")?2:1); }
                catch (NoSuchFieldException absent) {}
            }
        }

        private Field field(String name) throws NoSuchFieldException {
            Field f = quad.getField(name);
            f.setAccessible(true);
            return f;
        }
    }
}
