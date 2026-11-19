// Volumetric cumulus deck (cloud mode 3) for Caustica's sky miss path, styled after ITRP Alpha's
// volumetric clouds and driven by the same option set (altitude, thickness, coverage, density, scale,
// sun/sky lighting, base/detail noise scale, detail strength, coverage offset, quality).
//
// Written from the published real-time cloud technique ("Nubis": Perlin-Worley shape + Worley detail
// erosion, a low-frequency coverage map, a height profile, Beer transmittance toward the sun and a
// multiple-scattering approximation by phase-lobe octaves), not from any shader pack's source. The noise
// textures are generated procedurally at load time (ItrpVolumeNoise.java).

import world_common;
import itrp_atmosphere;

[[vk::binding(10, 0)]] public Sampler3D itrpVolumeShapeNoise;  // r Perlin-Worley, gba Worley fBm 4/8/16
[[vk::binding(11, 0)]] public Sampler3D itrpVolumeDetailNoise; // rgb Worley fBm 4/8/16

static const float VOLUME_PLANET_RADIUS = 6360000.0; // blocks; curves the deck down at the horizon
static const float VOLUME_MAX_MARCH = 60000.0;       // blocks of deck traversed at most per ray
static const float VOLUME_VIEW_EXTINCTION = 0.416;   // per block at density 1 (0.6 in log2 units)
static const float VOLUME_LIGHT_EXTINCTION = 0.08;   // per block toward the sun: lit interiors stay bright

public struct VolumeParams {
    public float bottom;       // absolute world Y of the deck base
    public float thickness;    // blocks
    public float coverage;     // clear/rain blended
    public float density;
    public float scale;
    public float sunlight;     // clear/rain blended
    public float skylight;
    public float baseScale;    // shape noise tiles per scaled block
    public float detailScale;  // detail noise tiles per scaled block
    public float detailStrength;
    public float coverageOffset;
    public float quality;
    public float rain;
    public float time;
};

public VolumeParams itrpVolumeParams(WorldPush w) {
    VolumeParams p;
    float rain = saturate(w.cloudWorld.w * w.cloudDetail.z);
    p.bottom = w.cloudVolume0.x;
    p.thickness = max(w.cloudVolume0.y, 1.0);
    p.coverage = lerp(w.cloudVolume0.z, w.cloudVolume0.w, rain);
    // Rain deck (ITRP CLOUD_RAIN_DENSITY / CLOUD_RAIN_SCALE); 0 keeps the clear-sky value.
    p.density = lerp(w.cloudVolume1.x, w.cloudVolume3.z > 0.0 ? w.cloudVolume3.z : w.cloudVolume1.x, rain);
    p.scale = max(lerp(w.cloudVolume1.y, w.cloudVolume3.w > 0.0 ? w.cloudVolume3.w : w.cloudVolume1.y, rain), 1e-3);
    p.sunlight = lerp(w.cloudVolume1.z, w.cloudVolume1.w, rain);
    p.skylight = w.cloudVolume2.x;
    p.baseScale = w.cloudVolume2.y;
    p.detailScale = w.cloudVolume2.z;
    p.detailStrength = w.cloudVolume2.w;
    p.coverageOffset = w.cloudVolume3.x;
    p.quality = max(w.cloudVolume3.y, 0.25);
    p.rain = rain;
    p.time = w.waterParams.w * w.cloudLight.w;
    return p;
}

float volumeRemap01(float v, float lo, float hi) {
    return saturate((v - lo) / max(hi - lo, 1e-5));
}

// Height of a camera-relative-curved point above the deck base, in [0,1] across the deck.
float volumeHeight(float3 worldPos, float3 camera, VolumeParams p) {
    float2 dxz = worldPos.xz - camera.xz;
    // Planet curvature: points far away sit lower relative to a deck of constant altitude.
    float drop = dot(dxz, dxz) / (2.0 * VOLUME_PLANET_RADIUS);
    return (worldPos.y + drop - p.bottom) / p.thickness;
}

// x = shape density (no erosion), y = eroded density. h is the height fraction in the deck.
float2 volumeDensity(float3 worldPos, float h, bool detail, VolumeParams p) {
    if (h <= 0.0 || h >= 1.0) return float2(0.0, 0.0);
    float3 wind = float3(9.0, 0.0, 3.5) * p.time;
    // Tops drift slightly ahead of bases (a few dozen scaled blocks), leaning the towers downwind. This
    // offset must NOT grow with thickness, or tall decks shear into long diagonal streaks.
    float3 q = (worldPos + wind) * p.scale + float3(30.0, 0.0, -12.0) * h;

    float4 n = itrpVolumeShapeNoise.SampleLevel(q * p.baseScale, 0.0);
    // ITRP NUBIS density model: Perlin-Worley eroded by a .4/.4/.2 Worley fBm.
    float cells = dot(n.gba, float3(0.4, 0.4, 0.2));
    float shape = volumeRemap01(n.r, cells - 1.0, 1.0);

    // Vertical shape: a hump that peaks low in the deck and a long taper to the top; rain squares the base.
    float top1 = 1.0 - h; top1 = 1.0 - (top1 - 1.0) * (top1 - 1.0);
    float lower = saturate(1.15 - h * 1.43); lower = lower * lower * (3.0 - 2.0 * lower);
    shape *= top1 * 0.5 + lower * 0.5;
    shape *= sqrt(saturate(h * 2.5)) * 0.2 + 0.8;
    float rb = saturate(h * 3.0); rb = 1.0 - (rb - 1.0) * (rb - 1.0);
    shape *= lerp(1.0, rb, p.rain);

    // Low-frequency coverage map: where it is high, the threshold drops and more of the shape survives.
    float map = itrpVolumeShapeNoise.SampleLevel(q * float3(8.5e-5, 1e-5, 8.5e-5) + p.coverageOffset, 0.0).r;
    float threshold = volumeRemap01(1.0 - map, p.coverage * 0.2, max(p.coverage, 1e-3));
    float d = volumeRemap01(shape, threshold, 1.0);
    // Denser tops, soft bottoms.
    float upper = saturate(h * 1.8 - 0.8); upper = upper * upper * (3.0 - 2.0 * upper);
    float base = saturate(h * 1.8); base = 1.0 - (base - 1.0) * (base - 1.0);
    d *= (upper * 2.0 + 1.0) * base;
    d = saturate(d);

    float eroded = d;
    if (detail && d > 1e-4) {
        float3 dq = q * p.detailScale + wind * p.scale * p.detailScale * 6.0;
        float3 dn = itrpVolumeDetailNoise.SampleLevel(dq, 0.0).rgb;
        float wisps = 1.0 - dot(dn, float3(0.625, 0.25, 0.125));
        float erosion = wisps * (0.21 - 0.11 * p.rain) * p.detailStrength;
        eroded = volumeRemap01(d, erosion, 1.0);
    }
    return float2(d, eroded);
}

float volumeHash(float3 p) {
    float3 p3 = frac(p * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// Ray against the deck shell: returns [t0, t1] (t1 < t0 = miss), camera at absolute height camY.
float2 volumeSpan(float camY, float3 dir, VolumeParams p) {
    float r0 = VOLUME_PLANET_RADIUS + camY;
    float rb = VOLUME_PLANET_RADIUS + p.bottom;
    float rt = rb + p.thickness;
    // |(0,r0,0) + t dir|^2 = r^2  ->  t^2 + 2 b t + c = 0
    float b = r0 * dir.y;
    float discT = b * b - (r0 * r0 - rt * rt);
    if (discT < 0.0) return float2(1.0, 0.0);
    float tTopFar = -b + sqrt(discT);
    float tTopNear = -b - sqrt(discT);
    float discB = b * b - (r0 * r0 - rb * rb);
    if (camY < p.bottom) {
        if (discB < 0.0) return float2(1.0, 0.0);
        float tBase = -b + sqrt(discB);
        return float2(max(tBase, 0.0), tTopFar);
    }
    if (camY > p.bottom + p.thickness) {
        if (tTopNear < 0.0) return float2(1.0, 0.0);
        float tEnd = discB >= 0.0 ? -b - sqrt(discB) : tTopFar;
        return float2(tTopNear, tEnd);
    }
    // Inside the deck: march to whichever boundary comes first along the ray.
    float tEnd = (discB >= 0.0 && dir.y < 0.0) ? -b - sqrt(discB) : tTopFar;
    return float2(0.0, min(tEnd, 6000.0));
}

// Sun visibility through the deck from a point inside it, plus multiple-scattering octaves.
float volumeSunScatter(float3 pos, float3 camera, float3 light, float cosTheta, float d, float h, VolumeParams p, bool detail) {
    float optical = 0.0;
    float stepLen = 0.06 * p.thickness;
    float3 s = pos;
    int taps = detail ? 5 : 3;
    for (int i = 0; i < taps; i++) {
        float3 q = s + light * stepLen * 0.5;
        optical += volumeDensity(q, volumeHeight(q, camera, p), detail && i < 2, p).y * stepLen;
        s += light * stepLen;
        stepLen *= 1.6;
    }
    float tau = optical * VOLUME_LIGHT_EXTINCTION;
    // Deep in-cloud light: sunlight that has scattered many times decays far slower than single scatter,
    // which keeps thick cumulus and overcast bases grey-white instead of black.
    float deep = 0.35 / (1.0 + 0.03 * tau * 10.0);
    // Multiple scattering: each octave sees less extinction and a broader phase lobe (Wrenninge-style).
    float scatter = 0.0, a = 1.0, b = 1.0, c = 1.0;
    for (int o = 0; o < 3; o++) {
        float phase = lerp(itrpPhase(-0.25 * c, cosTheta), itrpPhase(0.75 * c, cosTheta), 0.65);
        scatter += b * exp(-tau * a) * phase;
        a *= 0.35; b *= 0.55; c *= 0.5;
    }
    scatter += deep;
    // Powder: the sunlit rims of thick puffs read darker than their cores, like real cumulus edges.
    float powder = 1.0 - exp(-d * 12.0);
    scatter *= lerp(1.0, powder * 1.6, 0.5);
    // Bases sit in their own shadow; tops catch more light.
    return scatter * lerp(0.35, 1.0, smoothstep(0.0, 0.7, h)) * (1.0 - 0.6 * p.rain);
}

public static float itrpVolumeTransmittance = 1.0;

// Composites the deck over `background` (sky radiance in reference units, see itrpCloudPlane). Camera rays
// march with detail and a full light march; secondary rays take a cheap low-step estimate.
public float3 itrpVolumeClouds(float3 background, float3 dir, float3 light, float3 sun, float3 sunBeam,
                               float3 skyUp, float3 camera, bool cameraRay, VolumeParams p,
                               out float3 radiance, out float transmittance, out float rawTransmittance) {
    radiance = float3(0.0, 0.0, 0.0);
    transmittance = 1.0;
    rawTransmittance = 1.0;
    float2 span = volumeSpan(camera.y, dir, p);
    if (span.y <= span.x) return background;
    span.y = min(span.y, span.x + VOLUME_MAX_MARCH);

    float vertical = sqrt(abs(dir.y));
    int steps = cameraRay
        ? clamp(int(floor(lerp(20.0, 12.0, saturate(vertical * 1.4 - 0.4)) * p.quality)), 8, 64)
        : 4; // bounce rays: indirect sky light only, averaged by the denoiser (8 -> 4 measured invisible)
    float dt = (span.y - span.x) / float(steps);
    float jitter = volumeHash(dir * 4099.0 + p.time * 13.7);
    float cosTheta = dot(dir, light);
    // Optical depth per step scales with the deck THICKNESS, not the distance the ray travels in it: a
    // near-horizontal ray crosses tens of thousands of blocks of deck, and per-block extinction stacked
    // that into a solid wall that hid the whole sky near the horizon. Clouds read the same at any angle.
    float extinction = VOLUME_VIEW_EXTINCTION * p.density * p.thickness / float(steps);
    float sunGain = 1.2 * p.sunlight;
    float skyGain = 0.9 * p.skylight;
    // Light reaching a cloud's shaded side is mostly light already scattered by the cloud itself plus the
    // whole sky dome, so it is much less blue than the zenith colour. Without this an overcast deck turns
    // sky-blue and disappears against the sky.
    float skyLum = dot(skyUp, float3(0.2126, 0.7152, 0.0722));
    float3 ambientColor = lerp(skyUp, float3(skyLum, skyLum, skyLum), 0.55);

    float3 accum = float3(0.0, 0.0, 0.0);
    float T = 1.0;
    float weightedT = 0.0, weightSum = 0.0;
    for (int i = 0; i < steps; i++) {
        float t = span.x + (float(i) + jitter) * dt;
        float3 pos = camera + dir * t;
        float h = volumeHeight(pos, camera, p);
        float2 d = volumeDensity(pos, h, cameraRay, p);
        if (d.y <= 1e-4) continue;
        float sigma = d.y * extinction;
        float stepT = exp(-sigma);
        // The light march (5 density taps) only where this step visibly contributes: a step adding under
        // 0.1% of the pixel, or one behind 95% opacity (all later steps together add under 5%), takes the
        // cheap height estimate bounce rays use.
        float sun = cameraRay && T > 0.05 && T * (1.0 - stepT) > 0.001
                ? volumeSunScatter(pos, camera, light, cosTheta, d.y, h, p, true)
                : lerp(0.35, 1.0, h) * 0.6 * (1.0 - 0.6 * p.rain);
        float ambient = lerp(0.3, 1.0, sqrt(saturate(h))) * (1.0 - 0.5 * d.x);
        float3 lit = sunBeam * (sun * sunGain) + ambientColor * (ambient * skyGain);
        accum += T * (1.0 - stepT) * lit;
        weightedT += t * T * (1.0 - stepT);
        weightSum += T * (1.0 - stepT);
        T *= stepT;
        if (T < 0.003) break;
    }
    if (weightSum <= 0.0) return background;
    // Aerial fade: far clouds melt into the sky behind them instead of ending in a hard wall.
    float hitDistance = weightedT / weightSum;
    float fade = exp2(-hitDistance * 8e-5);
    transmittance = lerp(1.0, T, fade);
    rawTransmittance = T;
    radiance = accum * fade;
    return background * transmittance + radiance;
}

// Ground/volume shadow from the deck along the light, two shape samples (no erosion): soft penumbrae.
public float itrpVolumeShadow(WorldPush w, float3 worldPos, float3 camera, float3 light) {
    VolumeParams p = itrpVolumeParams(w);
    if (light.y <= 0.001) return 1.0;
    float d = 0.0;
    for (int i = 0; i < 2; i++) {
        float y = p.bottom + p.thickness * (0.25 + 0.25 * float(i));
        float t = (y - worldPos.y) / light.y;
        if (t <= 0.0) continue;
        float3 q = worldPos + light * t;
        d = max(d, volumeDensity(q, volumeHeight(q, camera, p), false, p).y);
    }
    return lerp(0.06, 1.0, exp2(-d * 20.0 * max(p.density, 0.05)));
}
