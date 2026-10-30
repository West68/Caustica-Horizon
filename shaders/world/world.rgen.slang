// Perspective path tracer. invViewProj = inverse(proj * viewRot) maps clip to camera-relative world.
// Primary rays are reconstructed from two unprojected points (near and far plane) rather than assuming
// the eye is at the origin: Minecraft bakes view bobbing into the projection, so the effective eye comes
// from the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
//
// Lighting is an iterative path-tracing loop in raygen, not recursion from closest-hit, so
// maxRayRecursionDepth stays 1. At each surface vertex:
//   * NEE toward the sun — a single analytic directional light, gated by one hit-object visibility query
//     (TerminateOnFirstHit | SkipClosestHit). Lambertian BRDF = albedo/PI.
//   * one Lambertian continuation ray, cosine/cone-guided near torches with its exact PDF weight.
// Sky / ambient light needs no separate AO pass: continuation rays that escape land in world.rmiss,
// which returns sky radiance — so soft sky fill and contact AO both fall out of the path integral.
// Russian roulette terminates deep paths. Output is HDR (R16G16B16A16_SFLOAT); the HDR -> LDR tonemap
// happens later at the display.comp seam.
//
// SER: the build emits EXT and NV shader-invocation-reorder variants from this source.
//
// Each frame emits a single SPP-averaged (still noisy) estimate; temporal convergence is the denoiser's
// job. With RR disabled the output is the raw noisy path trace — a reference view, not a converged image.
import world_common;
import glass_optics;
import radiance_cache;
import mirror_guides;
import environment_effects;
import end_environment;
import itrp_clouds;
import itrp_atmosphere;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(0, 0)]] RaytracingAccelerationStructure topLevelAS;
// HDR trace target: linear radiance, may exceed 1. Tonemap happens later at the display.comp seam.
[[vk::binding(1, 0)]] [format("rgba16f")] RWTexture2D<float4> outImage;
// Guide buffers — first-hit (primary-visibility) attributes consumed by the denoiser/DLSS-RR. Written
// every frame regardless of accumulation. gNormal.w carries roughness; gDepth carries HW reversed-Z depth.
[[vk::binding(3, 0)]] [format("rgba16f")] RWTexture2D<float4> gNormal;     // xyz world normal, w roughness
[[vk::binding(4, 0)]] [format("rgba16f")] RWTexture2D<float4> gAlbedo;     // rgb diffuse albedo
[[vk::binding(5, 0)]] [format("r32f")]    RWTexture2D<float>  gDepth;      // HW reversed-Z depth
[[vk::binding(6, 0)]] [format("rg16f")]   RWTexture2D<float2> gMotion;     // screen-space motion (render px)
[[vk::binding(7, 0)]] [format("rgba16f")] RWTexture2D<float4> gSpecAlbedo; // specular albedo (0 — diffuse-only)
[[vk::binding(8, 0)]] [format("rg16f")]   RWTexture2D<float2> gSpecMotion; // reflection MVs for RR

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `worldPush` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
static WorldPush worldPush;

// Ray payloads. Module-level statics so the helper functions (visibility / refractedGuideHit /
// specularReflectionMotion / tracePath) can share them, mirroring the GLSL rayPayloadEXT globals.
static Payload payload;
static bool gv_farLod;
#ifdef CAUSTICA_DH
[[vk::binding(0, 1)]] Sampler2D dhInputTextures[];
static bool dhCandidate;
static float3 dhCandidatePosition,dhCandidateColor,dhCandidateNormal;
static float dhCandidateAlpha;
#endif
static float4 shadowVis; // rgb = transmittance; a = nearest water-crossing t (-1 = none)

// First-hit (primary-visibility) guide attributes, captured at bounce 0 of tracePath. The primary ray
// is deterministic (no AA jitter yet), so every SPP sample's bounce 0 yields identical values.
static float3 gv_normal;
static float3 gv_albedo;
static float3 gv_specAlb; // specular albedo fed to DLSS-RR for specular demodulation (0 = pure diffuse).
static float gv_rough;
static float gv_emission;
static uint gv_emissionSource;
// Linear VIEW-SPACE depth guide inputs; sky resolves to a large far value (no negative sentinel).
static float gv_depth;
static float3 gv_hitCamRel;       // primary-surface hit position relative to the current camera
static float3 gv_motionHitCamRel; // opaque/particle guide position, or refracted content for albedo
static bool gv_motionUseRefracted; // primary water has a refracted content guide
// World-space displacement of the motion-guide surface since the previous frame (0 for static
// terrain/sky, an entity's per-vertex/object motion for a dynamic hit).
static float3 gv_motionObjDisp;

uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
uint payloadEmissionSource() {
    return (payload.flags >> PAYLOAD_EMISSION_SOURCE_SHIFT) & PAYLOAD_EMISSION_SOURCE_MASK;
}
// Set by world.rchit only on a MATERIAL_WATER hit (see PAYLOAD_WATER_ENTERING) — whether the ray was
// travelling into the water volume (vs. out of it) at this hit's face.
bool payloadWaterEntering() { return (payload.flags & PAYLOAD_WATER_ENTERING) != 0u; }
float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }
float payloadEmission() { return unpackHalf2(payload.emissionSss).x; }
float payloadSss() { return unpackHalf2(payload.emissionSss).y; }
float payloadIor() { return unpackHalf2(payload.iorTransmission & ~FIRST_PERSON_RECEIVER).x; }
float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }
void payloadSetTraceState(bool showCelestial, float rayConeWidth, float rayConeSpread) {
    payload.flags = showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u;
    payload.rayCone = packHalf2(float2(rayConeWidth, rayConeSpread));
}

static const float PI = 3.14159265359;
static const float INV_PI = 0.31830988618;

// The analytic sky (gradient + sun/moon discs + stars) is computed in world.rmiss. On a miss, raygen
// reads payload.albedo and accumulates that sky radiance. The directional NEE light is pushed separately
// as worldPush.lightDir / worldPush.lightRadiance / worldPush.sunDir.
static const float EMISSIVE_STRENGTH = 5.0; // HDR radiance of a full (level-15) emitter, modulated by albedo
static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
static const float WATER_IOR = 1.333;
static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Stained glass / ice (material 3): a thin colored dielectric. IOR ~1.5 (soda-lime glass).
// Transmitted-ray origin offset when crossing a glass face. Must be SMALLER than the terrain-side
// TRANSLUCENT_INSET (RtTerrain, 2e-4 blocks): glass is recessed into its block by that inset, so a face
// touching a slab/stair leaves a tiny gap before the neighbour's surface. A large bias (SURF_BIAS) would
// restart the ray past that neighbour and see through it; this lands the ray inside the gap so the
// neighbour is still hit, while still clearing the glass surface to avoid self-intersection.
static const float GLASS_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in payload.albedo for
// water hits, or worldPush.waterParams.xyz for the camera's own biome when starting submerged). A blue ocean
// tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.
static const float WATER_DENSITY = 0.05;
static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
float3 waterExtinction(float3 tint) {
    if((worldPush.flags & 512u)!=0u)return float3(.25,.04,.01);
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays geometrically
// flat; only the normal tilts). Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see applyWaterWaves) so the pattern is pinned in world space, and the
// caustics (waterCaustic) differentiate this same field, so they stay in sync with the visible ripples.
static const float WATER_WAVE_STRENGTH = 0.3;  // slope scale (higher = choppier glints / more broken reflections)
static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
static const float WAVE_G = 9.81;
static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components
float2 waterWaveGrad(float2 p, float t,float localRain) {
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
    float2 g = float2(0.0, 0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // Heading: deterministic irregular spread about the wind, widening down the spectrum.
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        if (i < 4) { // meander only the components whose fronts are long enough to read as lines
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float e = exp(S * (sin(ph) - 1.0));
        g += (Q[i] / k) * (S * cos(ph) * e) * dph; // a = Q/k; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        lambda /= 1.5;
    }
    // ITRP look: WaterWaves.glsl scales the whole wave field by a slow ~400-block noise,
    // (saturate(bw*2-0.7)*0.7+0.3), so a lake reads as calm stretches and rougher bands. The noise is
    // emulated by three incommensurate low-frequency sines drifting like its waveTime*0.01 term.
    if ((worldPush.flags & 512u) != 0u) {
        float2 q = p * 0.0025 + t * float2(-0.0009, -0.0003);
        float bw = 0.5 + 0.5 * (0.5 * sin(q.x * 6.2832 + 1.3) * cos(q.y * 5.1 + 0.4)
                + 0.3 * sin((q.x * 0.8 - q.y) * 9.7 + 2.1) + 0.2 * sin((q.x + q.y * 1.3) * 15.3 + 4.0));
        g *= saturate(bw * 2.0 - 0.7) * 0.7 + 0.3;
    }
    return g * WATER_WAVE_STRENGTH + rainRippleGradient(p,t/WAVE_SPEED,localRain);
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t,float localRain) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t,localRain);
    float side=nGeo.y>=0.0?1.0:-1.0;
    float3 up=nGeo*side;
    float2 authoredSlope=-up.xz/max(up.y,.05);
    up=normalize(float3(-authoredSlope.x-grad.x,1.0,-authoredSlope.y-grad.y));
    return side*up;
}

// ---- Water caustics. Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true focusing factor computable
// instead of faked with a scrolling texture. Where the shadow ray of an underwater NEE vertex crossed
// water (recorded by world.rahit in shadowVis.a), evaluate the horizontal landing position of the
// refracted sun ray as a function of surface position and finite-difference it: the caustic intensity is
// the inverse Jacobian determinant of that surface→floor mapping (area compression = brightening, real
// fold caustics where det → 0). Because this uses the SAME wave field as the visible surface normals,
// the caustic pattern stays in sync with the ripples, and the per-sample sun-quad jitter (sampleSquare)
// shifts the pattern per sample → caustics physically blur with depth under DLSS-RR accumulation.
static const float CAUSTIC_EPS = 0.25; // finite-difference step (m); also low-passes sub-step wave detail
static const float CAUSTIC_MAX = 6.0;  // focusing clamp: keeps fold-line singularities from becoming fireflies

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz, intersected
// with the receiver plane h metres below. inc = sunlight travel direction (downward, unit).
float2 causticLanding(float2 xz, float t, float3 inc, float h) {
    float2 g = waterWaveGrad(xz, t,worldPush.mediumParams.y);
    float3 nw = normalize(float3(-g.x, 1.0, -g.y));
    float3 rdw = refract(inc, nw, 1.0 / WATER_IOR); // air→water: never TIR
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

float waterCaustic(float3 exitPos, float3 lightDir, float waterDist) {
    // Grazing light: the receiver-plane intersection degenerates and caustics vanish in reality anyway
    // (long slant paths absorb + the pattern smears out) — fade the effect to neutral 1.0.
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return 1.0;
    float h = max(waterDist * lightDir.y, 0.05); // vertical depth of the receiver below the exit point
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + worldPush.waterAnchor.xy;  // world-stable wave domain (same as applyWaterWaves)
    float2 p0 = causticLanding(base, t, inc, h);
    float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h);
    float2 pz = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    // Flat water ⇒ all landings displace identically ⇒ det = eps² ⇒ focus = 1 (energy-neutral); the
    // clamp bounds the fold-line spikes whose energy the surrounding darkening already paid for.
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    return lerp(1.0, min(focus, CAUSTIC_MAX), fade);
}

// GGX BRDF. Roughness is clamped away from 0 so the delta sun's specular highlight stays finite
// and the VNDF sample is well-conditioned. alpha = roughness^2 (perceptual -> GGX) throughout.
static const float MIN_ROUGH = 0.045;

float luminance(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// GGX normal distribution.
float ggxD(float ndh, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
float ggxG1(float ndx, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

float3 fresnelSchlick(float cosT, float3 f0) {
    // Explicit zero reflectivity is an index-matched interface. Schlick's grazing limit is invalid here.
    if(max(f0.r,max(f0.g,f0.b))<=0.0)return float3(0);
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
static const int   MAX_SSS_BOUNCE = 1; // fire the transmission shadow ray on primary + first indirect hit only
float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (perceptual roughness squared), NoV is the first-hit view cosine.
float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    if(max(specularColor.r,max(specularColor.g,specularColor.b))<=0.0)return float3(0);
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG hash RNG.
uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
float rndf(inout uint s) {
    // High 24 bits are exact in float; converting all 32 lets the top values round to 1.0 and break
    // `< probability` tests (upstream Caustica #26).
    return float(pcg(s) >> 8u) * (1.0 / 16777216.0);
}

// Digitally shifted base-2 radical inverse: every pixel sees the same evenly distributed
// Fresnel decisions over time, with a different fixed ordering per pixel. This changes neither
// the path probability nor its throughput; it only avoids long random reflection streaks at 1 SPP.
float glassFresnelSample(uint2 pixel, uint ordinal) {
    uint scramble = pixel.x * 0x9E3779B9u ^ pixel.y * 0x85EBCA6Bu;
    // Use pcg's HASH, not its advanced LCG state: reversebits makes the state's lowest bits the most
    // significant, and an LCG's low bits are nearly periodic, so neighbouring pixels made the same
    // reflect/refract choice in regular stripes that RR smeared into streaks across slime and glass.
    scramble = pcg(scramble);
    return float(reversebits(ordinal ^ scramble) >> 8u) * (1.0 / 16777216.0); // never rounds up to 1.0
}

float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
float3 cosineDir(float3 n, inout uint s) {
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// Importance-guide an EXISTING diffuse continuation ray; never add another ray or a light term.
// The 50% cosine component covers the entire upper hemisphere, including emitters absent from the grid.
// Multiplying by (cos/PI)/q preserves the original Lambertian integral and bounds the weight by two.
float3 torchGuidedDiffuse(float3 p, float3 n, inout uint seed, out float weight) {
    weight = 1.0;
    if (worldPush.torchGuideAddr == 0) return cosineDir(n, seed);
    // The grid's 4-block cells switch the sampling strategy at their faces: inside a cell with a hint
    // half the rays aim at its emitter, next door none do. Each is unbiased on its own, but cache records
    // and the denoiser do not treat both noise shapes alike, so every cell face showed as a hard straight
    // line across floors and walls. Pick the cell from a point jittered by up to half a cell: the strategy
    // now blends across each face, and the estimate stays unbiased because the hint is chosen
    // independently of the direction and the weight below uses that same hint.
    float3 jitter = (float3(rndf(seed), rndf(seed), rndf(seed)) - 0.5) * worldPush.torchGuideOrigin.w;
    int3 cell = int3(floor((p + jitter - worldPush.torchGuideOrigin.xyz) / worldPush.torchGuideOrigin.w));
    if (any(cell < 0) || any(cell >= 16)) return cosineDir(n, seed);
    uint index = uint((cell.z * 16 + cell.y) * 16 + cell.x);
    float4 hint = ((ConstPtr<float4>)worldPush.torchGuideAddr)[index];
    if (hint.w == 0.0) return cosineDir(n, seed);
    float3 delta = worldPush.torchGuideOrigin.xyz + hint.xyz - p;
    float d2 = dot(delta, delta);
    float r2 = hint.w * hint.w;
    if (d2 <= r2) return cosineDir(n, seed);
    float3 axis = delta * rsqrt(d2);
    float cosMax = sqrt(max(0.0, 1.0 - r2 / d2));
    // Stable 1-cos(theta) (avoids catastrophic cancellation for a small distant emitter).
    float oneMinusCos = (r2 / d2) / (1.0 + cosMax);
    float3 direction;
    if (rndf(seed) < 0.5) {
        float c = 1.0 - rndf(seed) * oneMinusCos;
        float phi = 6.28318530718 * rndf(seed);
        float3 tangent = normalize(cross(axis, abs(axis.y) < 0.9 ? float3(0,1,0) : float3(1,0,0)));
        float3 bitangent = cross(axis, tangent);
        float s = sqrt(max(0.0, 1.0 - c * c));
        direction = normalize(axis * c + s * (cos(phi) * tangent + sin(phi) * bitangent));
    } else direction = cosineDir(n, seed);
    float cosinePdf = max(0.0, dot(n, direction)) / 3.14159265359;
    float conePdf = dot(axis, direction) >= cosMax ? 1.0 / (6.28318530718 * oneMinusCos) : 0.0;
    float pdf = 0.5 * (cosinePdf + conePdf);
    weight = pdf > 0.0 ? cosinePdf / pdf : 0.0;
    return direction;
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's
// sun/moon are square quads, so the NEE shadow ray samples a square (not a cone) — the same square the
// visible disc in world.rmiss uses, built from the same celestial tangent frame (right = arc-travel
// direction, from worldPush.celestial.xyz). Averaged over frames by DLSS-RR this yields soft penumbrae that
// widen with occluder distance (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    float3 right = normalize(cross(axis, worldPush.celestial.xyz));
    float3 up = cross(right, axis);
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. alpha = rough^2. Paired with the separable
// Smith term, the importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
float3 sampleGGXVNDF(float3 n, float3 ve, float rough, inout uint s) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = rough * rough;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
// receiver billboards only. The first-person camera owner uses 0x01 (secondary only).
static const uint CULL_SECONDARY = 0x01u;
static const uint CULL_PRIMARY = 0x02u;
// Ordinary scene instances carry this bit; the camera owner's first-person body (mask=1) does not.
static const uint CULL_HAND_WORLD = 0x08u;
// Particles and rain (mask 0x12) seen through glass or water. They are camera-facing billboards, so a
// reflection would show them edge-on and turning with the camera: mirrors, GI and shadows skip them.
static const uint CULL_SPECULAR_PARTICLES = 0x10u;
static bool throughTransmission = false; // the next ray continues straight through glass or water
static uint secondaryReceiverMask = CULL_SECONDARY;
static bool firstPersonReceiver = false;
static const uint TERRAIN_BUCKETS = 4u;
static const uint SBT_RADIANCE = 0u;
static const uint SBT_SHADOW = TERRAIN_BUCKETS;
static const uint SBT_STRIDE_BUCKET = 1u;

RayDesc makeRay(float3 origin, float tmin, float3 dir, float tmax) {
    RayDesc r;
    r.Origin = origin;
    r.TMin = tmin;
    r.Direction = dir;
    r.TMax = tmax;
    return r;
}

// SER radiance trace: trace into a hit object, reorder threads by hit coherence, THEN invoke the
// hit/miss shader — this is where the divergent Section/Prim shading work (world.rchit/world.rahit)
// actually happens, so reordering before it is what pays for the reorder's own cost.
// Only the two trace-input words (flags, ray cone) span the reorder: traversal runs world.rahit alone,
// which never writes a radiance payload, so the payload is rebuilt on each side instead of being saved
// and restored around ReorderThread (upstream Caustica #27, ~60 fewer load/stores per radiance trace).
// Every closest-hit/miss path writes the fields its consumers read; the rest start zeroed, not stale.
Payload makeRadiancePayload(uint flags, uint rayCone) {
    Payload p = makeShadowPayload();
    p.albedo = float3(0.0, 0.0, 0.0);
    p.flags = flags;
    p.rayCone = rayCone;
    return p;
}

HitObject traceRadianceReordered(uint cullMask, float3 ro, float tmin, float3 rd, float tmax) {
    uint traceFlags = payload.flags;
    uint traceCone = payload.rayCone;
    Payload tracePayload = makeRadiancePayload(traceFlags, traceCone);
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, makeRay(ro, tmin, rd, tmax), tracePayload);
#if !defined(CAUSTICA_DISABLE_REORDER)
    // Optional coherence hint (cacheFlags bit 2): also group by path stage (primary / indirect, specular
    // or diffuse continuation), whose raygen shading after the hit differs.
    if ((worldPush.cacheFlags & 4u) != 0u) ReorderThread(hObj, ser_hint, 2u);
    else ReorderThread(hObj);
#endif
    payload = makeRadiancePayload(traceFlags, traceCone);
    HitObject::Invoke(topLevelAS, hObj, payload);
    return hObj;
}

// Invoke the SAME receiver hit with a visibility-only payload: no TraceRay, no new pass,
// no extra BVH traversal, and no cross-stage payload expansion. Original ray/cone/classification
// reproduce the same height hit; only the proposed outgoing direction changes.
float outgoingReliefVisibility(HitObject receiver, float3 direction, uint cone, uint cameraFlag, float depth) {
    Payload query=(Payload)0;
    query.flags=PAYLOAD_RELIEF_QUERY | cameraFlag;
    query.normal=direction;
    query.rayCone=cone;
    query.hitT=depth; // full precision displaced depth from the ordinary hit; no second smooth search
    query.albedo=float3(1.0);
    HitObject::Invoke(topLevelAS,receiver,query);
    return saturate(query.albedo.x);
}

// Vulkan requires one payload structure for every stage a trace can reach, so shadow rays carry the full
// Payload: albedo = accumulated transmittance, hitT = nearest water crossing (-1 = none). shadowVis keeps
// that result for the caustic/medium code below.
Payload makeShadowPayload() {
    Payload p;
    p.albedo = float3(1.0, 1.0, 1.0);
    p.hitT = -1.0;
    p.normal = float3(0.0, 0.0, 0.0);
    p.motionPrev = float3(0.0, 0.0, 0.0);
    p.f0 = float3(0.0, 0.0, 0.0);
    p.flags = 0u;
    p.roughMetal = 0u;
    p.emissionSss = 0u;
    p.iorTransmission = 0u;
    p.rayCone = 0u;
    return p;
}

float3 visibilityMasked(float3 origin, float3 dir, float tmax,uint mask) {
    Payload shadowPayload = makeShadowPayload();
    // Shadow SBT records run any-hit only for cutout/translucent/water. Cutout alpha-tests; translucent
    // and water tint shadowVis.rgb and pass through. Solid blocks terminate traversal. There is no closest
    // or miss shader worth executing, so use a hit object only as the traversal result.
    HitObject hObj = HitObject::TraceRay(topLevelAS,
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            mask, SBT_SHADOW, SBT_STRIDE_BUCKET, 0u,
            makeRay(origin, RAY_TMIN, dir, tmax), shadowPayload);
    shadowVis = float4(shadowPayload.albedo, shadowPayload.hitT);
#ifdef CAUSTICA_DH
    // Only far terrain absent from the exact near RT scene; no second hardware visibility query.
    if(hObj.IsMiss() && (mask==CULL_SECONDARY || mask==CULL_HAND_WORLD)) {
        float distance,alpha;float3 color;
        if(dhSecondarySurface(origin,dir,tmax,distance,color,alpha))return shadowVis.rgb*(1.0-alpha);
    }
#endif
    return hObj.IsMiss() ? shadowVis.rgb : float3(0.0, 0.0, 0.0);
}

// Short opaque probe for the sunlight rims: cutouts count as solid (no any-hit), no DH fallback.
bool probeHit(float3 origin, float3 dir, float tmax) {
    Payload shadowPayload = makeShadowPayload();
    HitObject hObj = HitObject::TraceRay(topLevelAS,
            RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            secondaryReceiverMask, SBT_SHADOW, SBT_STRIDE_BUCKET, 0u, makeRay(origin, RAY_TMIN, dir, tmax), shadowPayload);
    return !hObj.IsMiss();
}

static float pathCloudShadow=-1.0;
float3 visibility(float3 origin,float3 dir,float tmax) {
    float3 vis=visibilityMasked(origin,dir,tmax,secondaryReceiverMask);
    // An occluded sample stays 0 whatever the clouds do: skip the cloud-deck noise lookups for it.
    if(!any(vis>0.0))return vis;
    // The cloud deck is far above and soft, so the path's first lookup also stands for its later vertices.
    if(pathCloudShadow<0.0)pathCloudShadow=itrpCloudShadow(worldPush,origin,dir);
    return vis*pathCloudShadow;
}

// Clear glass reflection without a stochastic branch: one shaded hit along the mirror direction,
// lit by emission, the unjittered sun/moon with a hard shadow ray and a small sky ambient; a miss
// returns the sky radiance. Deterministic, so DLSS-RR sees no reflect/refract coin flip. Clobbers
// the path payload, which the next bounce re-initialises before tracing.
float3 glassReflection(float3 origin, float3 dir, bool shade) {
    payload.hitT = -1.0;
    payloadSetTraceState(true, RAY_CONE_MIN_WIDTH, RAY_CONE_MIN_SPREAD);
    if (!shade) {
        // Near-normal view (F of a few percent): a reflected building would add ~4% to the pane, so
        // only the far brighter sky is resolved, without hit shading or a shadow ray.
        HitObject hObj = HitObject::TraceRay(topLevelAS,
                RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
                secondaryReceiverMask, SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u,
                makeRay(origin, RAY_TMIN, dir, 10000.0), payload);
        if (!hObj.IsMiss()) return float3(0.0, 0.0, 0.0);
        HitObject::Invoke(topLevelAS, hObj, payload);
        return max(payload.albedo, float3(0.0, 0.0, 0.0));
    }
    // A lone side ray: a thread reorder here would cost more than the coherence it buys.
    HitObject reflectedHit = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, secondaryReceiverMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, makeRay(origin, RAY_TMIN, dir, 10000.0), payload);
    HitObject::Invoke(topLevelAS, reflectedHit, payload);
    if (payload.hitT < 0.0) return max(payload.albedo, float3(0.0, 0.0, 0.0));
    float3 albedo = max(payload.albedo, float3(0.0, 0.0, 0.0));
    float3 n = payload.normal;
    float3 p = origin + dir * payload.hitT + n * SURF_BIAS;
    float3 radiance = albedo * payloadEmission() * EMISSIVE_STRENGTH * worldPush.visualLight.x;
    float3 lightDir = worldPush.lightDir.xyz;
    float ndl = max(0.0, dot(n, lightDir));
    float3 sun = worldPush.lightRadiance.xyz;
    if (ndl > 0.0) radiance += albedo * INV_PI * sun * ndl * visibility(p, lightDir, 10000.0);
    radiance += albedo * sun * (0.04 * (0.6 + 0.4 * n.y));
    return radiance;
}

float3 heldLighting(float3 hitPos,float3 n,float3 v,float3 diffAlb,float3 F0,float rough,
        bool pbr,float2 localVisibility,bool inWater,float3 extinction,inout uint seed) {
    if((payload.flags & PAYLOAD_HELD_OWNER)!=0u)return float3(0,0,0);
    float weight0=luminance(worldPush.mainLightColor.xyz)*heldFalloff(length(worldPush.mainLightPosition.xyz-hitPos),worldPush.mainLightPosition.w);
    float weight1=luminance(worldPush.offLightColor.xyz)*heldFalloff(length(worldPush.offLightPosition.xyz-hitPos),worldPush.offLightPosition.w);
    float total=weight0+weight1;
    if(total<=0)return float3(0,0,0);
    bool main=rndf(seed)*total<weight0;
    float probability=(main?weight0:weight1)/total;
    float4 lp=main?worldPush.mainLightPosition:worldPush.offLightPosition;
    // Shared radius in main.w, ITRP shadow floor in off.w; no push-layout expansion.
    float sourceRadius=worldPush.mainLightColor.w;
    if(sourceRadius>0) {
        float z=rndf(seed)*2-1,angle=rndf(seed)*6.28318530718;
        float radial=sqrt(max(1-z*z,0.0)),radius=sourceRadius*pow(rndf(seed),1.0/3.0);
        lp.xyz+=radius*float3(radial*cos(angle),z,radial*sin(angle));
    }
    float3 delta=lp.xyz-hitPos;float distance=length(delta);
    float attenuation=heldFalloff(distance,lp.w);
    if(attenuation<=0 || distance<=0.01)return float3(0,0,0);
    float3 ld=delta/distance;float cosine=max(dot(n,ld),0.0);
    if(cosine<=0)return float3(0,0,0);
    float3 vis=visibilityMasked(hitPos+n*SURF_BIAS,normalize(lp.xyz-(hitPos+n*SURF_BIAS)),max(length(lp.xyz-(hitPos+n*SURF_BIAS))-.002,RAY_TMIN),4u);
    // ITRP HeldLight.glsl: shadow * 0.85 + 0.15. Zero restores full physical occlusion.
    vis=lerp(float3(worldPush.offLightColor.w),float3(1.0),vis*(main?localVisibility.x:localVisibility.y));
    if(inWater)vis*=exp(-extinction*min(distance,shadowVis.a>0?shadowVis.a:distance));
    float3 brdf=diffAlb*INV_PI;
    if(pbr) {
        float3 sum=ld+v;float3 halfDir=sum/max(length(sum),1e-6);float nv=max(dot(n,v),1e-4);
        float D=ggxD(max(dot(n,halfDir),0.0),rough);
        float G=ggxG1(nv,rough)*ggxG1(cosine,rough);
        brdf+=D*G*fresnelSchlick(max(dot(v,halfDir),0.0),F0)/(4*nv*cosine);
    }
    float3 intensity=main?worldPush.mainLightColor.xyz:worldPush.offLightColor.xyz;
    return brdf*intensity*(attenuation*cosine/max(probability,1e-20))*vis;
}

// One point-light/celestial sample of single scattering, with categorical PDF compensation.
// This is a bounded visual approximation, not the reference's multiple-scattering volume integrator.
float3 volumeLighting(float3 position,float3 viewRay,float g,float3 extinction,bool water,inout uint seed) {
    float a=luminance(worldPush.lightRadiance.xyz)*hgPhase(dot(viewRay,worldPush.lightDir.xyz),g);
    float3 toMain=worldPush.mainLightPosition.xyz-position,toOff=worldPush.offLightPosition.xyz-position;
    float b=luminance(worldPush.mainLightColor.xyz)*heldFalloff(length(toMain),worldPush.mainLightPosition.w)
            *hgPhase(dot(viewRay,toMain/max(length(toMain),1e-5)),g);
    float c=luminance(worldPush.offLightColor.xyz)*heldFalloff(length(toOff),worldPush.offLightPosition.w)
            *hgPhase(dot(viewRay,toOff/max(length(toOff),1e-5)),g);
    float total=a+b+c;if(total<=0)return float3(0,0,0);
    float choose=rndf(seed)*total;
    float3 ld=worldPush.lightDir.xyz,intensity=worldPush.lightRadiance.xyz;
    float probability=a/total,distance=10000.0,attenuation=1.0;
    if(choose>=a) {
        bool main=choose<a+b;
        float4 lp=main?worldPush.mainLightPosition:worldPush.offLightPosition;
        float3 delta=lp.xyz-position;distance=length(delta);
        attenuation=heldFalloff(distance,lp.w);
        if(distance<0.01 || attenuation<=0)return float3(0,0,0);
        ld=delta/distance;intensity=main?worldPush.mainLightColor.xyz:worldPush.offLightColor.xyz;
        probability=(main?b:c)/total;
    }
    float3 vis=visibilityMasked(position,ld,max(distance-.002,RAY_TMIN),choose>=a?4u:secondaryReceiverMask);
    if(choose<a && any(vis>0.0))vis*=itrpCloudShadow(worldPush,position,ld);
    float mediumDistance=water?min(distance,shadowVis.a>0?shadowVis.a:32.0):min(distance,128.0);
    return intensity*(attenuation*hgPhase(dot(viewRay,ld),g)/max(probability,1e-20))
            *exp(-extinction*mediumDistance)*vis;
}

void transportMedium(float3 ro,float3 rd,float distance,bool water,bool scatter,
        float3 waterAbsorb,inout float3 throughput,inout float3 radiance,inout uint seed) {
    if(distance<=0)return;
    float dim=worldPush.environment.x;
    float rain=worldPush.mediumParams.y;
    // End fog is a directional finite-column source, not the old uniform purple HDR fill.
    if(!water && dim==2.0) {
        if(scatter)radiance+=throughput*endFogRadiance(distance,rd,worldPush.waterParams.w*15.0/max(worldPush.dimensionControl.x,1.0))*worldPush.visualLight.w*worldPush.environment.z;
        return;
    }
    if(!water && dim==1.0 && worldPush.mediumParams.w<0.5) {
        if(scatter) {
            float factor=1-exp2(-distance*worldPush.dimensionFog.w*worldPush.environment.z);
            factor=saturate(factor*factor);
            radiance+=throughput*worldPush.dimensionFog.xyz*worldPush.dimensionControl.y*factor;
            throughput*=1-factor;
        }
        return;
    }
    float sigma=0;
    if(water)sigma=.0075*worldPush.mediumParams.x;
    else {
        if(worldPush.environment.z<=0)return;
        float absoluteY=ro.y+rd.y*distance*.5+worldPush.cloudWorld.y-worldPush.camOffset.y;
        float height=exp(-max(absoluteY-worldPush.mediumParams.z,0.0)/64.0);
        sigma=worldPush.mediumParams.w>0.5 ? 4.0 : ((dim==1 || dim==2)?worldPush.dimensionFog.w:(.0006+.002*rain)*(.25+.75*height))*worldPush.environment.z;
    }
    float3 extinction=water ? waterAbsorb+sigma : float3(sigma,sigma,sigma);
    float3 transmit=exp(-extinction*distance);
    if(scatter && sigma>0) {
        // Truncated exponential distance sampling cancels homogeneous transmittance variance.
        // Thin segments use a uniform proposal with its own exact PDF, avoiding cancellation near zero.
        float rate=max(min(extinction.x,min(extinction.y,extinction.z)),1e-6);
        float u=rndf(seed),t=u*distance,pdf=1.0/distance;
        if(rate*distance>=0.001) {
            float norm=1.0-exp(-rate*distance);
            t=-log(max(1.0-u*norm,1e-20))/rate;
            pdf=rate*exp(-rate*t)/norm;
        }
        float3 source=worldPush.mediumParams.w>0.5?float3(0,0,0):volumeLighting(ro+rd*t,rd,(worldPush.flags & 512u)!=0u?(water?.65:.8):(water?.45:.65),extinction,water,seed);
        // Skylight access alone is not irradiance: it remains 15 at midnight.
        // Do not leave a day-strength constant water-scattering source after the sun sets.
        float waterSky=lerp(.002,1.0,saturate(worldPush.sunDir.w))*worldPush.environment.w;
        float3 incoming=water ? ((worldPush.flags & 512u)!=0u?float3(.4,.41,.5):worldPush.waterParams.xyz)*(.025*waterSky) :
                worldPush.mediumParams.w>0.5?float3(.8,.06,.003):(dim==1||dim==2)?worldPush.dimensionFog.xyz:float3(0,0,0);
        // Direct term: exp(-sigma_t*t)*sigma_s*Li / p(t).
        radiance+=throughput*(exp(-extinction*t)*source*(sigma/max(pdf,1e-20))
                +(1.0-transmit)*incoming*(water?.8:1.0));
    }
    throughput*=transmit;
    if(!water && dim==0.0 && scatter && (worldPush.flags & 512u)!=0u) {
        // Primary finite surface path only; sky already carries the complete atmosphere.
        // Reference land haze precedes VFOG, so its source uses the post-VFOG throughput.
        float landDistance=distance*pow(saturate(worldPush.environment.w),6.0)*worldPush.environment.z;
        float depth=landDistance*lerp(.005,.015,rain);
        float gate=1.0-exp2(-landDistance*.02);
        float3 haze=itrpReferenceAtmosphere(rd,worldPush.sunDir.xyz,rain,depth)
                *(ITRP_SCENE_SKY_SCALE*.02*gate*gate);
        radiance+=throughput*haze;
        throughput*=itrpLandTransmission(rd,landDistance*.005,rain);
    }
}

// Never feed NaN/Inf or a point behind the previous camera to RR/FG history.
// RG16F overflow is also invalid: off-screen finite vectors remain valid up to its actual limit.
bool validMotionClip(float4 clip) {
    return all(isfinite(clip)) && clip.w > 1.0e-5;
}
float2 checkedMotion(float4 previousClip, float2 currentNdc, float2 size, float2 fallback) {
    if (!validMotionClip(previousClip)) return fallback;
    float2 motion = (previousClip.xy / previousClip.w - currentNdc) * 0.5 * size;
    return all(isfinite(motion)) && all(abs(motion) <= 65504.0) ? motion : fallback;
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P seen
// in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye sees V
// along a straight line, so the reflection appears at proj(V). Project the mirror image directly.
// Gotcha: intersecting a reflection ray with the surface plane divides by dot(mirrorDir, n), which
// explodes at grazing angles and produces non-zero MV in a static scene. Mirroring is division-free, so
// static MV is bit-exact zero at any incidence. The reflector is assumed static (terrain / water);
// reflectedMotionPrev carries the reflected content's own displacement.
float4 previousReflectionClip(float3 surfacePos, float3 surfaceNormal, float3 reflectedWorldPos, float3 reflectedMotionPrev) {
    float3 n = normalize(surfaceNormal); // mirror(P) is orientation-independent, so no viewer flip needed
    float3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    float3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - surfacePos, n);
    return mul(worldPush.prevViewProj, float4(mirroredHit - worldPush.camOffset + worldPush.camDelta, 1.0));
}

// Reflection (specular) motion vector for DLSS-RR. Covers smooth specular blocks (metals / LabPBR _s)
// and water reflections including underwater total-internal-reflection — any primary surface with a
// sharp specular lobe (specular albedo > 0, roughness <= 0.5). A sky miss is treated as a reflected
// hit at infinity along the reflected direction, so the same mirror-image reprojection handles surface
// and sky reflections uniformly (continuous as the reflected distance -> infinity).
float2 specularReflectionMotion(float3 surfacePos, float3 primaryDir, float2 currentNdc, float2 size, float primaryConeSpread, float2 surfaceMotion) {
    if (length(gv_normal) < 0.5 || max(gv_specAlb.r, max(gv_specAlb.g, gv_specAlb.b)) <= 0.001 || gv_rough > 0.5) {
        // Dense field: rough surfaces still move with the primary surface/camera.
        return surfaceMotion;
    }

    float3 n = normalize(gv_normal);
    float3 specDir = reflect(primaryDir, n);
    // The bias is ONLY for the trace origin (so the reflection ray doesn't self-intersect the surface it
    // leaves). The hit is then reconstructed from the TRUE surface point `surfacePos`, NOT the biased
    // origin: reconstructing from `surfacePos + n*SURF_BIAS` bakes a constant `-n*SURF_BIAS` world offset
    // into the mirrored virtual point, and that offset's screen projection blows up at grazing angles (n
    // lies in the screen plane there) -> a non-zero MV in a static scene. Reconstructing from surfacePos
    // cancels the term so the mirror lands exactly on the view ray (static MV = bit-exact zero) WITHOUT
    // zeroing the trace bias the main path tracer needs to avoid surface acne.
    payload.hitT = -1.0;
    payloadSetTraceState(false,
            max(length(surfacePos - worldPush.camOffset) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    // Same intersection/any-hit/opacity work, but only distance + displacement are consumed below.
    if ((worldPush.flags & 16384u) != 0u) payload.flags |= PAYLOAD_REFLECTION_GUIDE;
    traceRadianceReordered(firstPersonReceiver ? CULL_HAND_WORLD : CULL_SECONDARY, surfacePos + n * SURF_BIAS, RAY_TMIN, specDir, 10000.0);

#ifdef CAUSTICA_DH
    if(payload.hitT<0.0) {
        float distance,alpha;float3 color;
        if(dhSecondarySurface(surfacePos+n*SURF_BIAS,specDir,10000.0,distance,color,alpha) && alpha>=.5) {
            payload.hitT=distance;payload.motionPrev=float3(0);
        }
    }
#endif
    float3 reflectedHit;
    float3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = surfacePos + specDir * payload.hitT;
        // Reflected water/fluid is a static dielectric (its chit MV lane is repurposed); only opaque
        // geometry carries a real per-vertex displacement. Particles are off secondary rays (cull mask).
        reflectedMotionPrev = payloadMaterial() == MATERIAL_OPAQUE ? payload.motionPrev : float3(0.0, 0.0, 0.0);
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6; // sky reflection: virtual image at infinity along specDir
        reflectedMotionPrev = float3(0.0, 0.0, 0.0);
    }
    float4 previousClip = previousReflectionClip(surfacePos, n, reflectedHit, reflectedMotionPrev);
    return checkedMotion(previousClip, currentNdc, size, surfaceMotion);
}

// One-interface refraction for the water guide buffers: trace the refracted ray from the surface into
// (or out of) the water and report the content it lands on. Diffuse AND specular albedo, plus MV, then
// track that hit as its own feature (the water surface itself has no true diffuse/specular response of
// its own worth demodulating by — it's a clear interface), while depth stays on the water surface.
// `refracted` is false on total internal reflection (no refracted hit exists); a sky miss returns a far
// point along the refracted direction, which the self-delta MV handles uniformly.
void refractedGuideHit(float3 surfacePos, float3 incidentDir, float3 surfaceNormal, bool inWaterAtSurface,
                       float materialIor,
                       float rayConeWidth, float rayConeSpread,
                       out float3 hitCamRel, out float3 motionPrev, out bool refracted, out float3 diffuseAlbedo,
                       out float3 specAlbedo) {
    hitCamRel = surfacePos - worldPush.camOffset;
    motionPrev = float3(0.0, 0.0, 0.0);
    refracted = false;
    diffuseAlbedo = float3(0.0, 0.0, 0.0);
    specAlbedo = float3(0.0, 0.0, 0.0);

    float etaI = inWaterAtSurface ? materialIor : 1.0;
    float etaT = inWaterAtSurface ? 1.0 : materialIor;
    float3 refractedDir = refract(incidentDir, surfaceNormal, etaI / etaT);
    if (dot(refractedDir, refractedDir) <= 0.0) {
        return; // total internal reflection: no refracted guide hit exists
    }
    refractedDir = normalize(refractedDir);
    refracted = true;

    float3 ro = surfacePos - surfaceNormal * SURF_BIAS;
    payload.hitT = -1.0;
    payloadSetTraceState(false, rayConeWidth, rayConeSpread);
    traceRadianceReordered(firstPersonReceiver ? CULL_HAND_WORLD : CULL_SECONDARY, ro, RAY_TMIN, refractedDir, 10000.0);
    if (payload.hitT < 0.0) {
        hitCamRel = (ro + refractedDir * 1.0e6) - worldPush.camOffset; // sky through the surface, at infinity
        motionPrev = float3(0.0, 0.0, 0.0);
        diffuseAlbedo = SKY_DIFF_ALBEDO; // sky guide: keep radiance out of the demodulation albedo
        specAlbedo = SKY_SPEC_ALBEDO;
        return;
    }

    float3 hitPos = ro + refractedDir * payload.hitT;
    hitCamRel = hitPos - worldPush.camOffset;
    uint material = payloadMaterial();
    motionPrev = material != MATERIAL_WATER ? payload.motionPrev : float3(0.0, 0.0, 0.0);
    if (material == MATERIAL_OPAQUE) {
        bool pbr = (worldPush.flags & 2u) != 0u;
        diffuseAlbedo = pbr ? payload.albedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0)) : payload.albedo;
        if (pbr) {
            float3 f0 = payload.f0;
            float rough = clamp(payloadRoughness(), MIN_ROUGH, 1.0);
            float NoV = clamp(dot(payload.normal, -refractedDir), 0.0, 1.0);
            specAlbedo = rrSpecularAlbedo(f0, rough * rough, NoV);
        }
    }
}

#ifdef CAUSTICA_DH
// Native DH raster depth is reconstructed with DH's OWN near/far projection, then compared in
// camera-relative world space with the primary RT hit. No LOD geometry enters the TLAS.
float3 dhUnproject(float2 uv,float depth) {
    float clipDepth=worldPush.dhTextures.z!=0?depth*2.0-1.0:depth;
    float4 h=mul(worldPush.dhInverseViewProjection,float4(uv*2.0-1.0,clipDepth,1.0));
    return h.xyz/h.w;
}
#include "dh_secondary.slang"

// The screen-space DH color is Distant Horizons' own raster shading, lit by Minecraft's lightmap. The
// overworld/Nether lightmap follows day and block light closely enough to stand in for scene radiance, but
// the End lightmap is a flat full-bright: under the End's dim light and the exposure it drives, far islands
// turned into white bands. There, take the raster color as albedo and light it with the End's own
// directional light plus a small sky ambient, the same light the hardware LOD path shades with.
float3 dhScreenRadiance(float3 color,float3 normal) {
    if(worldPush.environment.x!=2.0)return color;
    float ndl=saturate(dot(normal,worldPush.lightDir.xyz));
    return color*worldPush.lightRadiance.xyz*(ndl+.15)*INV_PI;
}

bool dhPrimarySurface(float3 ro,float3 rd,out float3 position,out float3 color,out float3 normal,out float alpha) {
    position=float3(0);color=float3(0);normal=-rd;alpha=0.0;
    if(worldPush.dhTextures.x==0 || worldPush.dhTextures.y==0)return false;
    float3 cameraOrigin=ro-worldPush.camOffset;
    float4 clip=mul(worldPush.dhViewProjection,float4(cameraOrigin+rd*1024.0,1.0));
    if(clip.w<=0.0)return false;
    float2 uv=clip.xy/clip.w*.5+.5;
    if(any(uv<0.0)||any(uv>=1.0))return false;
    uint width,height;dhInputTextures[worldPush.dhTextures.y].GetDimensions(width,height);
    float2 size=float2(width,height);
    uv=(floor(uv*size)+.5)/size;
    float depth=dhInputTextures[worldPush.dhTextures.y].SampleLevel(uv,0).r;
    float clearDepth=worldPush.dhTextures.w!=0?0.0:1.0;
    if(abs(depth-clearDepth)<1e-7)return false;
    float4 texel=dhInputTextures[worldPush.dhTextures.x].SampleLevel(uv,0);
    if(texel.a<1e-4)return false;
    position=dhUnproject(uv,depth);
    if(!all(isfinite(position)) || dot(position-cameraOrigin,rd)<=0.0)return false;
    // Native Blaze color is RGBA8_UNORM carrying Minecraft's gamma-space shading.
    alpha=saturate(texel.a);
    // Native alpha blending stores gamma-space premultiplied RGB. Decode only AFTER
    // unpremultiplication; a stochastic alpha-over chooses the actual RT background otherwise.
    color=pow(max(texel.rgb/max(alpha,1e-4),0.0),float3(2.2));
    float2 ux=min(uv+float2(1.0/size.x,0),1.0-.5/size);
    float2 uy=min(uv+float2(0,1.0/size.y),1.0-.5/size);
    float dx=dhInputTextures[worldPush.dhTextures.y].SampleLevel(ux,0).r;
    float dy=dhInputTextures[worldPush.dhTextures.y].SampleLevel(uy,0).r;
    if(abs(dx-clearDepth)>1e-7 && abs(dy-clearDepth)>1e-7) {
        float3 n=cross(dhUnproject(ux,dx)-position,dhUnproject(uy,dy)-position);
        if(all(isfinite(n)) && dot(n,n)>1e-10)normal=normalize(n)* (dot(n,rd)>0.0?-1.0:1.0);
    }
    // Snap the reconstructed depth onto the actual jittered camera ray. Guides then project
    // to that SAME primary sample, instead of inventing static motion at the raster texel centre.
    float distance=dot(position-cameraOrigin,rd);
    float denom=dot(normal,rd);
    if(abs(denom)>1e-5) {
        float planeDistance=dot(normal,position-cameraOrigin)/denom;
        if(planeDistance>0.0 && abs(planeDistance-distance)<max(1.0,.02*distance))distance=planeDistance;
    }
    position=cameraOrigin+rd*distance;
    return true;
}

#endif

// ---- World radiance cache (see radiance_cache.slang) ----
static const uint RC_UPDATE_MARKER = 0xCAC4E000u; // pc.debugView value selecting the sparse Update dispatch
static const uint SKY_LUT_MARKER = 0x5C7A1000u;   // pc.debugView value selecting the sky LUT build dispatch
static const uint RC_RECORDED = 3u;               // first opaque vertices of an Update path that receive samples
static bool rc_update;
static uint ser_hint;
// Primary surface replacement (PSR) for near-perfect metallic mirrors: DLSS-RR receives the guides of the
// surface seen IN the mirror (virtual position, mirrored normal, tinted albedo), so it reconstructs the
// reflection like ordinary primary geometry instead of blurring it as a specular signal.
static const uint FRAME_FLAG_MIRROR_PSR = 32768u;
static bool gv_psr;
// First visible surface (the glass pane itself, not the PSR-unfolded content behind it) for raster overlays.
static float3 gv_primaryCamRel;
static bool gv_primaryValid;
// The camera ray passed an ITRP rain veil: the surface behind it is still the camera's visible surface.
static bool cameraThroughRain;
// Cloud transmittance of the camera's own sky pixel (world.rmiss), for display.comp's night sky.
static float gv_skyTransmittance;
// The camera reached the sky through clear static glass only: display.comp draws the night sky there
// too (weighted by the glass transmission), so glass shows the same full-resolution sky as open air.
static bool gv_glassSky;
static uint glassSampleOrdinal;
static float rc_launchRough; // roughness of the lobe that launched the current ray (1 = diffuse, 0 = mirror)
static uint rc_count;
static uint rc_slot[RC_RECORDED];
static float3 rc_before[RC_RECORDED];
static float3 rc_throughput[RC_RECORDED];

RcGrid rcGrid() {
    RcGrid g;
    g.camera = worldPush.cloudWorld.xyz;
    g.sceneScale = worldPush.cacheParams.x;
    return g;
}

// Rebased (terrain) coordinates -> absolute world coordinates, so cache keys survive the per-move rebase.
float3 rcAbsolute(float3 rebased) {
    return rebased - worldPush.camOffset + worldPush.cloudWorld.xyz;
}

// One Monte Carlo path from (ro, rd). Returns accumulated radiance along the path.
// ---- RIS emitter NEE over a spatial light grid (ported from upstream Caustica #23/#27). One reservoir of M
// power-proposed candidates per shaded vertex, one shadow ray on the survivor. Direct hits on in-buffer
// emitters are gated off diffuse continuation rays (PAYLOAD_EMITTER_IN_LIST), so nothing is counted twice;
// emitters outside the buffer (sparse/weak footprints, entities, DH LODs) keep gathering on hits.

struct Reservoir {
    float3 pos;  // light sample point (rebased world space)
    float3 lnrm; // emitter outward normal
    float3 le;   // emitter radiance as shaded on a direct hit
    float area;  // emitter rectangle area (the area-light pdf factor)
    float M;
    float W;
    float wSum;
    float phat;
};

Reservoir resEmpty() {
    Reservoir r;
    r.pos = float3(0.0, 0.0, 0.0);
    r.lnrm = float3(0.0, 0.0, 0.0);
    r.le = float3(0.0, 0.0, 0.0);
    r.area = 0.0;
    r.M = 0.0;
    r.W = 0.0;
    r.wSum = 0.0;
    r.phat = 0.0;
    return r;
}

// Unshadowed contribution of a fixed light sample point at surface (hitPos,n,v); out p-hat = its luminance.
// Front hemisphere: the sun NEE's Lambert(+GGX) split. Back hemisphere with sss > 0: HG transmission.
float3 evalSampleContrib(float3 sp, float3 lnrm, float3 le, float area, float3 hitPos, float3 n,
                         float3 v, float3 rd, float3 diffAlb, float3 F0, float rough, bool pbr,
                         float sss, out float phat) {
    phat = 0.0;
    float3 toL = sp - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6) return float3(0.0, 0.0, 0.0);
    float dist = sqrt(dist2);
    float3 wi = toL / dist;
    float cosL = max(0.0, dot(lnrm, -wi));
    if (cosL <= 0.0) return float3(0.0, 0.0, 0.0); // receiver behind the emitter face
    float ndl = dot(n, wi);
    float3 contrib = float3(0.0, 0.0, 0.0);
    if (ndl > 0.0) {
        float G = ndl * cosL / dist2;
        float3 brdf = diffAlb * INV_PI;
        if (pbr) {
            float3 h = normalize(wi + v);
            float ndh = max(0.0, dot(n, h));
            float ndv = max(1.0e-4, dot(n, v));
            float vdh = max(0.0, dot(v, h));
            brdf += (ggxD(ndh, rough) * ggxG1(ndv, rough) * ggxG1(ndl, rough)) * fresnelSchlick(vdh, F0)
                    / (4.0 * ndv * ndl);
        }
        contrib = brdf * le * (G * area);
    } else if (sss > 0.0) {
        float G = -ndl * cosL / dist2;
        contrib = diffAlb * sss * SSS_STRENGTH * hg(dot(wi, rd), SSS_G) * le * (G * area);
    }
    phat = luminance(contrib);
    return contrib;
}

void selectGlobalLight(inout uint proposalSeed, out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(worldPush.lightCount);
    uint column = min(uint(aliasSample), worldPush.lightCount - 1u);
    if (pc.lightAliasAddr != 0) {
        LightAlias a = ConstPtr<LightAlias>(pc.lightAliasAddr)[column];
        lightIndex = aliasSample - float(column) < a.accept ? column : a.aliasIndex;
    } else {
        lightIndex = column;
    }
}

bool findLightGridCell(float3 p, out LightGridCell cell, out int3 cellCoord) {
    cell.spanOffset = 0u;
    cell.spanCount = 0u;
    cell.invWeightSum = 0.0;
    cellCoord = int3(0, 0, 0);
    if (pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0) return false;
    int3 coord = int3(floor((p - worldPush.lightGridOrigin.xyz) / worldPush.lightGridOrigin.w));
    if (any(coord < 0) || any(coord >= worldPush.lightGridDims.xyz)) return false;
    uint linear = (uint(coord.z) * uint(worldPush.lightGridDims.y) + uint(coord.y))
            * uint(worldPush.lightGridDims.x) + uint(coord.x);
    cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[linear];
    cellCoord = coord;
    return cell.spanCount > 0u;
}

void selectSectionLight(uint firstLight, uint lightCount, inout uint proposalSeed, out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    LightAlias alias = ConstPtr<LightAlias>(pc.lightLocalAliasAddr)[firstLight + column];
    lightIndex = firstLight + (aliasSample - float(column) < alias.accept ? column : alias.aliasIndex);
}

float unpackUnsignedFloat(uint bits, uint mantissaBits) {
    uint mantissa = bits & ((1u << mantissaBits) - 1u);
    uint exponent = (bits >> mantissaBits) & 31u;
    if (exponent == 0u) return float(mantissa) * exp2(float(1 - 15 - int(mantissaBits)));
    return (1.0 + float(mantissa) / float(1u << mantissaBits)) * exp2(float(int(exponent) - 15));
}

// Collector radiance (the alias tables' power basis): albedo * mask * state light.
float3 lightRawRadiance(Light light) {
    uint packed = light.le;
    return float3(unpackUnsignedFloat(packed & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 11u) & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 22u) & 0x3ffu, 5u));
}

// The radiance a direct hit on this emitter shades (see the emission term in tracePath): the same
// EMISSIVE_STRENGTH and visual gain, torch brightness, and ITRP light colors, so RIS-lit and hit-lit
// light agree whatever the settings.
float3 lightShadedRadiance(Light light, float3 raw) {
    uint kind = (light.section >> 27u) & 7u;
    float3 le = raw;
    if ((worldPush.flags & 1024u) != 0u) {
        if ((kind & 2u) != 0u) le = luminance(raw) * pow(float3(1, .6, .25), float3(2.2)) * 1.52;
        else if ((kind & 4u) != 0u) le = luminance(raw) * pow(float3(1, .2, .17), float3(2.2)) * 4.0;
    }
    if ((kind & 1u) != 0u) le *= worldPush.torchControl.x;
    return le * (EMISSIVE_STRENGTH * worldPush.visualLight.x);
}

int3 lightSectionCoord(Light light) {
    uint packed = light.section;
    return int3(int(packed & 0x1ffu), int((packed >> 9u) & 0x1ffu), int((packed >> 18u) & 0x1ffu));
}

float3 lightHalfU(Light light) { return float3(unpackHalf2(light.halfUxy), unpackHalf2(light.halfUzVx).x); }
float3 lightHalfV(Light light) { return float3(unpackHalf2(light.halfUzVx).y, unpackHalf2(light.halfVyz)); }

float proposalPdf(Light light, float area, float3 rawLe, LightGridCell cell, int3 cellCoord,
                  float localProbability) {
    float power = max(0.0, area * luminance(rawLe));
    float globalPdf = pc.lightAliasAddr != 0 ? power * worldPush.lightRebase.w : 1.0 / float(worldPush.lightCount);
    float localPdf = 0.0;
    if (localProbability > 0.0 && power > 0.0) {
        int3 delta = lightSectionCoord(light) - cellCoord;
        if (all(abs(delta) <= int3(2, 2, 2))) {
            float3 deltaF = float3(delta);
            localPdf = power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
        }
    }
    return localProbability * localPdf + (1.0 - localProbability) * globalPdf;
}

void selectLightGridSpanLight(LightGridCell cell, inout uint proposalSeed, out uint lightIndex) {
    ConstPtr<LightGridSpan> spans = ConstPtr<LightGridSpan>(pc.lightGridSpanAddr);
    float aliasSample = rndf(proposalSeed) * float(cell.spanCount);
    uint column = min(uint(aliasSample), cell.spanCount - 1u);
    LightGridSpan span = spans[cell.spanOffset + column];
    bool self = aliasSample - float(column) < span.accept;
    uint firstLight = self ? span.firstLight : span.aliasFirstLight;
    uint lightCount = self ? (span.packedLightCounts & 0xffffu) : (span.packedLightCounts >> 16u);
    selectSectionLight(firstLight, lightCount, proposalSeed, lightIndex);
}

// Secondary vertices run M/4 candidates (upstream measured 18.0 -> 16.1 ms, indistinguishable by eye);
// RIS stops past the second shaded surface, where the gate lifts and emitters gather on hits again.
static const uint SECONDARY_RIS_DIVISOR = 4u;
static const uint RIS_MAX_DIFFUSE_DEPTH = 2u;

Reservoir risInitial(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb, float3 F0,
                     float rough, bool pbr, float sss, uint shadedDepth,
                     inout uint seed, inout uint proposalSeed) {
    Reservoir r = resEmpty();
    LightGridCell gridCell;
    int3 gridCellCoord;
    float3 gridLookup = hitPos;
    if (pc.lightGridCellAddr != 0) {
        // Stochastically blend across section boundaries; every cell keeps full global support.
        gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), rndf(proposalSeed)) - 0.5)
                * worldPush.lightGridOrigin.w;
    }
    bool hasGridCell = findLightGridCell(gridLookup, gridCell, gridCellCoord);
    // No light section within the cell's reach (open daylight, far from any lamp): skip the candidate walk
    // and its shadow ray. M stays 0, so the next vertex keeps gathering emitters it hits directly (the
    // gate keys on whether RIS actually ran) — nothing is lost, only the far lights' variance stays.
    if (!hasGridCell) return r;
    uint fullCount = worldPush.risCandidates & 0xffffu;
    uint candidateCount = shadedDepth == 0u ? fullCount : max(1u, fullCount / SECONDARY_RIS_DIVISOR);
    // Deterministic proposal stratification: at M=8, six local + two global candidates.
    uint globalCandidateCount = hasGridCell ? max(1u, (candidateCount + 2u) / 4u) : candidateCount;
    float localProbability = float(candidateCount - globalCandidateCount) / float(candidateCount);
    for (uint c = 0u; c < candidateCount; c++) {
        r.M += 1.0;
        uint li;
        bool useLocal = hasGridCell && ((c + 1u) * globalCandidateCount) / candidateCount
                == (c * globalCandidateCount) / candidateCount;
        if (useLocal) selectLightGridSpanLight(gridCell, proposalSeed, li);
        else selectGlobalLight(proposalSeed, li);
        Light lg = ConstPtr<Light>(pc.lightBufAddr)[li];
        float3 halfU = lightHalfU(lg), halfV = lightHalfV(lg);
        float3 cuv = cross(halfU, halfV);
        float area = 4.0 * length(cuv);
        float3 lightNormal = normalize(cuv) * ((lg.section & 0x40000000u) != 0u ? -1.0 : 1.0);
        // Soft shadows: uniform point on the emitter rectangle ((s,t) in [-1,1]^2, pdf 1/area).
        float s = rndf(seed) * 2.0 - 1.0;
        float t = rndf(seed) * 2.0 - 1.0;
        float3 sp = lg.pos + worldPush.lightRebase.xyz + s * halfU + t * halfV;
        float3 rawLe = lightRawRadiance(lg);
        float3 le = lightShadedRadiance(lg, rawLe);
        float phat;
        evalSampleContrib(sp, lightNormal, le, area, hitPos, n, v, rd, diffAlb, F0, rough, pbr, sss, phat);
        if (phat <= 0.0) continue;
        float w = phat / max(proposalPdf(lg, area, rawLe, gridCell, gridCellCoord, localProbability), 1.0e-20);
        r.wSum += w;
        if (rndf(seed) * r.wSum < w) {
            r.pos = sp;
            r.lnrm = lightNormal;
            r.le = le;
            r.area = area;
            r.phat = phat;
        }
    }
    r.W = r.phat > 0.0 ? (r.wSum / (r.M * r.phat)) : 0.0;
    return r;
}

// Recompute the survivor's contribution, cast ONE shadow ray from the sample's side of the surface, and
// return throughput-free radiance contrib*vis*W. No cloud shadow: block lights sit under the deck.
float3 shadeReservoir(Reservoir s, float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                      float3 F0, float rough, bool pbr, float sss) {
    if (s.W <= 0.0 || s.phat <= 0.0) return float3(0.0, 0.0, 0.0);
    float phat;
    float3 contrib = evalSampleContrib(s.pos, s.lnrm, s.le, s.area, hitPos, n, v, rd,
            diffAlb, F0, rough, pbr, sss, phat);
    if (phat <= 0.0) return float3(0.0, 0.0, 0.0);
    // Bias the origin to the sample's side first, then measure the ray from it, so the stop-short margin
    // can never let the ray reach the emitter's own face.
    float side = dot(n, s.pos - hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = hitPos + side * n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
    float3 vis = visibilityMasked(origin, toL / dist, dist * 0.999, secondaryReceiverMask);
    return contrib * vis * s.W;
}

float3 tracePath(float3 ro, float3 rd, float primaryConeSpread, inout uint seed) {
    secondaryReceiverMask=CULL_SECONDARY;
    firstPersonReceiver=false;
    pathCloudShadow=-1.0;
    float3 L = float3(0.0, 0.0, 0.0);
    float3 throughput = float3(1.0, 1.0, 1.0);
    float rayConeWidth = 0.0;
    float rayConeSpread = max(primaryConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    bool pbr = (worldPush.flags & 2u) != 0u; // GGX BRDF + material guides, else Lambertian fallback
    // Start submerged if the camera is in water so the first segment gets the right medium orientation
    // and absorption when the eye is underwater.
    bool inWater = (worldPush.flags & 1u) != 0u;
    // Current medium extinction. Defaults to the camera-biome tint (correct when the eye starts
    // submerged, before any water surface is hit); refreshed to the hit water body's tint on each crossing.
    float3 waterExt = waterExtinction(worldPush.waterParams.xyz);
    bool waterWaves = (worldPush.flags & 16u) != 0u; // animated wave-normal perturbation
    // Sky-disc gate (see world.rmiss): the sun/moon disc is shown to the primary ray and to rays spawned
    // by a specular/dielectric bounce (mirror reflections + refractions of the sun/moon), but hidden from
    // diffuse-sampled continuation rays — those are already covered by the sun NEE, so the tiny disc
    // would be a double-counted firefly. Reset true on every specular/water bounce, false on a diffuse one.
    bool showCelestial = true;
    bool waterScatterDone=false;
    gv_farLod=false;
    rc_count = 0u;
    rc_launchRough = 0.0;
    bool cacheOn = worldPush.cacheCapacity != 0u;
    gv_psr = false;
    gv_primaryValid = false;
    cameraThroughRain = false;
    gv_skyTransmittance = 0.0;
    gv_glassSky = false;
    bool cameraThroughGlass = false;
    throughTransmission = false;
    MirrorGuideTransform psrTransform=mirrorGuideIdentity();
    float3 psrTint=float3(1.0);
    int gvBounce = 0; // path vertex whose attributes feed the RR guides (1 behind a PSR mirror)
    bool glassSkyDone = false;
    // RIS emitter NEE: active when lights are published and the candidate count is non-zero.
    bool risOn = (worldPush.risCandidates & 0xffffu) > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
    // Light mode (bit 16): only the first shaded surface resamples emitters; deeper ones gather on hits.
    int risMaxDepth = (worldPush.risCandidates >> 16u) != 0u ? 0 : int(RIS_MAX_DIFFUSE_DEPTH);
    // Independent light-proposal RNG (screen-coherent sharing made tile-shaped noise visible upstream).
    uint2 risPix = DispatchRaysIndex().xy;
    uint proposalSeed = risPix.x * 1973u + risPix.y * 9277u + 26699u ^ pc.frameIndex * 2654435761u ^ seed;
    proposalSeed = pcg(proposalSeed);
    // Surfaces SHADED so far, unlike `bounce`, which also counts glass/water interfaces: a wall behind a
    // pane is bounce 1 but shaded depth 0 — visually primary, so it keeps full RIS.
    int diffuseDepth = 0;
    bool risRanPrev = false; // the previous shaded vertex resampled emitters, so direct hits on them are gated
    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        payload.hitT = -1.0;
        payloadSetTraceState(showCelestial, rayConeWidth, rayConeSpread);
        uint incomingCone=payload.rayCone;
        if(bounce==0 || cameraThroughRain || cameraThroughGlass) payload.flags |= PAYLOAD_CAMERA_MISS;
        bool viaGlass = cameraThroughGlass;
        cameraThroughRain = false;
        cameraThroughGlass = false;
        ser_hint = (bounce == 0 ? 0u : 2u) | (showCelestial ? 0u : 1u);
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles but not the first-person
        // player. Bounce rays are secondary (CULL_SECONDARY): exclude particles, include the player.
        uint receiverMask = bounce == 0 ? CULL_PRIMARY
                : (throughTransmission ? secondaryReceiverMask | CULL_SPECULAR_PARTICLES : secondaryReceiverMask);
        throughTransmission = false;
        HitObject receiverHit=traceRadianceReordered(receiverMask, ro, 0.0, rd, 10000.0);
        // Only the hand receiver excludes its camera-owner body. Once the path lands on
        // the world again, ordinary body shadows/GI are restored for that receiver.
        bool handHit = bounce==0 && payload.hitT>=0.0 && (payload.iorTransmission&FIRST_PERSON_RECEIVER)!=0u;
        if(bounce==0) firstPersonReceiver=handHit;
        if(bounce==0) {
            gv_primaryValid = payload.hitT >= 0.0;
            if (gv_primaryValid) gv_primaryCamRel = ro + rd * payload.hitT - worldPush.camOffset;
        }
        secondaryReceiverMask=handHit ? CULL_HAND_WORLD : CULL_SECONDARY;

#ifdef CAUSTICA_DH
        bool dhHit=false;
        float3 dhRadiance=bounce==0&&dhCandidate?dhScreenRadiance(dhCandidateColor,dhCandidateNormal):float3(0);
        if(bounce==0 && dhCandidate) {
            float dhDistance=dot(dhCandidatePosition-(ro-worldPush.camOffset),rd);
            dhHit=payload.hitT<0.0 || dhDistance<payload.hitT;
            // Partial DH coverage (silhouette edges against the sky, translucent LODs) picks LOD or background
            // with a threshold fixed per pixel. A fresh random draw every frame flipped far outlines between
            // building and sky each frame, which RR showed as edges flickering around distant skylines.
            if(dhHit && dhCandidateAlpha<1.0) {
                float2 px=float2(DispatchRaysIndex().xy);
                dhHit=frac(52.9829189*frac(dot(px,float2(.06711056,.00583715))))<dhCandidateAlpha;
            }
            if(dhHit)payload.hitT=dhDistance;
        }
        // First continuation only: cached far radiance terminates the path instead of spawning
        // another lighting/NEE tree over simplified LODs. Exact near-scene hits always win.
        if(bounce==1 && payload.hitT<0.0) {
            float distance,alpha;float3 color;
            if(dhSecondarySurface(ro,rd,10000.0,distance,color,alpha) && rndf(seed)<alpha) {
                dhHit=true;payload.hitT=distance;dhRadiance=dhScreenRadiance(color,-rd);
            }
        }
#endif
        // ITRP DiffuseTracer (Nether): a diffuse ray that travels through the open cavern gathers the
        // biome ambient, NetherLighting() * saturate(rayLength * 0.1 - 0.1).
        if (worldPush.environment.x == 1.0 && (worldPush.flags & 512u) != 0u && bounce > 0 && !showCelestial && !inWater) {
            float rayLength = payload.hitT >= 0.0 ? payload.hitT : 1024.0;
            L += throughput * worldPush.netherLighting.rgb * worldPush.dimensionControl.y * saturate(rayLength * 0.1 - 0.1);
        }
        float mediumDistance=payload.hitT>=0 ? payload.hitT : inWater?96.0:0.0;
        if(inWater || bounce==0) {
            bool scatter=inWater?!waterScatterDone:bounce==0;
            transportMedium(ro,rd,mediumDistance,inWater,scatter,waterExt,throughput,L,seed);
            if(inWater)waterScatterDone=true;
        }
#ifdef CAUSTICA_DH
        if(dhHit) {
            if(bounce>0 && bounce==gvBounce) {
                // Far LOD seen in a PSR mirror: smooth unlit guide at the reflected point.
                gv_normal=-rd;gv_rough=1.0;gv_albedo=float3(.5);gv_specAlb=float3(0);
                gv_hitCamRel=ro+rd*payload.hitT-worldPush.camOffset;gv_motionHitCamRel=gv_hitCamRel;
                gv_motionUseRefracted=false;gv_motionObjDisp=float3(0);
            }
            if(bounce==0) {
                gv_farLod=true;
                gv_normal=dhCandidateNormal;gv_rough=1.0;
                gv_albedo=float3(.5);gv_specAlb=float3(0);
                gv_emission=0.0;gv_emissionSource=EMISSION_SOURCE_NONE;
                gv_hitCamRel=dhCandidatePosition;gv_motionHitCamRel=dhCandidatePosition;
                gv_motionUseRefracted=false;gv_motionObjDisp=float3(0);
            }
            // Cached scene-linear far radiance, with the existing path throughput/medium.
            // Never treat this already-lit color as diffuse albedo or add a second direct-light term.
            L+=throughput*dhRadiance;
            break;
        }
#endif
        // Debug view 10: what the first bounce ray hit, denoised like the image (sky / within 1 block / terrain).
        if (pc.debugView == 10u && bounce == 1) {
            L = payload.hitT < 0.0 ? float3(1.0, 1.0, 1.0) : payload.hitT < 1.0 ? float3(1.0, 0.1, 0.1) : float3(0.1, 0.8, 0.1);
            break;
        }
        if (payload.hitT < 0.0) {
            // world.rmiss writes sky radiance (gradient + sun/moon disc + stars) into payload.albedo. The
            // packed show-celestial flag decides whether this path segment may see the bright disc.
            float3 sky = payload.albedo;
            if (bounce == 0) gv_skyTransmittance = payload.f0.x;
            else if (viaGlass) {
                gv_skyTransmittance = payload.f0.x * saturate(dot(throughput, float3(0.2126, 0.7152, 0.0722)));
                gv_glassSky = true;
            }
            if (bounce == gvBounce) { // primary ray hit sky: guides describe "no surface"
                gv_normal = float3(0.0, 0.0, 0.0);
                // Flat mid-grey albedo guide for sky (NOT the bright sky radiance): demodulating DLSS-RR
                // by the huge sun-disc radiance rings/artifacts around the sun. A constant albedo keeps
                // the brightness in the lighting channel where RR resolves it cleanly.
                gv_albedo = SKY_DIFF_ALBEDO;
                gv_specAlb = SKY_SPEC_ALBEDO;
                gv_rough = 1.0;
                gv_emission = 0.0;
                gv_emissionSource = EMISSION_SOURCE_NONE;
                gv_hitCamRel = rd * 1.0e6; // sky at infinity: only rotation parallax (-> large far depth)
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0); // sky is static
            }
            L += throughput * sky; // escaped to sky
            break;
        }

        float launchRough = rc_launchRough; // set by the GGX lobe below; every other continuation is mirror-like
        rc_launchRough = 0.0;
        float3 n = payload.normal; // oriented toward the incoming ray by the closest-hit
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        if (bounce == 0) {
            gv_emission = payloadEmission();
            gv_emissionSource = payloadEmissionSource();
        }

        // Real dielectric entry/exit; the geometric boundary normal is independent of normal mapping.
        if (material == MATERIAL_GLASS) {
            float3 tint=glassBoundaryTransmission(payload.albedo,payloadTransmission());
            float materialIor=max(payloadIor(),1.0e-3);
            bool entering=payloadWaterEntering();
            float etaI=entering?1.0:materialIor,etaT=entering?materialIor:1.0;
            float eta=etaI/etaT;
            float cosI=clamp(dot(-rd,n),0.0,1.0);
            float F=fresnelDielectric(cosI,etaI,etaT);
            // First-person held glass moves with the camera: bending the view through it changed what
            // its edges showed every frame, which RR could not track and flickered. Its surface keeps
            // the real Fresnel reflection; the view passes straight through (see unfold below).
            bool heldGlass=(payload.iorTransmission&FIRST_PERSON_RECEIVER)!=0u;
            bool staticGlass=(payload.flags & PAYLOAD_STATIC_GLASS)!=0u;
            // Block glass refracts at its flat geometric face: a slab's entry and exit bend cancel, so the
            // world behind stays sharp. Bending at the normal-mapped normal turned resource-pack glass bumps
            // into a smeared, blotchy view through every pane; the surface reflection keeps the normal map.
            float3 refractNormal=staticGlass && dot(payload.f0,payload.f0)>0.25?normalize(payload.f0):n;
            float3 transmitted=heldGlass?rd:refract(rd,refractNormal,eta);
            // Leaving a glass block through a face adjacent to the entry face is always total internal
            // reflection (critical angle ~42 degrees at IOR 1.5): the path bounced inside until its budget ran
            // out and the block edge seen at a grazing angle went pure black. Block glass is treated as a thin
            // slab (see refractNormal), so such an exit passes straight out instead.
            bool slabEscape=staticGlass && !entering && !heldGlass && dot(transmitted,transmitted)<1.0e-12;
            if(slabEscape) { transmitted=rd; F=0.0; eta=1.0; }
            bool totalInternal=dot(transmitted,transmitted)<1.0e-12;
            bool reflected=totalInternal;
            // A per-pixel reflect/refract coin flip at 1 spp flips the RR guides between two
            // unrelated surfaces, which DLSS-RR shows as crawling noise on glass. Outside cache
            // updates, follow transmission weighted by (1-F) and add the reflected sky once per path.
            bool deterministic=!totalInternal && !rc_update;
            float3 glassSky=float3(0.0);
            if(deterministic && !glassSkyDone && F>1.0e-4) { // eta=1 mob veils reflect nothing
                glassSkyDone=true;
                glassSky=glassReflection(hitPos+n*SURF_BIAS,reflect(rd,n),F>0.08);
            }
            if(!totalInternal && !deterministic) {
                float choice=rndf(seed); // keep the downstream path's RNG sequence unchanged
                // Use a separate low-discrepancy Fresnel stream at every glass
                // boundary, including held glass. A wall of panes otherwise
                // falls back to white-noise choices after its first interface.
                // Keep the regular RNG draw above so the rest of the path keeps
                // its existing sequence and its unbiased branch probabilities.
                if(!rc_update) {
                    uint b=uint(bounce);
                    uint2 streamPixel=DispatchRaysIndex().xy
                            +uint2(b*0x9E3779B9u,b*0x85EBCA6Bu);
                    choice=glassFresnelSample(streamPixel,glassSampleOrdinal);
                }
                reflected=choice<F;
            }
            float3 boundaryNormal=dot(payload.f0,payload.f0)>0.25?normalize(payload.f0):n;
            bool guidePrefix=bounce==gvBounce;
            // Block glass always transmits along its flat face (see refractNormal), so the world behind it is a
            // sharp image whatever roughness a resource pack assigns. Any roughness test here only decided whether
            // RR denoised the pane as an opaque surface, which smeared everything behind clear and stained glass.
            bool clearGlass=staticGlass || payloadRoughness()<=0.15
                    || min(payload.albedo.x,min(payload.albedo.y,payload.albedo.z))>=0.995;
            // Moving glass keeps its existing foreground motion guides. Static clear glass can supply
            // the visible continuation's guides; no extra guide ray or larger path budget is introduced.
            // A mob veil (eta=1, see world.rchit.slang) does not bend the ray, so the surface behind it
            // keeps its own position and motion: hand RR those guides, as for static glass, instead of
            // the flat veil, which made the slime core a blurred texture.
            bool mobVeil=abs(materialIor-1.0)<1.0e-3;
            bool unfold=guidePrefix && bounce<maxBounces
                    && (heldGlass || (!firstPersonReceiver && (mobVeil || (clearGlass && staticGlass))));
            if(guidePrefix) {
                gv_normal=n;gv_rough=clamp(payloadRoughness(),MIN_ROUGH,1.0);
                gv_specAlb=float3(F);gv_albedo=tint;
                gv_hitCamRel=hitPos-worldPush.camOffset;gv_motionHitCamRel=gv_hitCamRel;
                gv_motionUseRefracted=false;gv_motionObjDisp=payload.motionPrev;
            }
            if(reflected) {
                if(unfold)mirrorGuideAppend(psrTransform,hitPos-worldPush.camOffset,n);
                rd=reflect(rd,n);ro=hitPos+boundaryNormal*SURF_BIAS;
            } else {
                float3 nextDir=normalize(transmitted);
                // The RR guide must follow the ray actually traced: block glass bends at its flat face, so the
                // virtual image is built from that same normal. Building it from the bumped normal made the
                // guide disagree with the image, and RR smeared history into noise crawling across the pane.
                if(unfold)unfold=mirrorGuideAppendTransmission(psrTransform,hitPos-worldPush.camOffset,
                        refractNormal,heldGlass?1.0:eta,abs(dot(-rd,refractNormal)),abs(dot(nextDir,refractNormal)));
                if(deterministic) { L+=throughput*F*glassSky;throughput*=1.0-F; }
                // Paired boundaries reproduce one slab tint instead of squaring it. Radiance eta^2
                // factors cancel across a parallel entry/exit pair.
                throughput*=tint*(heldGlass?1.0:eta*eta);
                throughTransmission=true;
                // Still on the camera's straight line of sight (clear static glass, unfolded guides).
                cameraThroughGlass=unfold && clearGlass && !heldGlass && !mobVeil && (bounce==0 || viaGlass);
                if(unfold)psrTint*=tint;
                rd=nextDir;ro=hitPos-boundaryNormal*GLASS_TRANSMIT_BIAS;
            }
            if(unfold) { gv_psr=true;gvBounce=bounce+1; }
            showCelestial=true;
            if(bounce>=rrStart && !unfold) {
                float q=clamp(max(throughput.r,max(throughput.g,throughput.b)),0.02,1.0);
                if(rndf(seed)>q)break;
                throughput/=q;
            }
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE && payload.f0.x < -0.5) {
            // ITRP-PTGI rain (Weather_FS + Volumetric_FS Rain()): color = mix(color, rainColor,
            // saturate(mask * 0.15 * wetness * RAIN_VISIBILITY)), rainColor ~ (skylight + 0.1 sunlight)
            // * 0.01 - a faint dark veil, not a lit sprite. The ray continues to the surface behind,
            // which keeps the camera's guides and primary-surface quality.
            float a = saturate(payload.f0.y * 0.15 * saturate(worldPush.mediumParams.y));
            L += throughput * a * (worldPush.lightRadiance.xyz * 0.001 + 0.002);
            throughput *= 1.0 - a;
            ro = hitPos + rd * SURF_BIAS;
            gvBounce = bounce + 1;
            cameraThroughRain = bounce == 0;
            if (bounce >= maxBounces) break;
            maxBounces += 1; // the veil is not a path vertex
            continue;
        }
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payload.albedo;
            if (bounce == gvBounce) { // a particle seen in a mirror must not replace the mirror's guides
            gv_normal = n;
            gv_albedo = albedo; // RR demodulation target = the particle texel (keeps it sharp)
            gv_specAlb = float3(0.0, 0.0, 0.0);
            gv_rough = 1.0;
            gv_hitCamRel = hitPos - worldPush.camOffset;
            gv_motionHitCamRel = gv_hitCamRel;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = payload.motionPrev; // per-particle MV: de-ghost moving particles
            }

            float3 lightDir = worldPush.lightDir.xyz;
            float lightHalfAngle = worldPush.lightDir.w;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side.
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0);
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * worldPush.lightRadiance.xyz * ndl * vis;
                }
            }

            if (bounce >= maxBounces) {
                break;
            }
            L+=throughput*heldLighting(hitPos,n,-rd,albedo,float3(0,0,0),1.0,false,float2(1,1),inWater,waterExt,seed);
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse receiver: direct sun/moon was handled by NEE above
            continue;
        }

        // Water: smooth dielectric interface. Choose reflection or refraction stochastically by the
        // Fresnel reflectance F — sampling by F leaves the throughput unchanged (lossless interface), so
        // there is no diffuse albedo multiply and no sun NEE here (the surface is specular). On
        // transmission, the new medium is read from this hit's face orientation (payloadWaterEntering),
        // not toggled — see PAYLOAD_WATER_ENTERING. The bounce cap bounds stacked refractions.
        if (material == MATERIAL_WATER) {
            float materialIor = max(payloadIor(), 1.0e-3);
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);
            // The primary-water guide traces another radiance ray through the global payload. Preserve
            // this interface's orientation before that nested trace overwrites payload.flags.
            bool materialEntering = payloadWaterEntering();
            // Ripple the (near-flat) surface normal before any Fresnel/guide use → glints, broken
            // reflections, wobbling refraction. This water body's biome tint (carried in payload.albedo
            // for water hits) sets the absorption applied to the next submerged segment.
            if (waterWaves) {
                // World-stable wave domain: rebased hit XZ + the pushed anchor (terrain origin mod 4096)
                // reconstructs a world-pinned coordinate that survives the per-move terrain rebase.
                n = applyWaterWaves(n, hitPos.xz + worldPush.waterAnchor.xy, worldPush.waterParams.w,(payload.flags&PAYLOAD_RAIN_EXPOSED)!=0u?worldPush.mediumParams.y:0.0);
            }
            waterExt = waterExtinction(payload.albedo);
            L+=throughput*heldLighting(hitPos,n,-rd,float3(0,0,0),float3(.02,.02,.02),
                    max(payloadRoughness(),MIN_ROUGH),true,float2(1,1),inWater,waterExt,seed);
            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            if (bounce == gvBounce) { // primary water hit: feed RR a smooth dielectric specular surface
                gv_normal = n;
                gv_rough = clamp(payloadRoughness(), MIN_ROUGH, 1.0);
                gv_hitCamRel = hitPos - worldPush.camOffset; // depth + reflection start at the water surface
                // Diffuse AND specular albedo + MV track the refracted hit (floor/shore seen through the
                // surface) so refracted content stops ghosting; depth stays on the water surface. Water's
                // own Fresnel reflectance is ignored here — a clear interface, not a demodulation target.
                float3 refractedAlbedo;
                float3 refractedSpecAlbedo;
                refractedGuideHit(hitPos, rd, n, inWater, materialIor, rayConeWidth, rayConeSpread,
                        gv_motionHitCamRel, gv_motionObjDisp,
                        gv_motionUseRefracted, refractedAlbedo, refractedSpecAlbedo);
                gv_albedo = refractedAlbedo;
                // RR guide only (no radiance change): the interface's own Fresnel reflection is real
                // specular signal. With a zero/seabed specular albedo RR denoised mirror reflections on
                // water as diffuse light, blurring and dimming them. Total internal reflection is a
                // perfect mirror. The refracted surface's specular stays in the transmitted remainder.
                float waterF0 = (materialIor - 1.0) / (materialIor + 1.0);
                waterF0 *= waterF0;
                float3 waterSpec = gv_motionUseRefracted
                        ? rrSpecularAlbedo(float3(waterF0, waterF0, waterF0), gv_rough * gv_rough, cosI)
                        : float3(1.0, 1.0, 1.0);
                gv_specAlb = saturate(waterSpec + (1.0 - waterSpec) * refractedSpecAlbedo);
            }
            float etaI = inWater ? materialIor : 1.0;
            float etaT = inWater ? 1.0 : materialIor;
            float F = fresnelDielectric(cosI, etaI, etaT);
            // Visible water used the same reflect/refract coin flip as glass did, which RR could only
            // remove by blurring the surface. Where transmission dominates, follow it weighted by (1-F)
            // and add the shaded reflection along the mirror direction once (glassReflection). Grazing
            // views, where reflection dominates, keep the full path-traced reflection.
            bool waterSplit = bounce == gvBounce && !rc_update && F < 0.5;
            if (waterSplit) {
                // Shaded reflection only where it is visible (F > 8%: the far, grazing water that shows the
                // shore); a near, steep view reflects a few percent of sky, resolved by a cheap miss query.
                L += throughput * F * glassReflection(hitPos + n * SURF_BIAS, reflect(rd, n), F > 0.08);
                throughput *= 1.0 - F;
            }
            if (!waterSplit && rndf(seed) < F) {
                rd = reflect(rd, n);
                ro = hitPos + n * SURF_BIAS; // reflected ray stays on the incidence side
            } else {
                rd = refract(rd, n, etaI / etaT); // F < 1 here, so this is never total internal reflection
                throughput *= transmission;
                throughTransmission = true;
                ro = hitPos - n * SURF_BIAS;       // transmitted ray crosses to the far side
                // Derived from this hit's face orientation, not toggled — self-corrects instead of a
                // stray/missing face corrupting the medium for the rest of the path (see PAYLOAD_WATER_ENTERING).
                inWater = materialEntering;
            }
            // Specular interface (mirror reflect / clear refract): the continuation ray should see the
            // sun/moon disc directly — no NEE happened here to double-count it.
            showCelestial = true;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // A far LOD reached by an indirect ray takes its emission and sun/moon light here and ends the path:
        // further bounces off simplified distant terrain cost full rays for detail nobody can see.
        if(bounce>=1 && (payload.flags&PAYLOAD_HAS_RELIEF)==0u && payload.rayCone==DH_HIT_MARK) maxBounces=bounce;
        float3 albedo = payload.albedo;
        float localHeightVisibility=1.0-float((payload.flags >> 8u)&255u)/255.0;
        float2 heldHeightVisibility=float2(1.0-float((payload.flags >> 16u)&255u)/255.0,1.0-float((payload.flags >> 24u)&15u)/15.0);
        bool hasRelief=(payload.flags & PAYLOAD_HAS_RELIEF)!=0u;
        uint reliefCone=incomingCone;
        float reliefDepth=asfloat(payload.rayCone);
        uint reliefCameraFlag=bounce==0?PAYLOAD_CAMERA_MISS:0u;
        float sss = payloadSss(); // LabPBR SSS strength (0 when absent)
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

        // Material split. Metals tint F0 by albedo and have no diffuse lobe; dielectrics use a fixed
        // 0.04 F0. With PBR disabled this collapses to the Lambertian fallback.
        float rough = pbr ? clamp(payloadRoughness(), MIN_ROUGH, 1.0) : 1.0;
        float metal = pbr ? clamp(payloadMetalness(), 0.0, 1.0) : 0.0;
        float3 diffAlb = albedo * (1.0 - metal);
        // F0 comes from the chit: dielectric 0.04, a LabPBR custom/metal F0, or albedo for metals.
        float3 F0 = pbr ? payload.f0 : lerp(float3(0.04, 0.04, 0.04), albedo, metal);

        if (bounce == gvBounce) { // primary-visibility surface: capture the denoiser guide buffers
            gv_normal = n;
            gv_albedo = pbr ? diffAlb : albedo;  // RR diffuse-albedo demodulation target
            gv_rough = rough;
            gv_specAlb = pbr ? rrSpecularAlbedo(F0, rough * rough, dot(n, v)) : float3(0.0, 0.0, 0.0);
            gv_hitCamRel = ro + rd * payload.hitT - worldPush.camOffset; // camera-relative hit position
            gv_motionHitCamRel = gv_hitCamRel;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = payload.motionPrev; // per-vertex world motion (0 for static terrain/sky)
        }

        // Radiance cache. Only indirect vertices reached by a diffuse continuation whose segment and ray
        // footprint both exceed the voxel may use it, so contact shadows/corners keep exact paths.
        if (cacheOn) {
            RcGrid grid = rcGrid();
            float3 absPos = rcAbsolute(hitPos);
            // Voxel size steps by octaves of camera distance; a lookup at one level drew a hard line across
            // floors where the level changed. Over the upper half of each octave the visible pass blends the
            // entry of this level with the next one (no randomness, so no added noise). The update pass picks
            // one of the two levels with the same weight, so both entries exist where the blend reads them.
            float rcLf = rcLevelF(grid, absPos);
            int rcL0 = int(floor(rcLf));
            float rcW = rcL0 >= 11 ? 0.0 : smoothstep(0.5, 1.0, rcLf - float(rcL0));
            float voxel = exp2(float(rcL0) + rcW);
            int rcPick = rc_update && rndf(seed) < rcW ? rcL0 + 1 : rcL0;
            uint hash = rcHashLevel(grid, absPos, n, rcPick);
            RcPtr<uint> entries = RcPtr<uint>(worldPush.cacheEntriesAddr);
            // bit 1: rough glossy continuations (lobe roughness >= 0.5) may also use the cache.
            bool lobeOk = !showCelestial || ((worldPush.cacheFlags & 2u) != 0u && launchRough >= 0.5);
            if (bounce >= 1 && lobeOk) {
                // How far this vertex is past the cache's footprint test (1 = exactly at the old threshold).
                float rcFit = min(payload.hitT / (voxel * 1.7320508), rayConeWidth / (voxel * worldPush.cacheParams.z));
                RcPtr<float4> resolved = RcPtr<float4>(worldPush.cacheResolvedAddr);
                // Debug view 11: share of the first indirect vertex handed to the cache (blue traced .. yellow cached).
                if (!rc_update && pc.debugView == 11u) {
                    float3 probe;
                    bool cachedHere = (worldPush.cacheFlags & 1u) != 0u && rcQuery(entries, resolved, hash,
                            worldPush.cacheCapacity, worldPush.cacheParams.y, probe);
                    L = lerp(float3(0.05, 0.1, 1.0), float3(1.0, 0.9, 0.05), cachedHere ? smoothstep(0.75, 1.35, rcFit) : 0.0);
                    break;
                }
                // Update paths never read the cache. Feeding cached radiance back into its own update added
                // bounces without limit: a closed room of bright walls came out several times brighter than
                // the traced path with the cache off, which read as light leaking in.
                if (rc_update) {
                } else if ((worldPush.cacheFlags & 1u) != 0u && rcFit > 0.75) {
                    // The visible pass used to switch from the traced path to the cache in one step, which drew
                    // a line at a fixed distance from the camera. Across fit 0.75..1.35 the cached radiance
                    // takes over a growing share of the path's remaining throughput while the rest keeps
                    // tracing: continuous, no random choice, and only a narrow band traces further than before.
                    float rcTake = smoothstep(0.75, 1.35, rcFit);
                    float3 cached, cachedNext;
                    bool hit = rcQuery(entries, resolved, hash, worldPush.cacheCapacity, worldPush.cacheParams.y, cached);
                    if (rcW > 0.0) {
                        bool hitNext = rcQuery(entries, resolved, rcHashLevel(grid, absPos, n, rcL0 + 1),
                                worldPush.cacheCapacity, worldPush.cacheParams.y, cachedNext);
                        if (hit && hitNext) cached = lerp(cached, cachedNext, rcW);
                        else if (hitNext) { cached = cachedNext; hit = true; }
                    }
                    if (hit) {
                        L += throughput * cached * rcTake;
                        if (rcTake >= 1.0) break;
                        throughput *= 1.0 - rcTake;
                    }
                }
            }
            if (rc_update && rc_count < RC_RECORDED) {
                uint slot = rcInsert(entries, hash, worldPush.cacheCapacity);
                if (slot != RC_INVALID) {
                    rc_slot[rc_count] = slot;
                    rc_before[rc_count] = L;
                    rc_throughput[rc_count] = throughput;
                    rc_count++;
                }
            }
        }

        // Emissive surfaces (lava, glowstone, torches, ...) add radiance directly, colored by albedo.
        // Block lights have no extra NEE ray: the guided continuation gathers emission by actual hits (counted
        // at every vertex, no double counting). Small emitters are noisy, but temporal accumulation
        // converges static views.
        float emission = payloadEmission();
        if(payloadEmissionSource()==EMISSION_SOURCE_PORTAL) {
            L+=throughput*albedo*emission;
            break; // An unlit star window must not add a blue diffuse/NEE slab.
        }
        // An in-buffer emitter reached by a diffuse continuation ray was already counted by the previous
        // vertex's RIS, if that vertex actually ran it. Primary and specular/dielectric rays (showCelestial),
        // and paths whose last vertex skipped RIS (too deep, or no lights nearby), still gather it.
        bool risActive = risOn && diffuseDepth <= risMaxDepth;
        bool gateEmitter = risRanPrev && (payload.flags & PAYLOAD_EMITTER_IN_LIST) != 0u && !showCelestial;
        if (emission > 0.0 && !gateEmitter) {
            float3 emitterColor=albedo;
            if((worldPush.flags&1024u)!=0u) {
                uint source=payloadEmissionSource();
                if(source==6u)emitterColor=luminance(albedo)*pow(float3(1,.6,.25),float3(2.2))*1.52;
                else if(source==7u)emitterColor=luminance(albedo)*pow(float3(1,.2,.17),float3(2.2))*4.0;
            }
            L += throughput * emitterColor * emission * EMISSIVE_STRENGTH * worldPush.visualLight.x;
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (worldPush.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        float3 lightDir = worldPush.lightDir.xyz;
        float lightHalfAngle = worldPush.lightDir.w;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        float ndl = max(0.0, dot(n, lightDir));
        if (ndl > 0.0) {
            float3 vis = visibility(p, lightDir, 10000.0);
            // Underwater receiver whose shadow ray crossed a water surface: scale the direct light by
            // the wave-refraction caustic at the exit point. Focusing scales the incident irradiance,
            // so it applies to the whole NEE term (diffuse + specular).
            if (inWater && waterWaves && shadowVis.a > 0.0) {
                vis *= waterCaustic(p + lightDir * shadowVis.a, lightDir, shadowVis.a);
            }
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                float3 brdf = diffAlb * INV_PI; // Lambertian diffuse (f = albedo/PI)
                if (pbr) {
                    float3 h = normalize(lightDir + v);
                    float ndh = max(0.0, dot(n, h));
                    float ndv = max(1.0e-4, dot(n, v));
                    float vdh = max(0.0, dot(v, h));
                    float D = ggxD(ndh, rough);
                    float G = ggxG1(ndv, rough) * ggxG1(ndl, rough); // separable Smith masking
                    float3 F = fresnelSchlick(vdh, F0);
                    brdf += (D * G) * F / (4.0 * ndv * ndl); // Cook–Torrance specular
                }
                // Debug view 12: indirect only, the first visible surface gets no direct sun/moon.
                if (pc.debugView != 12u || bounce != gvBounce)
                    L += throughput * brdf * worldPush.lightRadiance.xyz * ndl * vis * localHeightVisibility;
            }
        }

        // ITRP sunlight rims (torchControl.y = strength, 0 off; ITRP overworld only). A visible surface that
        // faces away from the sun gets a thin band of sunlight where it ends in a convex edge whose far side
        // is lit: probe a short way along the sun direction projected onto the surface (ITRP: 0.05 block,
        // kept between 1 and 15 pixels), require that the face has ended there and nothing blocks the way,
        // then light the band from the edge point with the reference's 1.5x plant rim weight.
        float rimStrength = worldPush.torchControl.y;
        float rimNdl = dot(n, worldPush.lightDir.xyz);
        // Plants only (the reference's 0.1x terrain rim is not visible) and within 40 blocks, where a rim is
        // wider than a pixel; this keeps the probe rays to a small share of the backlit pixels.
        float rimFade = 1.0 - smoothstep(32.0, 40.0, payload.hitT);
        if (rimStrength > 0.0 && sss > 0.0 && rimFade > 0.0 && bounce == gvBounce && !firstPersonReceiver && rimNdl < 0.0
                && max(worldPush.lightRadiance.x, max(worldPush.lightRadiance.y, worldPush.lightRadiance.z)) > 0.0) {
            float3 along = worldPush.lightDir.xyz - n * rimNdl;
            float alongLen = length(along);
            if (alongLen > 1.0e-3) {
                along /= alongLen;
                float pixel = max(rayConeWidth, 1.0e-4);
                float width = clamp(0.05, pixel, pixel * 15.0);
                float stepFrac = lerp(1.0 / 3.0, 1.0, rndf(seed)); // stochastic step: soft band edge over frames
                // Faces are block aligned (rebased space keeps integer block boundaries): a face can only end
                // where the step crosses a block boundary, so every other pixel skips the probe rays.
                float toBoundary = 1.0e9;
                [unroll] for (int axis = 0; axis < 3; axis++) {
                    float dirA = along[axis];
                    if (abs(dirA) > 1.0e-4) {
                        float next = dirA > 0.0 ? floor(p[axis]) + 1.0 : floor(p[axis]);
                        toBoundary = min(toBoundary, (next - p[axis]) / dirA);
                    }
                }
                float3 above = p + n * 0.02;
                float3 probe = above + along * (width * stepFrac);
                bool faceEnded = toBoundary < width * stepFrac && !probeHit(probe, -n, 0.04);
                bool pathOpen = faceEnded && !probeHit(above, along, width * stepFrac);
                if (pathOpen) {
                    float back = -rimNdl;
                    float rimX = saturate(3.0 - stepFrac * 3.0);
                    float wgt = 3.0 / (stepFrac * stepFrac + 0.01);
                    float t = saturate(back * 5.0) * saturate(wgt - back * wgt) - 1.0;
                    float rimY = rimX * (1.0 - t * t);
                    float gain = rimY * 1.5 * rimStrength * rimFade;
                    if (gain > 0.0) {
                        float3 rimVis = visibilityMasked(probe, lightDir, 10000.0, secondaryReceiverMask); // no cloud march for a thin band
                        L += throughput * diffAlb * INV_PI * worldPush.lightRadiance.xyz * gain * rimVis;
                    }
                }
            }
        }

        L+=throughput*heldLighting(hitPos,n,v,diffAlb,F0,rough,pbr,heldHeightVisibility,inWater,waterExt,seed);

        // RIS direct lighting from block emitters: front lobe + SSS backlighting share ONE shadow ray (a light
        // is either in front of n or behind it). SSS only on the surfaces the sun SSS term also serves.
        bool risRan = false;
        if (risActive) {
            float activeSss = bounce <= MAX_SSS_BOUNCE ? sss : 0.0;
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, pbr, activeSss,
                    uint(diffuseDepth), seed, proposalSeed);
            risRan = r.M > 0.0;
            L += throughput * shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough, pbr, activeSss);
        }
        risRanPrev = risRan;
        diffuseDepth++;

        // Thin-surface SSS transmission. Light entering from the back face scatters through toward the
        // viewer via a forward-biased HG phase (leaves/grass backlit by the sun glow when you look
        // through them toward the light source). Shadow ray fires from the back face to avoid
        // self-occlusion. cosT = dot(lightDir, rd): rd tracks the ray toward the sun when the sun is
        // behind the slab, giving cosT ≈ 1 → forward-scatter peak.
        if (sss > 0.0 && bounce == gvBounce) { // the visible surface only: a deeper vertex's glow averages away
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                float3 visB = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                // flicker with the same light bands as the floor around it.
                if (inWater && waterWaves && shadowVis.a > 0.0) {
                    visB *= waterCaustic(hitPos + lightDir * shadowVis.a, lightDir, shadowVis.a);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * worldPush.lightRadiance.xyz * visB;
                }
            }
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance. Only the diffuse direction proposal changes; GGX and light energy are unchanged.
        bool psrMirror = bounce == gvBounce && (bounce==0 || bounce<maxBounces) && pbr && (worldPush.flags & FRAME_FLAG_MIRROR_PSR) != 0u
                && metal >= 0.99 && rough <= max(MIN_ROUGH, 0.06) && !firstPersonReceiver;
        if (psrMirror) {
            // Perfect reflection (no diffuse lobe on a full metal); the reflected vertex supplies the guides.
            float3 F = fresnelSchlick(max(0.0, dot(n, v)), F0);
            mirrorGuideAppend(psrTransform,hitPos-worldPush.camOffset,n);
            psrTint*=F;
            gvBounce=bounce+1;
            // Preserve the existing first-mirror allowance exactly. Later mirrors consume
            // the already configured path budget; never extend it per mirror.
            if(!gv_psr) { maxBounces+=1; rrStart+=1; }
            gv_psr=true;
            throughput *= F;
            ro = p;
            rd = reflect(rd, n);
            showCelestial = true;
        } else if (pbr) {
            float ps = max(F0.r,max(F0.g,F0.b))<=0.0 ? 0.0 : clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
            if (rndf(seed) < ps) {
                float3 h = sampleGGXVNDF(n, v, rough, seed);
                float3 l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break; // microfacet reflects below the surface: terminate this path
                }
                // VNDF + separable Smith ⇒ weight = F · G2/G1(v) = F · G1(l); divide by the lobe pdf ps.
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= F * ggxG1(ndl2, rough) / ps;
                ro = p;
                rd = l;
                rc_launchRough = rough;
                rayConeSpread = max(rayConeSpread, rough * rough * RAY_CONE_GLOSSY_SPREAD_SCALE);
                showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
            } else {
                float guideWeight;
                rd = torchGuidedDiffuse(p, n, seed, guideWeight);
                throughput *= diffAlb * guideWeight / (1.0 - ps);
                if (guideWeight <= 0.0) break;
                ro = p;
                rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
                showCelestial = false; // diffuse lobe: disc covered by NEE, hide it (anti-firefly)
            }
        } else {
            float guideWeight;
            rd = torchGuidedDiffuse(p, n, seed, guideWeight);
            throughput *= albedo * guideWeight;
            if (guideWeight <= 0.0) break;
            showCelestial = false; // diffuse bounce: hide the disc (covered by NEE)
            ro = p;
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
        }

        // Outgoing relief self-occlusion re-invokes the whole closest-hit shader. Only the camera surface keeps it:
        // at deeper vertices the micro-shadowing of one bounced direction averages out of the image, while the
        // extra hit-shader run per relief vertex per bounce was the largest cost parallax added.
        if(hasRelief && bounce==0 && bounce<maxBounces) {
            throughput*=outgoingReliefVisibility(receiverHit,rd,reliefCone,reliefCameraFlag,reliefDepth);
            if(max(throughput.x,max(throughput.y,throughput.z))<=0.0) break;
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart && !psrMirror) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    if (gv_psr) {
        // Compose all prefix mirror planes in order; do not fold about only the last plane.
        gv_hitCamRel=mirrorGuidePoint(psrTransform,gv_hitCamRel);
        gv_motionHitCamRel=mirrorGuidePoint(psrTransform,gv_motionHitCamRel);
        gv_motionObjDisp=mirrorGuideVector(psrTransform,gv_motionObjDisp);
        if(dot(gv_normal,gv_normal)>0.25) gv_normal=mirrorGuideNormal(psrTransform,gv_normal);
        gv_albedo*=psrTint;
        gv_specAlb*=psrTint;
    }
    // Update pass: each recorded vertex receives the radiance that left it along this path
    // (its own emission/direct light plus everything gathered after it), divided by its throughput.
    if (rc_update) {
        RcPtr<uint> accum = RcPtr<uint>(worldPush.cacheAccumAddr);
        for (uint i = 0u; i < rc_count; i++) {
            float3 tp = rc_throughput[i];
            if (min(tp.x, min(tp.y, tp.z)) <= 1.0e-6) continue;
            rcAccumulate(accum, rc_slot[i], (L - rc_before[i]) / tp);
        }
    }
    return L;
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    if(worldPush.environment.x==2.0) {
        float visible=endPlanetVisibility(worldPush.waterParams.w*15.0/max(worldPush.dimensionControl.x,1.0));
        worldPush.lightRadiance.xyz*=visible*visible;
    }
    // load the per-frame push data from the BDA buffer
    int2 pix = int2(DispatchRaysIndex().xy);
    float2 size = float2(DispatchRaysDimensions().xy);
    if (pc.debugView == SKY_LUT_MARKER) {
        // Sky LUT build: one exact miss evaluation (diffuse, no sun/moon disc) per texel.
        float3 lutDir = skyLutDir((float2(pix) + 0.5) / size);
        payload.hitT = -1.0;
        payloadSetTraceState(false, RAY_CONE_MIN_WIDTH, RAY_CONE_DIFFUSE_SPREAD);
        payload.flags |= PAYLOAD_SKY_LUT_BUILD;
        traceRadianceReordered(0u, worldPush.camOffset, 0.0, lutDir, 1.0); // empty mask: always a miss
        Ptr<float4>(worldPush.skyLutAddr)[pix.y * int(size.x) + pix.x] = float4(payload.albedo, 1.0);
        return;
    }
    rc_update = pc.debugView == RC_UPDATE_MARKER;
    if (rc_update) {
        // One random pixel per tile x tile block of the full render target.
        uint tile = max(uint(worldPush.cacheParams.w), 1u);
        uint tileSeed = (uint(pix.x) * 7919u + uint(pix.y) * 104729u) ^ (worldPush.frameIndex * 2654435761u);
        uint r = pcg(tileSeed);
        pix = pix * int(tile) + int2(int(r % tile), int((r / tile) % tile));
        size *= float(tile);
    }
    float2 uv = (float2(pix) + 0.5) / size;
    float2 ndc = uv * 2.0 - 1.0; // unjittered pixel centre — the motion vector reprojects this

    // Offset the primary ray within the pixel by the sub-pixel jitter reported to DLSS-RR, so
    // successive frames sample different positions and RR can resolve detail above render resolution.
    // The guide buffers below come from this jittered first hit (they must match the jittered colour);
    // only the motion vector stays on the unjittered centre, which DLSS expects.
    float2 jndc = (uv + worldPush.jitter / size) * 2.0 - 1.0;

    float4 nearH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;

    float3 origin = nearP + worldPush.camOffset;
    float3 dir = normalize(farP - nearP);
    float rayConeSpread = primaryRayConeSpread(jndc, size, dir);

    uint seed = (uint(pix.x) * 1973u + uint(pix.y) * 9277u + 26699u) ^ (worldPush.frameIndex * 2654435761u);

#ifdef CAUSTICA_DH
    dhCandidatePosition=float3(0);dhCandidateColor=float3(0);dhCandidateNormal=-dir;dhCandidateAlpha=0.0;
    dhCandidate=dhPrimarySurface(origin,dir,dhCandidatePosition,dhCandidateColor,dhCandidateNormal,dhCandidateAlpha);
#endif
    uint spp = max(worldPush.spp, 1u);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    for (uint s = 0u; s < spp; s++) {
        seed = pcg(seed); // decorrelate per-sample paths (shared camera ray, no AA jitter yet)
        glassSampleOrdinal=worldPush.frameIndex*spp+s;
        frameRadiance += tracePath(origin, dir, rayConeSpread, seed);
    }
    frameRadiance /= float(spp);
    if (rc_update) return; // cache population only: no color, guides or debug output

    // Hardware (non-linear, reversed-Z) depth for DLSS-RR + Frame Generation: project the camera-relative
    // primary hit through the forward view-projection and take ndc z/w — exactly the value a rasterizer
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag; sky's far hit -> ~0.
    // Depth follows the primary surface (water included); only the MV tracks the refracted hit.
    float4 curClip = mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0));
    gv_depth = curClip.w > 0.0 ? curClip.z / curClip.w : 0.0;

    // Guide buffers: first-hit attributes for the denoiser / DLSS-RR (written every frame).
    // Material roughness is already linear data (not an sRGB color channel).
    // Do not substitute GGX alpha: the SDK BRDF-albedo example squares material roughness separately.
    gNormal[pix] = float4(gv_normal, gv_rough);
    // Alpha: depth of the first visible surface, read by the raster overlay layer (LitematicaMaterials).
    float4 primaryClip = mul(worldPush.curViewProj, float4(gv_primaryCamRel, 1.0));
    float primaryDepth = gv_primaryValid && primaryClip.w > 0.0 ? primaryClip.z / primaryClip.w : 0.0;
    // A camera ray that reached the sky stores -(cloud transmittance): display.comp draws the night sky
    // there at full output resolution. Overlay depth readers clamp negatives to the far plane.
    if (!gv_primaryValid || gv_glassSky) primaryDepth = -max(gv_skyTransmittance, 1.0e-4);
    gAlbedo[pix] = float4(gv_albedo, primaryDepth);
    gDepth[pix] = gv_depth;
    gSpecAlbedo[pix] = float4(gv_specAlb, 1.0); // specular albedo for RR demodulation

    // Motion vector: reproject this guide point through the previous frame's view-projection.
    // The primary ray was cast through the JITTERED ndc (jndc), so this hit's current screen position is
    // jndc, NOT the pixel centre. Subtract jndc so the MV is jitter-free (= 0 when static), which is
    // what DLSS expects with MVJittered unset. Scaled from NDC into render-pixel space.
    // For a dynamic surface, previous-frame position is the current point minus its world-space
    // displacement; subtracting gv_motionObjDisp de-cameras the MV.
    float4 prevClip = mul(worldPush.prevViewProj, float4(gv_motionHitCamRel + worldPush.camDelta - gv_motionObjDisp, 1.0));
    float2 curNdc = jndc; // primary hit's current screen position is exactly the jittered ray ndc
    // RR (and FG) require primary depth and primary motion from the SAME surface.
    // Water's depth/normal are on the interface, not the refracted floor. Using floor motion
    // with interface depth invents a different parallax/depth history under camera translation.
    // Static fluid geometry has no vertex displacement; refraction only drives albedo guides.
    if (gv_motionUseRefracted) {
        float4 prevWaterClip = mul(worldPush.prevViewProj,
                float4(gv_hitCamRel + worldPush.camDelta, 1.0));
        prevClip = prevWaterClip;
        curNdc = jndc;
    }
    // An unprojectable previous surface has no history: use a finite off-screen reprojection,
    // rather than zero motion (which would falsely reuse the current pixel's stale history).
    float2 motion = checkedMotion(prevClip, curNdc, size, size * 2.0);
    gMotion[pix] = motion;
    // This extra trace only constructs an RR guide; it contributes no color/light.
    float2 specMotion = motion;
    if ((worldPush.flags & 2048u) != 0u && !gv_psr) {
        if(!gv_farLod)specMotion = specularReflectionMotion(gv_hitCamRel + worldPush.camOffset, dir, jndc, size, rayConeSpread, motion);
    }
    gSpecMotion[pix] = specMotion;

    // Debug guide-buffer visualization: bypass accumulation and show a guide directly.
    if (pc.debugView != 0u && pc.debugView < 10u) {
        float3 dbg;
        if (pc.debugView == 1u) {
            dbg = gv_normal * 0.5 + 0.5;            // world normal -> [0,1]
        } else if (pc.debugView == 2u) {
            dbg = gv_albedo;                        // diffuse albedo
        } else if (pc.debugView == 3u) {
            dbg = float3(gv_depth, gv_depth, gv_depth); // HW reversed-Z depth (near=white, far/sky=black)
        } else if (pc.debugView == 4u) {
            dbg = float3(gv_rough, gv_rough, gv_rough); // roughness
        } else if (pc.debugView == 6u) {
            dbg = gv_specAlb;                       // specular albedo
        } else if (pc.debugView == 7u) {
            dbg = float3(clamp(0.5 + specMotion * 0.05, 0.0, 1.0), 0.5); // reflection motion
        } else if (pc.debugView == 8u) {
            dbg = float3(gv_emission, gv_emission, gv_emission); // resolved emission mask/strength
        } else if (pc.debugView == 9u) {
            float3 sourceColor = gv_emissionSource == EMISSION_SOURCE_OVERRIDE ? float3(1.0, 0.2, 1.0)
                    : gv_emissionSource == EMISSION_SOURCE_LAB_PBR ? float3(0.15, 0.8, 1.0)
                    : gv_emissionSource == EMISSION_SOURCE_HEURISTIC ? float3(1.0, 0.45, 0.05)
                    : gv_emissionSource == EMISSION_SOURCE_UNIFORM ? float3(1.0, 1.0, 1.0)
                    : float3(0.0, 0.0, 0.0);
            dbg = sourceColor * (gv_emissionSource == EMISSION_SOURCE_NONE
                    ? 0.0 : max(0.2, clamp(gv_emission, 0.0, 1.0)));
        } else {
            dbg = float3(clamp(0.5 + motion * 0.05, 0.0, 1.0), 0.5); // motion: red=+x, green=+y
        }
        outImage[pix] = float4(dbg, 1.0);
        return;
    }

    // Single noisy estimate; the denoiser (DLSS-RR) handles temporal convergence.
    // The storage image is RGBA16F. Preserve representable HDR values and saturate only overflow
    // peaks from near-field microfacet highlights instead of feeding infinities into DLSS RR.
    outImage[pix] = float4(clamp(frameRadiance,float3(0,0,0),float3(65504,65504,65504)), 1.0);
}
