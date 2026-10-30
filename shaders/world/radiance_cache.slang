// World-space radiance cache for indirect light (SHaRC-style spatial hash; algorithm after NVIDIA's
// Spatially Hashed Radiance Cache: a sparse Update pass records full paths into a hash grid, a Resolve
// pass blends them over time, and the main pass terminates eligible indirect paths with the cached
// outgoing radiance). Own implementation on 64-bit device addresses; no SDK source is included.
//
// Layout (one entry per slot, capacity is a power of two):
//   entries  uint   hash key (0 = empty)
//   accum    uint4  fixed-point radiance sum xyz, path sample count w (atomic, cleared by Resolve)
//   resolved float4 blended radiance xyz, accumulated frame count w (0 = unusable)
//   meta     uint   frames since the last sample (eviction)
module radiance_cache;

public static const uint RC_PROBE = 8u;
public static const uint RC_INVALID = 0xFFFFFFFFu;
public static const float RC_RADIANCE_SCALE = 256.0;

public typealias RcPtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device>;

public struct RcGrid {
    public float3 camera;      // absolute camera position
    public float  sceneScale;  // voxels per block of camera distance: voxel edge ~= distance / sceneScale
};

// Power-of-two voxel edge that grows with camera distance (1/16 block up close, ~distance/sceneScale far).
public int rcLevel(in RcGrid g, float3 absPos) {
    float d = length(absPos - g.camera);
    return clamp(int(floor(log2(max(d, 1.0e-3) / g.sceneScale))), -4, 11);
}

public float rcVoxel(in RcGrid g, float3 absPos) {
    return exp2(float(rcLevel(g, absPos)));
}

// Continuous level: rcLevel is its floor. Lets a lookup blend two voxel sizes near an octave boundary.
public float rcLevelF(in RcGrid g, float3 absPos) {
    float d = length(absPos - g.camera);
    return clamp(log2(max(d, 1.0e-3) / g.sceneScale), -4.0, 11.0);
}

public uint rcHash(in RcGrid g, float3 absPos, float3 n) {
    return rcHashLevel(g, absPos, n, rcLevel(g, absPos));
}

public uint rcHashLevel(in RcGrid g, float3 absPos, float3 n, int level) {
    float voxel = exp2(float(level));
    int3 q = int3(floor(absPos / voxel));
    // Dominant-axis normal bucket (6 values) keeps both sides of a wall apart.
    float3 a = abs(n);
    uint face = a.x >= a.y && a.x >= a.z ? (n.x >= 0.0 ? 0u : 1u)
              : a.y >= a.z ? (n.y >= 0.0 ? 2u : 3u) : (n.z >= 0.0 ? 4u : 5u);
    uint h = uint(q.x) * 73856093u ^ uint(q.y) * 19349663u ^ uint(q.z) * 83492791u
           ^ uint(level + 8) * 2654435761u ^ face * 2246822519u;
    h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u;
    return h == 0u ? 1u : h;
}

uint rcBase(uint hash, uint capacity) {
    return (hash * 2654435769u) & (capacity - 1u) & ~(RC_PROBE - 1u);
}

public uint rcInsert(RcPtr<uint> entries, uint hash, uint capacity) {
    uint base = rcBase(hash, capacity);
    for (uint i = 0u; i < RC_PROBE; i++) {
        uint prev;
        InterlockedCompareExchange(entries[base + i], 0u, hash, prev);
        if (prev == 0u || prev == hash) return base + i;
    }
    return RC_INVALID;
}

public uint rcFind(RcPtr<uint> entries, uint hash, uint capacity) {
    uint base = rcBase(hash, capacity);
    for (uint i = 0u; i < RC_PROBE; i++) {
        if (entries[base + i] == hash) return base + i;
    }
    return RC_INVALID;
}

public void rcAccumulate(RcPtr<uint> accum, uint slot, float3 radiance) {
    uint3 r = uint3(clamp(radiance * RC_RADIANCE_SCALE, float3(0.0), float3(4.0e6)));
    uint b = slot * 4u;
    uint o;
    if (r.x != 0u) InterlockedAdd(accum[b + 0u], r.x, o);
    if (r.y != 0u) InterlockedAdd(accum[b + 1u], r.y, o);
    if (r.z != 0u) InterlockedAdd(accum[b + 2u], r.z, o);
    InterlockedAdd(accum[b + 3u], 1u, o);
}

// Resolved radiance if the entry has at least minFrames of history.
public bool rcQuery(RcPtr<uint> entries, RcPtr<float4> resolved, uint hash, uint capacity, float minFrames,
        out float3 radiance) {
    radiance = float3(0.0);
    uint slot = rcFind(entries, hash, capacity);
    if (slot == RC_INVALID) return false;
    float4 v = resolved[slot];
    if (!(v.w >= minFrames) || !all(isfinite(v.xyz))) return false;
    radiance = max(v.xyz, float3(0.0));
    return true;
}
