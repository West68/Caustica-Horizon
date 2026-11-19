// Waving foliage (ITRP WavingPlants.glsl, reimplemented for RT). One thread per vertex of a waving
// terrain section: writes the displaced BLAS input position from the meshed rest position, and the
// per-vertex motion since last frame for RR. The section BLAS is refit from these positions afterwards.
//
// Per-vertex weight w (RtWaving.encode): 0 still; w < 0 leaves (|w| = skylight weight); w >= 4 crop
// (w - 4 = bend weight); else plant bend weight. Bend weights already include the skylight weight.

typealias WPtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device>;

struct WavePush {
    uint64_t rest;      // float3 per vertex, section-local
    uint64_t weights;   // float per vertex
    uint64_t positions; // float3 per vertex, BLAS input
    uint64_t disp;      // float4 per vertex, current - previous world position
    float3 origin;      // section origin in world blocks, wrapped to keep float precision
    uint count;
    float3 camera;      // camera relative to the section origin
    float time;         // seconds * waving speed
    float prevTime;
    float rain;         // wetness 0..1
    float strength;     // amplitude multiplier (0 = still, settles the section to rest)
    float range;        // ITRP WAVING_RANGE
};
[[vk::push_constant]] WavePush wp;

float hash2(float2 p) {
    float3 p3 = frac(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

// Cubic B-spline weights: C2-continuous, like ITRP's BicubicBlurTexture.
float4 bspline(float t) {
    float t2 = t * t, t3 = t2 * t;
    return float4((1.0 - t) * (1.0 - t) * (1.0 - t), 3.0 * t3 - 6.0 * t2 + 4.0, -3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0, t3) / 6.0;
}

// Stand-in for ITRP's bicubic-blurred noise texture (one lattice cell = one texel). Plain value noise with
// smoothstep interpolation stops dead at every lattice crossing (zero slope there, then speeds up again);
// the plant phase multiplies the noise by 14-21, so each crossing became a visible stop-and-snap (about twice a second) that RR smeared into a
// doubled image. A B-spline blur has continuous slope and curvature, and lower variance, as ITRP's does.
float smoothNoise(float2 uv) {
    float2 i = floor(uv), f = uv - i;
    float4 wx = bspline(f.x), wy = bspline(f.y);
    float s = 0.0;
    [unroll] for (int y = 0; y < 4; y++) {
        float row = 0.0;
        [unroll] for (int x = 0; x < 4; x++) row += wx[x] * hash2(i + float2(x - 1, y - 1));
        s += wy[y] * row;
    }
    return s;
}

float3 waveOffset(float3 world, float dist, float w, float timer) {
    float2 rainAmp = 1.0 + wp.rain * float2(0.5, 0.2);
    float2 uv = (world.xz + timer * float2(5.0, -1.0)) * 0.05;
    float wind = smoothNoise(uv) * 0.85 + 0.15;
    // Fade to zero over the last quarter of the range.
    wind *= saturate((dist - wp.range) / (wp.range * -0.25));
    wind *= wp.strength;
    float3 offset = float3(0.0, 0.0, 0.0);
    if (w < 0.0) {
        wind *= -w; // skylight weight
        wind *= sin(timer * 2.22 * rainAmp.y + world.x + world.z * 0.5) * 0.015 + 0.015;
        offset = sin(timer * (120.0 / float3(19.0, 11.0, 17.0)) + world.x * 1.2 - world.z * 1.5)
                * float3(1.0, 0.5, 1.0) * wind * rainAmp.x;
    } else {
        bool crop = w >= 4.0;
        wind *= crop ? w - 4.0 : w;
        float wheat = crop ? 1.0 : 0.0;
        float2 uv2 = uv * (4.0 + 4.0 * wheat) + timer * (float2(-2.0, 2.0) * 0.2);
        float rand = smoothNoise(uv2);
        float2 o = sin(timer * rainAmp.y * float2(5.0, 3.0) + rand * float2(14.0, 21.0)
                + world.zx * (1.0 - wheat * 0.7)) * float2(0.07, 0.04) * rainAmp.x;
        offset.xz = (o + float2(-0.05, 0.02) * rainAmp.x) * wind;
        offset.y = (cos(max(abs(o.x) * 2.0, abs(o.y))) - 1.0) * wind;
    }
    return offset;
}

[shader("compute")]
[numthreads(128, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= wp.count) return;
    WPtr<float> rest = WPtr<float>(wp.rest);
    WPtr<float> pos = WPtr<float>(wp.positions);
    WPtr<float4> disp = WPtr<float4>(wp.disp);
    float w = WPtr<float>(wp.weights)[i];
    float3 p = float3(rest[3u * i], rest[3u * i + 1u], rest[3u * i + 2u]);
    float3 now = float3(0.0, 0.0, 0.0), before = float3(0.0, 0.0, 0.0);
    if (w != 0.0) {
        float3 world = wp.origin + p;
        float dist = length(p - wp.camera);
        now = waveOffset(world, dist, w, wp.time);
        before = waveOffset(world, dist, w, wp.prevTime);
    }
    float3 q = p + now;
    pos[3u * i] = q.x;
    pos[3u * i + 1u] = q.y;
    pos[3u * i + 2u] = q.z;
    disp[i] = float4(now - before, 0.0);
}
