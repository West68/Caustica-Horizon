// Ray miss = sky. Per-frame sky data comes from the WorldPush BDA buffer addressed by the 8-byte push
// constant. The shader writes full sky radiance (Rayleigh/Mie/ozone atmosphere + transmittance-tinted
// sun/moon discs + soft horizon + starfield) into payload.albedo; raygen consumes that value for both
// accumulated radiance and bounce-0 denoiser guides.
//
// Celestial discs (sun/moon) are gated by a packed payload flag, which raygen sets per-ray from the path
// state: true on the primary ray and after every specular/dielectric bounce (so the sun/moon show up in
// water/glass/metal reflections + refractions), false after a diffuse vertex did sun/moon NEE (so the
// disc isn't double-counted against the direct light → fireflies). The atmosphere gradient + stars are
// added on every miss (any bounce): stars aren't a NEE light, so they never double-count.
import world_common;
import environment_effects;
import end_environment;
import night_sky;
import itrp_clouds;
import itrp_atmosphere;

[[vk::push_constant]] WorldPushConstants pc;

// Night sky texture (NightSkyTexture): Milky Way equirect in the top 2/3, the moon map (rgb colour,
// a elevation) in the left half below. Until it is baked the binding holds the celestials atlas, which
// lacks the marker texel, so skyTextureReady() is false and the procedural fallbacks are drawn.
[[vk::binding(9, 0)]] Sampler2D celestialsAtlas;
bool skyTextureReady() {
    float4 m = celestialsAtlas.SampleLevel(float2(0.75, 0.84), 0.0);
    return m.a < 0.02 && m.r > 0.98 && m.g < 0.02 && m.b > 0.98;
}
float4 moonMap(float2 uv, float lod) {
    return celestialsAtlas.SampleLevel(float2(frac(uv.x) * 0.5, 2.0 / 3.0 + clamp(uv.y, 0.002, 0.998) / 3.0), lod);
}

static const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

// Near-white top-of-atmosphere disc radiances: the visible warm/red tint comes from multiplying by
// transmittanceToSpace(dir) at draw time — at noon the sun reads warm-white, at the horizon it reddens
// on exactly the sky's own sunset curve.
static const float3 SUN_DISC_RADIANCE  = float3(24.0, 23.0, 21.5);
static const float3 MOON_DISC_RADIANCE = float3(1.7, 1.85, 2.2); // lit-side moon (HDR, bright at night exposure)
// Vibrance applied to the atmosphere in-scatter only (not discs/stars/night ambient): luminance-
// preserving, so GI energy is unchanged.
static const float SKY_SATURATION = 1.2;
// VISIBLE disc half-angles, matched to vanilla: the sun/moon quads are drawn at half-width 30/20 at
// distance 100 → half-angle = atan(0.30) / atan(0.20). Decoupled from the NEE light radius (≈0.6°).
static const float SUN_DISC_HALF_ANGLE  = 0.29146; // atan(30/100), vanilla sun size
static const float MOON_DISC_HALF_ANGLE = 0.19740; // atan(20/100), vanilla moon size
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.
static const float3 NIGHT_ZENITH  = float3(0.004, 0.008, 0.022) / 4.0;
static const float3 NIGHT_HORIZON = float3(0.015, 0.022, 0.045) / 4.0;

// ---- Physically-based atmosphere (Nishita / O'Neil single scattering + ozone absorption). A view ray
// is marched through the atmospheric shell; at each step the transmittance toward the sun is integrated
// (a second, shorter march), giving Rayleigh (blue-sky / red-sunset) + Mie (sun halo) in-scattered
// radiance. World up = +Y; the camera sits a couple km above a spherical planet. Output is HDR in the
// same units as tracePath.
static const float  PLANET_R   = 6371000.0;
static const float  ATMOS_R    = 6471000.0;        // 100 km shell
static const float3 RAY_BETA   = float3(5.5e-6, 13.0e-6, 22.4e-6); // Rayleigh scattering at sea level
static const float  MIE_BETA   = 21.0e-6;          // Mie scattering at sea level
static const float  RAY_SCALE_H = 8000.0;          // Rayleigh density scale height
static const float  MIE_SCALE_H = 1200.0;          // Mie density scale height
static const float  MIE_G      = 0.758;            // Mie anisotropy (forward-scattering)
// Ozone: absorption only (no scattering), tent-shaped density peaking at 25 km. Its green-heavy
// absorption keeps the zenith deep blue at noon and turns twilight purple-orange.
static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
static const float  OZONE_CENTER_H = 25000.0;
static const float  OZONE_WIDTH_H  = 15000.0;
static const float  SUN_INTENSITY = 22.0;
static const int    ATMOS_PRIMARY_STEPS = 16;
static const int    ATMOS_LIGHT_STEPS   = 8;

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H); }

// Smaller root of ray vs sphere of radius r centred at origin (no-hit handled by x > y).
float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

float3 atmosphere(float3 dir, float3 sunDir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);      // ray misses the atmosphere entirely
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x); // clip the march at the planet surface
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = MIE_G;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;            // view-ray optical depth accumulators
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        // March toward the sun to accumulate the light-ray optical depth (transmittance to space).
        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; } // sun is below this point's horizon → in shadow
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 tau = RAY_BETA * (odR + odRL) + MIE_BETA * 1.1 * (odM + odML)
                       + OZONE_BETA * (odO + odOL);
            float3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}

// Extinction from the camera to space along `dir` (Rayleigh + Mie + ozone; the sun-below-horizon case
// needs no explicit planet test — a grazing/downward path picks up enormous optical depth, so the
// transmittance rolls to zero smoothly on its own). Tints the sun/moon discs at draw time, and is ported
// verbatim to RtComposite.writeSky so the NEE sunlight on terrain follows the identical sunset curve —
// the disc, the sky, and the light can never disagree.
float3 transmittanceToSpace(float3 dir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.y <= 0.0) return float3(1.0, 1.0, 1.0);
    float segLen = t.y / float(ATMOS_LIGHT_STEPS);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = orig + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / RAY_SCALE_H) * segLen;
        odM += exp(-h / MIE_SCALE_H) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return exp(-(RAY_BETA * odR + MIE_BETA * 1.1 * odM + OZONE_BETA * odO));
}

// Build the celestial body's square tangent frame: `right` along the arc-travel direction, `up`
// perpendicular within the sky. Shared by the visible disc and the NEE square sampling in raygen.
void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

// Gnomonic projection of `dir` into the body's tangent square, in units of the half-angle. local ∈
// [-1,1]² is inside the square. Returns 0 behind the body. The square (not a cone) matches MC's quad.
float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;                  // d·bodyDir == 1 (point on the tangent plane)
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m); // soft square edge
}

// Dave Hoskins' hash13 (https://www.shadertoy.com/view/4djSRW): well-distributed and pattern-free.
float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// Rotate v about a unit axis by angle (Rodrigues). Used to wheel the starfield about the celestial pole.
float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Procedural starfield, a faithful port of vanilla's buildStars(): ~1500 discrete stars at random
// sphere directions, each a small SQUARE billboard of random size + random rotation. A cube-map grid is
// the spatial accelerator — at most one star per cell, placed at a hashed position INSET from the cell
// edges (so its square never crosses into a neighbour), drawn as a hash-rotated square of hashed size.
// The lookup direction is rotated about the celestial pole by -STAR_ANGLE so the field wheels with
// world time like the real sky.
static const float STAR_CELLS     = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
static const float STAR_THRESHOLD = 0.86;  // cell occupancy cutoff (higher = fewer stars)
static const float STAR_HALF      = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at center)
float3 stars(float3 dir, float starBrightness, float VdotS, float4 celestial) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return float3(0.0, 0.0, 0.0);
    // Negative angle: rotating the LOOKUP direction is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way as the sun/moon (whose world dirs are
    // forward-rotated on the CPU).
    float3 sdir = rotateAxis(dir, normalize(celestial.xyz), -celestial.w);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return float3(0.0, 0.0, 0.0); // no star in this cell
    // Per-star randoms: size, centre (inset so the rotated square stays inside the cell), rotation.
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // keeps the rotated square clear of the cell edge
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;        // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y); // GLSL mat2(cs,-sn,sn,cs) * q, written out
    float box = max(abs(q.x), abs(q.y));               // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);  // soft-edged square (helps DLSS stability)
    if (star <= 0.0) return float3(0.0, 0.0, 0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);    // mild per-star brightness variation
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    // Per-star colour temperature: hash picks a spot on a warm-orange ↔ blue-white ramp (real star
    // populations). Average luminance close to the old uniform constant so night exposure doesn't shift.
    float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return 0.2 * star * starCol * starBrightness;
}

// Value noise / fbm for the moon's albedo map, on the moon's own unit sphere.
float moonNoise(float3 p) {
    float3 i = floor(p), f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    return lerp(lerp(lerp(hash13(i), hash13(i + float3(1, 0, 0)), f.x),
                     lerp(hash13(i + float3(0, 1, 0)), hash13(i + float3(1, 1, 0)), f.x), f.y),
                lerp(lerp(hash13(i + float3(0, 0, 1)), hash13(i + float3(1, 0, 1)), f.x),
                     lerp(hash13(i + float3(0, 1, 1)), hash13(i + float3(1, 1, 1)), f.x), f.y), f.z);
}
float moonFbm(float3 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { s += a * moonNoise(p); p = p * 2.07 + 3.1; a *= 0.5; }
    return s / 0.96875;
}
// One crater per cell at this scale: dark floor, bright raised rim, some with a bright fresh halo.
float moonCraters(float3 n, float scale, float seed) {
    float3 g = n * scale, c = floor(g);
    float h = hash13(c + seed);
    if (h < 0.45) return 0.0;
    float3 centre = c + 0.5 + (float3(hash13(c + seed + 7.0), hash13(c + seed + 13.0), hash13(c + seed + 19.0)) - 0.5) * 0.3;
    float rr = 0.12 + 0.22 * hash13(c + seed + 29.0);
    float d = length(g - centre) / rr;
    float floorDark = -0.18 * (1.0 - smoothstep(0.55, 0.9, d));
    float rim = 0.22 * smoothstep(0.7, 0.95, d) * (1.0 - smoothstep(1.0, 1.35, d));
    return (floorDark + rim) * (h > 0.9 ? 1.6 : 1.0);
}

static const float SUN_BODY_RADIUS = 0.30;  // fraction of the vanilla quad half-width
static const float MOON_BODY_RADIUS = 0.30;

// Round sun with solar limb darkening (I = 1 - u(1 - mu), redder toward the limb) and an anti-aliased edge.
float3 realSun(float2 local) {
    float r = length(local) / SUN_BODY_RADIUS;
    if (r >= 1.02) return float3(0.0, 0.0, 0.0);
    float mu = sqrt(max(1.0 - r * r, 0.0));
    float3 limb = 1.0 - float3(0.50, 0.62, 0.78) * (1.0 - mu);
    return limb * (1.0 - smoothstep(0.97, 1.02, r));
}

// Realistic moon from the LRO maps: colour albedo, and the LOLA elevation turned into a bump normal so
// craters and mountains cast relief along the terminator. Lommel-Seeliger lighting for the current
// phase (0 full .. 4 new .. 7) plus faint earthshine. Returns linear RGB, ~1 for the bright highlands.
// Falls back to the procedural surface until the texture is baked.
float3 realMoon(float2 local, float phase, bool mapped) {
    float2 q = local / MOON_BODY_RADIUS;
    float r = length(q);
    if (r >= 1.02) return float3(0.0, 0.0, 0.0);
    float3 n = float3(q.x, q.y, sqrt(max(1.0 - r * r, 0.0)));
    float a = phase * 0.78539816;
    float3 L = float3(sin(a), 0.0, cos(a));
    float3 albedo;
    float3 nb = n;
    if (mapped) {
        float lat = asin(clamp(n.y, -1.0, 1.0)), lon = atan2(n.x, n.z);
        float2 uv = float2(0.5 + lon / 6.2831853, 0.5 - lat / 3.1415927);
        float4 t = moonMap(uv, 1.5);   // ~1 texel per render pixel at the default size
        albedo = pow(t.rgb, 2.2) / 0.219 * 0.85;          // near-side mean albedo -> 0.85
        const float E = 3.0 / 2048.0;
        float hE = moonMap(uv + float2(E, 0.0), 1.5).a - moonMap(uv - float2(E, 0.0), 1.5).a;
        float hN = moonMap(uv - float2(0.0, E), 1.5).a - moonMap(uv + float2(0.0, E), 1.5).a;
        float3 east = float3(cos(lon), 0.0, -sin(lon));
        float3 north = float3(-sin(lat) * sin(lon), cos(lat), -sin(lat) * cos(lon));
        nb = normalize(n - 2.0 * (hE / max(cos(lat), 0.05)) * east - 2.0 * hN * north);
    } else {
        float maria = smoothstep(0.50, 0.66, moonFbm(n * 1.6 + float3(4.0, 1.0, 7.0)));
        albedo = (0.80 + 0.22 * (moonFbm(n * 9.0) - 0.5)) * (1.0 - 0.42 * maria) + moonCraters(n, 7.0, 1.0);
    }
    float mu0 = max(dot(nb, L), 0.0), mu = max(n.z, 1.0e-3);
    float lit = 2.0 * mu0 / (mu0 + mu) * smoothstep(-0.02, 0.06, dot(n, L));
    return max(albedo, 0.02) * (lit + 0.006) * (1.0 - smoothstep(0.97, 1.02, r));
}

[shader("miss")]
void main(inout Payload payload) {
    ConstPtr<WorldPush> worldPushPtr = ConstPtr<WorldPush>(pc.worldPushAddr);
    float3 dir = normalize(WorldRayDirection());
    // Diffuse continuations integrate the sky over a cosine lobe: the per-frame LUT (same miss shader,
    // evaluated per texel) replaces the per-ray atmosphere/cloud march. Camera, specular and refracted
    // rays (show-celestial) still evaluate the exact sky with sun/moon discs.
    // ~96% of misses take this path; read only its three fields instead of loading the whole
    // WorldPush (hundreds of bytes) first. Same values, so the same radiance.
    uint64_t skyLutAddr = (payload.flags & (PAYLOAD_SHOW_CELESTIAL|PAYLOAD_CAMERA_MISS|PAYLOAD_SKY_LUT_BUILD|PAYLOAD_REFLECTION_GUIDE))==0u
            ? worldPushPtr[0].skyLutAddr : 0uL;
    if(skyLutAddr!=0uL) {
        payload.albedo=max(sampleSkyLut(skyLutAddr,worldPushPtr[0].skyLutWidth,worldPushPtr[0].skyLutHeight,dir),float3(0,0,0));
        payload.hitT=-1;payload.normal=float3(0,0,0);payload.motionPrev=float3(0,0,0);
        payload.f0=float3(0,0,0);payload.flags=0;payload.roughMetal=packHalf2(float2(1,0));
        payload.emissionSss=0;payload.iorTransmission=packHalf2(float2(1,0));payload.rayCone=0;return;
    }
    WorldPush worldPush = worldPushPtr[0];
    if(worldPush.environment.x==1.0 || worldPush.environment.x==2.0) {
        float skyTime=worldPush.environment.x==2.0?worldPush.waterParams.w*15.0/max(worldPush.dimensionControl.x,1.0):worldPush.waterParams.w;
        payload.albedo=dimensionRadiance(dir,worldPush.environment.x,skyTime,worldPush.dimensionFog.xyz*worldPush.dimensionControl.y,worldPush.cloudNoiseAddr,(payload.flags & PAYLOAD_SHOW_CELESTIAL)!=0u,worldPush.visualLight.z,worldPush.visualLight.y);
        if(worldPush.environment.x==2.0)payload.albedo*=worldPush.visualLight.w;
        payload.hitT=-1;payload.normal=float3(0,0,0);payload.motionPrev=float3(0,0,0);
        payload.f0=float3(0,0,0);payload.flags=0;payload.roughMetal=packHalf2(float2(1,0));
        payload.emissionSss=0;payload.iorTransmission=packHalf2(float2(1,0));payload.rayCone=0;return;
    }
    float day = worldPush.sunDir.w;
    float3 sd = worldPush.sunDir.xyz;
    float SdotU = sd.y;
    float VdotS = dot(dir, sd);
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0); // 0 well below horizon .. 1 above
    float starBrightness = worldPush.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    // Physically-based in-scattering: blue zenith, bright/desaturated horizon, warm Mie halo + red
    // sunset all fall out of the Rayleigh/Mie/ozone march. At night the sun is below the horizon so the
    // light march is blocked → near-zero; a faint night ambient + the rotating starfield sit underneath.
    //
    // Soft horizon: below-horizon rays used to march into the planet-surface clip, drawing a hard fixed
    // line across the sky. Instead, march the ray flattened onto the horizon (same azimuth, y=0) so the
    // gradient continues seamlessly downward, with an exponential falloff so it settles into darkness
    // instead of a uniform bright band.
    float3 mdir = dir;
    float downFade = 1.0;
    if (dir.y < 0.0) {
        float2 flat2 = float2(dir.x, dir.z);
        float fl = length(flat2);
        mdir = fl > 1.0e-4 ? float3(flat2.x / fl, 0.0, flat2.y / fl) : float3(1.0, 0.0, 0.0);
        downFade = exp(dir.y * 3.0);
    }
    float3 col = atmosphere(mdir, sd);
    // Luminance-preserving vibrance on the in-scatter only (see SKY_SATURATION).
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = max(lerp(float3(lum, lum, lum), col, SKY_SATURATION), float3(0.0, 0.0, 0.0));
    float up = clamp(dir.y, 0.0, 1.0);
    float3 nightAmbient = lerp(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col *= downFade;
    bool referenceLook=(worldPush.flags & 512u)!=0u;
    float3 referenceAtmosphere=float3(0.0);
    if(referenceLook) {
        referenceAtmosphere=itrpReferenceSky(dir,sd,worldPush.cloudWorld.w);
        col=referenceAtmosphere*ITRP_SCENE_SKY_SCALE;
    }
    col += stars(dir, starBrightness, VdotS, worldPush.celestial);
    // Painted night sky (night_sky.slang): Milky Way, nebular colour and meteors, wheeling with the
    // stars and dimmed by the atmosphere, rain and, below, the cloud deck. Only rays that see the sky
    // itself evaluate it; the diffuse sky LUT returned before reaching here.
    // starBrightness < 0: the ITRP look hides the point stars but it is still night (RtComposite).
    float nightStrength = abs(starBrightness);
    // Camera rays get the Milky Way at full output resolution in display.comp; only reflections and
    // refractions (glass, water, mirrors) draw this render-resolution copy.
    bool cameraSky = (payload.flags & PAYLOAD_CAMERA_MISS) != 0u;
    if (nightStrength > 0.0 && dir.y > 0.0 && showCelestial && !cameraSky) {
        float3 skyDir = rotateAxis(dir, normalize(worldPush.celestial.xyz), -worldPush.celestial.w);
        float clear = 1.0 - saturate(worldPush.cloudWorld.w);
        // Same dark-sky treatment and the same baked Milky Way as the direct view (display.comp), so
        // water and glass reflect what the player sees overhead.
        col *= 1.0 - 0.72 * saturate(nightStrength);
        float3 galaxy;
        if (skyTextureReady()) {
            float u = atan2(skyDir.z, skyDir.x) / 6.2831853 + 0.5, v = acos(clamp(skyDir.y, -1.0, 1.0)) / 3.1415927;
            galaxy = pow(celestialsAtlas.SampleLevel(float2(u, v * (2.0 / 3.0)), 2.0).rgb, 2.2) * (3.0 * 0.10);
        } else {
            galaxy = milkyWay(skyDir);
        }
        if ((lookSwitches(worldPush.torchControl.w) & LOOK_SWITCH_NO_NEBULA) != 0u) galaxy = float3(0.0, 0.0, 0.0);
        col += (galaxy + meteor(dir, worldPush.waterParams.w)) * nightStrength
                * clear * smoothstep(0.0, 0.18, dir.y) * exp(-0.06 / max(dir.y, 0.03));
    }

    // Sun & moon: round, physically shaded bodies instead of the vanilla square sprites. squareBody gives
    // the body-local tangent coordinate (in units of the vanilla quad half-width); the round body fills
    // the bright core of that quad. Visible appearance only: NEE keeps its own small light radius.
    // Night brightness (slider, packed above the glow in torchControl.w) dims the night sky that lights the
    // outdoors, so moonlit ground no longer outshines a torch-lit room; the moon disc below keeps its look.
    float glowAndNight = lookGlowNight(worldPush.torchControl.w);
    float nightDim = floor(glowAndNight / 4.0) / 100.0;
    col *= lerp(1.0, nightDim, saturate(nightStrength));
    if (showCelestial) {
        bool skyReady = skyTextureReady();
        float2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        // torchControl.z: visible size multiplier, negative for the square shape; .w: glow strength.
        float sunScale = abs(worldPush.torchControl.z);
        local /= max(sunScale, 0.01);
        float3 sunDisc;
        if (worldPush.torchControl.z < 0.0) {
            float edge = max(abs(local.x), abs(local.y));
            sunDisc = float3(1.0 - smoothstep(SUN_BODY_RADIUS * 0.97, SUN_BODY_RADIUS * 1.02, edge));
        } else {
            sunDisc = realSun(local);
        }
        // Forward scattering in the air around the sun, the same two-lobe shape as the moon's aureole below.
        float sunGlow = glowAndNight - 4.0 * floor(glowAndNight / 4.0);
        if (sunGlow > 0.0 && sunUp > 0.0) {
            float a2 = max(2.0 * (1.0 - dot(dir, sd)), 0.0) / max(sunScale * sunScale, 0.01);
            col += SUN_DISC_RADIANCE * transmittanceToSpace(dir) * (0.05 * exp(-a2 / 0.02) + 0.012 * exp(-a2 / 0.18)) * sunGlow * sunUp;
        }
        // transmittanceToSpace reddens+dims the disc through the sunset on the sky's own curve.
        if (any(sunDisc > 0.0)) col += SUN_DISC_RADIANCE * transmittanceToSpace(dir) * sunDisc * sunUp;
        // Same horizon gate as the sun: with the soft-horizon change, below-horizon rays no longer clip
        // at the planet, so without this the moon disc would stay visible after it set.
        float moonUp = clamp((worldPush.moonDir.y + 0.0625) / 0.125, 0.0, 1.0);
        if (moonUp > 0.0) {
            float night = pow2(1.0 - day) * moonUp;
            float3 moonT = transmittanceToSpace(dir);
            squareBody(dir, worldPush.moonDir.xyz, MOON_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
            float3 moonA;
            if (worldPush.torchControl.z < 0.0) {
                float moonEdge = max(abs(local.x), abs(local.y));
                // The whole moon inside the square, with its texture and phase; 0.7 keeps the corners within the disc.
                moonA = realMoon(local * 0.7, worldPush.moonDir.w, skyReady) * (1.0 - smoothstep(MOON_BODY_RADIUS * 0.97, MOON_BODY_RADIUS * 1.02, moonEdge));
            } else {
                moonA = realMoon(local, worldPush.moonDir.w, skyReady);
            }
            if (any(moonA > 0.0)) col += MOON_DISC_RADIANCE * 0.2 * moonT * moonA * night; // lit side stays below clipping so the surface reads
            // Aureole: forward scattering in the air around the moon, scaled by how much of it is lit.
            float lit = 1.0 - abs(worldPush.moonDir.w - 4.0) / 4.0;
            float a2 = max(2.0 * (1.0 - dot(dir, worldPush.moonDir.xyz)), 0.0);
            col += MOON_DISC_RADIANCE * moonT * (0.02 * exp(-a2 / 0.012) + 0.006 * exp(-a2 / 0.10)) * sunGlow * lit * night;
        }
    }

    // ITRP-PTGI blocky cloud deck, composited over the atmosphere before the celestial discs are added
    // so a sun/moon behind a thick cloud is occluded exactly as the cloud alpha says. Mode rides the
    // free bits 7..8 of worldPush.flags: 0 = off (no cost, output bit-identical to before), 1 = the
    // cheaper step counts, 2 = the higher ones. Both share the same shape, colour and weather response.
    {
        uint cloudMode = (worldPush.flags >> 7u) & 3u;
        if (cloudMode != 0u) {
            float rain=worldPush.cloudWorld.w;
            if(!referenceLook) referenceAtmosphere=itrpReferenceSky(dir,sd,rain);
            float3 cloudAmbient=itrpReferenceSky(float3(0.0,1.0,0.0),sd,rain);
            col=itrpCloudPlane(worldPush.cloudNoiseAddr,col,dir,worldPush.lightDir.xyz,sd,
                cloudAmbient,referenceAtmosphere,worldPush.cloudWorld.xyz,worldPush.waterParams.w,rain,int(cloudMode),(payload.flags & PAYLOAD_CAMERA_MISS)!=0u,worldPush.cloudShape,worldPush.cloudLight,worldPush.cloudDetail);
        }
    }
    float skyTransmittance = itrpCloudTransmittance; // read before the payload reset below
    payload.albedo = max(col, float3(0.0, 0.0, 0.0)); // raygen reads this as sky radiance + bounce-0 guide
    payload.hitT = -1.0;
    payload.normal = float3(0.0, 0.0, 0.0);
    payload.motionPrev = float3(0.0, 0.0, 0.0);
    payload.f0 = float3(0.0, 0.0, 0.0);
    payload.f0.x = (worldPush.flags >> 7u & 3u) != 0u ? skyTransmittance : 1.0; // cloud cover for display.comp
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.iorTransmission = packHalf2(float2(1.0, 0.0));
    payload.rayCone = 0u;
}
