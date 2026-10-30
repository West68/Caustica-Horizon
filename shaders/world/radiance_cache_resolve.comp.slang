// Resolve pass of the world radiance cache: blend this frame's Update samples into the resolved value
// (bounded moving average), clear the accumulator and evict entries that stopped receiving samples.
import radiance_cache;

struct ResolvePush {
    uint64_t entries;
    uint64_t accum;
    uint64_t resolved;
    uint64_t meta;
    uint capacity;
    uint maxFrames;   // history length of the moving average
    uint staleFrames; // evict after this many frames without samples
    uint clear;       // 1 = wipe everything (world/dimension change)
};
[[vk::push_constant]] ResolvePush rp;

[shader("compute")]
[numthreads(256, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= rp.capacity) return;
    RcPtr<uint> entries = RcPtr<uint>(rp.entries);
    RcPtr<uint> accum = RcPtr<uint>(rp.accum);
    RcPtr<float4> resolved = RcPtr<float4>(rp.resolved);
    RcPtr<uint> meta = RcPtr<uint>(rp.meta);
    uint b = i * 4u;
    if (rp.clear != 0u) {
        entries[i] = 0u; accum[b] = 0u; accum[b + 1u] = 0u; accum[b + 2u] = 0u; accum[b + 3u] = 0u;
        resolved[i] = float4(0.0); meta[i] = 0u;
        return;
    }
    if (entries[i] == 0u) return;
    uint count = accum[b + 3u];
    float4 r = resolved[i];
    if (count > 0u) {
        float3 sample = float3(accum[b], accum[b + 1u], accum[b + 2u]) / (RC_RADIANCE_SCALE * float(count));
        float frames = min(r.w + 1.0, float(rp.maxFrames));
        r.xyz = r.w > 0.0 ? lerp(r.xyz, sample, 1.0 / frames) : sample;
        r.w = frames;
        resolved[i] = r;
        meta[i] = 0u;
        accum[b] = 0u; accum[b + 1u] = 0u; accum[b + 2u] = 0u; accum[b + 3u] = 0u;
    } else {
        uint stale = meta[i] + 1u;
        if (stale > rp.staleFrames) {
            entries[i] = 0u; resolved[i] = float4(0.0); meta[i] = 0u;
        } else {
            meta[i] = stale;
        }
    }
}
