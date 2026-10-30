// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;
    public uint frameIndex;
    public uint debugView;
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint64_t tableAddr;        // section table
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint64_t entityTableAddr;  // entity geometry table
    public uint     flags;            // bit0 camera submerged, bit1 PBR BRDF, bit4 water waves
    public uint     maxBounces;
    public float4   sunDir;           // xyz true sun direction, w dayFactor 0..1
    public float4   lightDir;         // xyz active NEE light dir, w square half-angle (rad)
    public float4   lightRadiance;    // xyz HDR light radiance, w star brightness
    public float4   moonDir;          // xyz moon direction, w moonPhase (0 full .. 4 new)
    public float4   celestial;        // xyz celestial rotation axis, w star angle (rad)
    public float4   sunUv;            // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    public float4   moonUv;           // vanilla current-moon-phase sprite atlas rect
    public float4   waterParams;      // xyz camera-biome water tint, w wave time (s)
    public float4   waterAnchor;      // xy world-stable wave-domain anchor
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public uint     breakCount;
    public uint64_t cloudNoiseAddr;    // optional user-owned 64x64 RGBA8 data
    public float4 cloudWorld;         // absolute camera xyz, rain strength w
    public float4 cloudShape; // coverage bias, density, size, absolute height
    public float4 cloudLight; // brightness, underside, sunlight, wind
    public float4 cloudDetail; // erosion, cirrus, rain response, zero-F0 pack compatibility floor
    public float4 materialParams; // parallax strength and smooth steps
    public float4 mainLightPosition; // rebased xyz, finite radius
    public float4 mainLightColor; // linear intensity
    public float4 offLightPosition;
    public float4 offLightColor;
    public float4 environment; // dimension: 0 overworld, 1 nether, 2 end, 3 custom; wetness; fog scale; camera skylight
    public float4 mediumParams; // water scatter strength, rain strength, sea level, camera in lava
    public float4 dimensionFog; // biome-smoothed linear environment source RGB, extinction
    public float4 visualLight; // emission gain, End halo gain, stars, sky gain
    public float4 surfaceControl; // F0 scale, normal strength, portal brightness, relief shadow strength
    public float4 dimensionControl; // End cycle minutes, Nether gain, puddle coverage, ripple strength
    public float4 torchControl; // x independent torch emission gain, y ITRP sunlight rim strength; zw reserved
    public uint64_t torchGuideAddr; // optional 16^3 float4 continuation hints, not light energy
    public float4 torchGuideOrigin; // rebased lower corner xyz, cell size
    public BreakEntry breaking[8];    // Java capacity and breakCount serialization are generated from this
    public float4x4 dhViewProjection;
    public float4x4 dhInverseViewProjection;
    public int4 dhTextures; // color/depth slots; negative-one depth range; reverse-Z
    // World radiance cache (radiance_cache.slang). cacheCapacity 0 disables every cache path.
    public uint64_t cacheEntriesAddr;
    public uint64_t cacheAccumAddr;
    public uint64_t cacheResolvedAddr;
    public uint cacheCapacity;
    public uint cacheFlags;    // bit0: main pass may terminate eligible indirect paths with cached radiance
    public float4 cacheParams; // x voxels per block of camera distance, y minimum resolved frames, z footprint factor, w update tile
    // Per-frame sky radiance LUT (equirectangular float4) for diffuse continuation misses; 0 = disabled.
    public uint64_t skyLutAddr;
    public uint skyLutWidth;
    public uint skyLutHeight;
    // ITRP NetherColor.glsl NetherLighting(): biome-smoothed Nether ambient (rgb, already * 8e-5).
    public float4 netherLighting;
};

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
public struct Payload {
    public float3 albedo;      // hit: block albedo. miss: sky radiance.
    public float3 normal;      // hit: geometric normal, viewer-oriented.
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public float3 motionPrev;  // per-vertex world displacement since last frame.
    public float3 f0;          // opaque: specular F0; glass: viewer-oriented geometric boundary normal.
    public uint   flags;       // bits 0..1 material, bit 2 celestial, bit 3 water-entering, bits 4..6 emission source.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // trace input: packHalf2(width,spread); HAS_RELIEF hit output: float depth bits
};

// Hit-only flag: an opaque height-displaced receiver needs outgoing height-field visibility.
// Query-only alias: invoke its retained hit object without another traversal or a larger payload.
public static const uint PAYLOAD_HAS_RELIEF = 0x10000000u;
public static const uint PAYLOAD_RELIEF_QUERY = 0x20000000u;
// Static glass may supply RR guides from its actual optical continuation, rather than the front pane.
public static const uint PAYLOAD_STATIC_GLASS = 0x40000000u;
// Hit output in payload.rayCone for a Distant Horizons LOD surface (never a valid packed cone or relief depth).
public static const uint DH_HIT_MARK = 0xFFFFD4D4u;

// Trace-input-only alias. Closest hit consumes this before replacing flags with hit material data.
// Hit-only visibility lanes may reuse the bit; every new radiance ray resets flags first.
public static const uint PAYLOAD_REFLECTION_GUIDE = 256u;
public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Miss-input only: this ray is building the sky LUT, so the miss must evaluate the exact sky.
public static const uint PAYLOAD_SKY_LUT_BUILD = 0x80000000u;

// Equirectangular sky LUT shared by the builder (raygen) and the consumer (miss).
public float2 skyLutUv(float3 dir) {
    return float2(atan2(dir.z, dir.x) * 0.15915494 + 0.5, acos(clamp(dir.y, -1.0, 1.0)) * 0.31830989);
}
public float3 skyLutDir(float2 uv) {
    float phi = (uv.x - 0.5) * 6.28318531, theta = uv.y * 3.14159265;
    return float3(cos(phi) * sin(theta), cos(theta), sin(phi) * sin(theta));
}
public float3 sampleSkyLut(uint64_t addr, uint w, uint h, float3 dir) {
    float2 p = skyLutUv(dir) * float2(w, h) - 0.5;
    int2 i0 = int2(floor(p));
    float2 f = p - float2(i0);
    ConstPtr<float4> lut = ConstPtr<float4>(addr);
    int x0 = (i0.x % int(w) + int(w)) % int(w), x1 = (x0 + 1) % int(w); // longitude wraps
    int y0 = clamp(i0.y, 0, int(h) - 1), y1 = clamp(i0.y + 1, 0, int(h) - 1);
    float3 a = lerp(lut[y0 * int(w) + x0].xyz, lut[y0 * int(w) + x1].xyz, f.x);
    float3 b = lerp(lut[y1 * int(w) + x0].xyz, lut[y1 * int(w) + x1].xyz, f.x);
    return lerp(a, b, f.y);
}
// Hit-only alias: suppress direct illumination from own analytic held emitters.
public static const uint PAYLOAD_HELD_OWNER = 4u;
// The low half of iorTransmission is a strictly positive IOR. Its unused sign bit tags the
// separately captured first-person receiver without expanding the cross-stage payload.
public static const uint FIRST_PERSON_RECEIVER = 0x8000u;
// Miss-only camera classification; does not change payload storage or hit material semantics.
public static const uint PAYLOAD_CAMERA_MISS = 128u;
// Same lane after a HIT: rain exposure; trace state is reset before every new ray.
public static const uint PAYLOAD_RAIN_EXPOSED = 128u;
// Set by world.rchit on a MATERIAL_WATER hit: true when the incoming ray travels against the prim's
// outward face normal (entering the water volume), false when it exits (water -> air). Bit 2
// (PAYLOAD_SHOW_CELESTIAL) is only meaningful on a miss and this bit only on a hit, so they never need
// to be read together, but they get distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_WATER_ENTERING = 8u;
public static const uint PAYLOAD_EMISSION_SOURCE_SHIFT = 4u;
public static const uint PAYLOAD_EMISSION_SOURCE_MASK = 7u;
public static const uint EMISSION_SOURCE_NONE = 0u;
public static const uint EMISSION_SOURCE_UNIFORM = 1u;
public static const uint EMISSION_SOURCE_HEURISTIC = 2u;
public static const uint EMISSION_SOURCE_LAB_PBR = 3u;
public static const uint EMISSION_SOURCE_OVERRIDE = 4u;
public static const uint EMISSION_SOURCE_PORTAL = 5u;

public static const uint PAYLOAD_MATERIAL_MASK = 3u;
public static const uint MATERIAL_OPAQUE = 0u;
public static const uint MATERIAL_WATER = 1u;
public static const uint MATERIAL_PARTICLE = 2u;
public static const uint MATERIAL_GLASS = 3u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Item glint: original float UVs for base alpha, half UVs only for the low-resolution effect texture.
public float2 foilEffectUv(Prim p,float3 bary) {
    uint a=asuint(p.normal.w),b=p.aux0,c=p.aux1;
    return bary.x*float2(f16tof32(a&65535u),f16tof32(a>>16u))
        +bary.y*float2(f16tof32(b&65535u),f16tof32(b>>16u))
        +bary.z*float2(f16tof32(c&65535u),f16tof32(c>>16u));
}

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in MaterialHeader instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Low address bit tags a lossless section-local primitive offset table; actual BDA stays aligned.
// Raw records remain supported for sections with no profitable sharing. No Section/Prim ABI change.
public TerrainPrim terrainPrimitive(uint64_t taggedAddress, uint triangle) {
    if ((taggedAddress & uint64_t(1)) == 0) return ConstPtr<TerrainPrim>(taggedAddress)[triangle];
    uint64_t base=taggedAddress & ~uint64_t(1);
    uint offset=ConstPtr<uint>(base)[triangle];
    return ConstPtr<TerrainPrim>(base+uint64_t(offset))[0];
}

public uint64_t terrainUvTriangleAddress(uint64_t taggedAddress, uint triangle) {
    if ((taggedAddress & uint64_t(1)) == 0) return taggedAddress + uint64_t(triangle)*24u;
    uint64_t base=taggedAddress & ~uint64_t(1);
    return base + uint64_t(ConstPtr<uint>(base)[triangle]);
}

// Compiled material record (80 B). Source-format data has already been decoded into canonical pages;
// texturePage indexes the dedicated canonical page arrays. materialUv maps sprite-local UV into the packed
// page, while albedoUv maps the vanilla atlas rectangle back to sprite-local UV.
public struct MaterialHeader {
    public uint model;
    public uint features;        // low feature bits; bits 24..31 = maximum valid material-page LOD
    public uint texturePage;
    public uint extensionOffset; // zero when no optional extension record is present
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
    public float4 params;  // roughness, metalness, IOR, transmission
    public float4 average; // whole-sprite linear-input average RGBA for translucent shadow filtering
};

public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_HEURISTIC_EMISSION = 4u;
public static const uint MATERIAL_FEATURE_OVERRIDE_EMISSION = 8u;
public static const uint MATERIAL_FEATURE_STOCHASTIC_ALPHA = 16u;
public static const uint MATERIAL_EMISSION_STRENGTH_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_STRENGTH_MASK = 255u;
public static const float MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH = 4.0;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;
public static const uint MATERIAL_MAX_LOD_MASK = 255u;

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/any-hit; PrimitiveIndex restarts at zero for each geometry.
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[2];
    public uint2 reserved; // x: local held-light owner (body / separately captured hands)
};

// TLAS instance-custom-index encoding + terrain geometry buckets (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index
public static const uint BUCKET_CUTOUT = 1u;
public static const uint BUCKET_TRANSLUCENT = 2u;
public static const uint BUCKET_WATER = 3u;

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

// Vanilla's block/entity atlases are ordinary sRGB-encoded PNGs sampled through a plain UNORM view (no
// hardware sRGB decode), so the raw texel is gamma-encoded. The path tracer's BRDF math (Lambertian/GGX,
// Beer-Lambert, NEE) is only correct on linear radiance/albedo, so every COLOR texture sample (not the
// LabPBR _s/_n data maps, which are already linear data channels) must go through this before it's used
// in any lighting calculation.
public float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4, 2.4, 2.4));
    float3 isHi = step(float3(0.04045, 0.04045, 0.04045), c);
    return lerp(lo, hi, isHi);
}
