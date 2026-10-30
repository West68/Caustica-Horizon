// Painted night sky: a Milky Way band overhead with a warm core and dust lanes, coloured nebular glow
// along it and occasional meteors. Evaluated only for rays that see the sky directly (camera, mirrors, glass, water) - the diffuse
// sky LUT never calls it - so the cost is a few dozen ALU ops on visible sky pixels.

float nsSq(float x) { return x * x; }

float nsHash(float3 p) {
    p = frac(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return frac((p.x + p.y) * p.z);
}

// Smooth value noise on the unit sphere's ambient 3D space.
float nsNoise(float3 p) {
    float3 i = floor(p), f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = lerp(nsHash(i), nsHash(i + float3(1, 0, 0)), f.x);
    float b = lerp(nsHash(i + float3(0, 1, 0)), nsHash(i + float3(1, 1, 0)), f.x);
    float c = lerp(nsHash(i + float3(0, 0, 1)), nsHash(i + float3(1, 0, 1)), f.x);
    float d = lerp(nsHash(i + float3(0, 1, 1)), nsHash(i + float3(1, 1, 1)), f.x);
    return lerp(lerp(a, b, f.y), lerp(c, d, f.y), f.z);
}

float nsFbm(float3 p) {
    return 0.55 * nsNoise(p) + 0.3 * nsNoise(p * 2.13 + 7.1) + 0.15 * nsNoise(p * 4.37 + 3.3);
}

// sdir: sky-fixed direction (already rotated with the star field). Returns HDR radiance in the same
// units as the star field (peak star ~0.2 at full starBrightness).
// Built only from broad, smooth structure (features of a few degrees and up): the path tracer runs at
// a fraction of the output resolution before DLSS, so sub-degree detail would only turn into grain.
public float3 milkyWay(float3 sdir) {
    const float3 G = float3(0.9403, 0.0, 0.3402);      // galactic pole, horizontal: the band crosses the zenith
    const float3 C = float3(-0.2790, 0.5700, 0.7723);  // galactic centre, ~35 degrees up, in the plane
    float b = dot(sdir, G);                            // sin(galactic latitude)
    float core = exp(-nsSq(acos(clamp(dot(sdir, C), -1.0, 1.0))) * 2.4);
    float width = 0.17 + 0.13 * core;                  // the band swells around the bulge
    float band = exp(-b * b / (width * width));
    float halo = exp(-b * b / (0.42 * 0.42));          // wide faint glow the nebulae can drift into
    if (halo < 0.01) return float3(0.0, 0.0, 0.0);

    // Domain-warped fbm: soft swirls and filaments like a long-exposure photograph.
    float3 q = sdir * 2.2;
    float3 warp = float3(nsFbm(q + 1.7), nsFbm(q + 9.2), nsFbm(q + 4.4)) - 0.5;
    float3 p = sdir * 3.0 + warp * 1.4;
    float clouds = nsFbm(p * 1.5);
    float glow = band * (0.4 + 0.6 * clouds) * (0.55 + 1.5 * core);
    // Great Rift: a broad dark lane wandering along the mid-plane, deepest across the bulge.
    float rift = exp(-nsSq((b + 0.07 * (nsFbm(p) - 0.5)) / (0.05 + 0.035 * core)));
    glow *= 1.0 - 0.72 * rift * smoothstep(0.3, 0.7, nsFbm(p * 2.0 + 3.0));

    // Colour: warm gold bulge fading into cool star-cloud blue along the arms, with large nebular
    // regions of rose, teal, violet and amber blending softly into one another.
    float3 color = lerp(float3(0.58, 0.70, 1.0), float3(1.0, 0.82, 0.58), saturate(core * 1.3));
    float3 m = float3(nsFbm(p * 0.9 + 11.0), nsFbm(p * 0.8 + 23.0), nsFbm(p * 1.0 + 37.0));
    float3 nebula = float3(1.0, 0.36, 0.56) * smoothstep(0.42, 0.78, m.x)
            + float3(0.30, 0.86, 0.95) * smoothstep(0.45, 0.80, m.y)
            + float3(0.64, 0.42, 1.0) * smoothstep(0.40, 0.76, m.z)
            + float3(1.0, 0.66, 0.30) * smoothstep(0.55, 0.85, (m.x + m.z) * 0.5) * core;
    float nebulaMask = lerp(halo, band, 0.55) * (0.35 + 0.65 * clouds);
    return color * glow * 0.03 + nebula * nebulaMask * 0.022;
}

// One meteor per lucky 5-second slot, crossing about 14 degrees in 0.9 s with a fading tail.
public float3 meteor(float3 dir, float time) {
    float slot = floor(time / 5.0);
    if (nsHash(float3(slot, 1.7, 9.2)) > 0.3) return float3(0.0, 0.0, 0.0);
    float age = time - slot * 5.0 - 5.0 * nsHash(float3(slot, 4.1, 2.3)) * 0.8;
    if (age < 0.0 || age > 0.9) return float3(0.0, 0.0, 0.0);
    float az = nsHash(float3(slot, 7.7, 1.1)) * 6.2832, el = 0.35 + 0.6 * nsHash(float3(slot, 2.2, 8.8));
    float3 start = float3(cos(az) * cos(el), sin(el), sin(az) * cos(el));
    float3 side = normalize(cross(start, float3(0.0, 1.0, 0.0)));
    float3 travel = normalize(lerp(side, float3(0.0, -1.0, 0.0), 0.45));
    float3 head = normalize(start + travel * (0.25 * age / 0.9));
    float3 rel = dir - head;
    float along = dot(rel, -travel);                   // >0 behind the head
    float across = length(rel + travel * along);
    if (along < -0.004 || along > 0.07) return float3(0.0, 0.0, 0.0);
    float tail = exp(-max(along, 0.0) * 55.0) * exp(-across * across * 2.5e5);
    float life = smoothstep(0.0, 0.12, age) * smoothstep(0.9, 0.55, age);
    float3 hue = lerp(float3(1.0, 0.92, 0.75), float3(0.6, 1.0, 0.75), nsHash(float3(slot, 3.3, 3.3)));
    return hue * tail * life * 0.9;
}
