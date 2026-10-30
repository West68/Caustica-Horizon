package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * The painted Milky Way as a 4096x2048 equirectangular texture (sky-fixed frame, +Y up), baked once on
 * background threads and sampled by display.comp at full output resolution. Baking offline is what lets it
 * carry many octaves of structure - filamentary dust, emission nebulae, resolved star clouds - that a
 * per-ray evaluation at render resolution could only turn into grain.
 *
 * <p>Encoding: rgb = (radiance / {@link #PEAK})^(1/2.2) in RGBA8; display.comp inverts it.
 *
 * <p>The texture is {@link #HEIGHT} rows: the Milky Way fills the top {@link #GALAXY_HEIGHT}, and the
 * moon map (NASA SVS CGI Moon Kit: LRO LROC colour in rgb, LOLA elevation in alpha; public domain)
 * fills the left half of the rows below, so world.rmiss can draw both from one binding. The rest of
 * the moon rows hold a marker (magenta, alpha 0) the shader uses to tell this texture is bound.
 */
public final class NightSkyTexture {
    public static final int WIDTH = 4096, GALAXY_HEIGHT = 2048, HEIGHT = 3072;
    private static final String MOON_MAP = "/assets/caustica/textures/environment/moon_rgbh.png";
    /** Radiance stored as 1.0 (display.comp multiplies the decoded value back by this). */
    public static final float PEAK = 3.0f;
    private static CompletableFuture<List<byte[]>> bake;
    private static RtMaterialPageTexture texture;

    /** Starts the bake once; returns the uploaded view, or 0 until it is ready. Render thread. */
    public static long viewOrZero(RtContext ctx) {
        if (texture != null) return texture.view();
        if (bake == null) bake = CompletableFuture.supplyAsync(NightSkyTexture::bakeLevels);
        if (!bake.isDone()) return 0L;
        try {
            texture = new RtMaterialPageTexture(ctx, WIDTH, HEIGHT, bake.join(), "night sky milky way");
            bake = null;
            return texture.view();
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Night sky texture unavailable", t);
            bake = CompletableFuture.completedFuture(null);
            texture = null;
            return 0L;
        }
    }

    public static void destroy() {
        if (texture != null) texture.destroy();
        texture = null;
    }

    // ---------------------------------------------------------------- baking

    private static List<byte[]> bakeLevels() {
        long start = System.nanoTime();
        float[] hdr = new float[WIDTH * GALAXY_HEIGHT * 3];
        IntStream.range(0, GALAXY_HEIGHT).parallel().forEach(y -> {
            double theta = (y + 0.5) / GALAXY_HEIGHT * Math.PI;
            double st = Math.sin(theta), ct = Math.cos(theta);
            float[] rgb = new float[3];
            for (int x = 0; x < WIDTH; x++) {
                double phi = ((x + 0.5) / WIDTH - 0.5) * 2.0 * Math.PI;
                galaxy(st * Math.cos(phi), ct, st * Math.sin(phi), rgb);
                int i = (y * WIDTH + x) * 3;
                hdr[i] = rgb[0]; hdr[i + 1] = rgb[1]; hdr[i + 2] = rgb[2];
            }
        });
        List<byte[]> levels = new ArrayList<>();
        int w = WIDTH, h = GALAXY_HEIGHT;
        float[] level = hdr;
        byte[] moon = loadMoon();
        int mw = WIDTH / 2, mh = HEIGHT - GALAXY_HEIGHT;
        for (int k = 0; ; k++) {
            int tw = Math.max(1, WIDTH >> k), th = Math.max(1, HEIGHT >> k);
            levels.add(compose(encode(level, w, h), w, h, moon, mw, mh, tw, th));
            if (tw == 1 && th == 1) break;
            if (moon != null && mw > 1 && mh > 1) { moon = halve(moon, mw, mh); mw /= 2; mh /= 2; }
            else moon = null;
            int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
            float[] next = new float[nw * nh * 3];
            final int fw = w, fh = h; final float[] src = level;
            IntStream.range(0, nh).parallel().forEach(y -> {
                for (int x = 0; x < nw; x++) for (int c = 0; c < 3; c++) {
                    int x0 = Math.min(2 * x, fw - 1), x1 = Math.min(2 * x + 1, fw - 1);
                    int y0 = Math.min(2 * y, fh - 1), y1 = Math.min(2 * y + 1, fh - 1);
                    next[(y * nw + x) * 3 + c] = 0.25f * (src[(y0 * fw + x0) * 3 + c] + src[(y0 * fw + x1) * 3 + c]
                            + src[(y1 * fw + x0) * 3 + c] + src[(y1 * fw + x1) * 3 + c]);
                }
            });
            level = next; w = nw; h = nh;
        }
        CausticaMod.LOGGER.info("Night sky baked in {} ms", (System.nanoTime() - start) / 1_000_000);
        return levels;
    }

    // Level k of the full texture: the galaxy level on top, the moon level and the marker below.
    private static byte[] compose(byte[] galaxy, int w, int h, byte[] moon, int mw, int mh, int tw, int th) {
        byte[] out = new byte[tw * th * 4];
        int gh = Math.min(h, th);
        for (int y = 0; y < gh; y++) System.arraycopy(galaxy, y * w * 4, out, y * tw * 4, Math.min(w, tw) * 4);
        for (int y = gh; y < th; y++) {
            int my = y - gh;
            for (int x = 0; x < tw; x++) {
                int o = (y * tw + x) * 4;
                if (moon != null && x < mw && my < mh) System.arraycopy(moon, (my * mw + x) * 4, out, o, 4);
                else { out[o] = (byte) 255; out[o + 1] = 0; out[o + 2] = (byte) 255; out[o + 3] = 0; }
            }
        }
        return out;
    }

    private static byte[] loadMoon() {
        try (var in = NightSkyTexture.class.getResourceAsStream(MOON_MAP)) {
            if (in == null) return null;
            var img = javax.imageio.ImageIO.read(in);
            int w = WIDTH / 2, h = HEIGHT - GALAXY_HEIGHT;
            if (img == null || img.getWidth() != w || img.getHeight() != h) return null;
            byte[] out = new byte[w * h * 4];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y), o = (y * w + x) * 4;
                out[o] = (byte) (argb >> 16); out[o + 1] = (byte) (argb >> 8); out[o + 2] = (byte) argb; out[o + 3] = (byte) (argb >>> 24);
            }
            return out;
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Moon map unavailable", e);
            return null;
        }
    }

    private static byte[] halve(byte[] src, int w, int h) {
        int nw = w / 2, nh = h / 2;
        byte[] out = new byte[nw * nh * 4];
        for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) for (int c = 0; c < 4; c++) {
            int sum = (src[((2 * y) * w + 2 * x) * 4 + c] & 255) + (src[((2 * y) * w + 2 * x + 1) * 4 + c] & 255)
                    + (src[((2 * y + 1) * w + 2 * x) * 4 + c] & 255) + (src[((2 * y + 1) * w + 2 * x + 1) * 4 + c] & 255);
            out[(y * nw + x) * 4 + c] = (byte) ((sum + 2) / 4);
        }
        return out;
    }

    private static byte[] encode(float[] hdr, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            for (int c = 0; c < 3; c++) {
                double v = Math.pow(Math.max(0.0, Math.min(1.0, hdr[i * 3 + c] / PEAK)), 1.0 / 2.2);
                out[i * 4 + c] = (byte) Math.round(v * 255.0);
            }
            out[i * 4 + 3] = (byte) 255;
        }
        return out;
    }

    // Galactic frame: the pole lies on the horizon, so the band arches through the zenith.
    private static final double GX = 0.9403, GY = 0.0, GZ = 0.3402;
    private static final double CX = -0.2790, CY = 0.5700, CZ = 0.7723;

    private static void galaxy(double x, double y, double z, float[] out) {
        double b = x * GX + y * GY + z * GZ;
        double cosCore = Math.max(-1, Math.min(1, x * CX + y * CY + z * CZ));
        double coreAngle = Math.acos(cosCore);
        double core = Math.exp(-coreAngle * coreAngle * 2.6);
        double bulge = Math.exp(-(coreAngle * coreAngle) / 0.045) * Math.exp(-b * b / 0.02);
        double width = 0.30 + 0.18 * core;
        double band = Math.exp(-b * b / (width * width));
        double halo = Math.exp(-b * b / (0.62 * 0.62));

        // Domain warp: long-exposure swirls instead of a straight ribbon.
        double wx = fbm(x * 2.0 + 1.7, y * 2.0, z * 2.0, 4) - 0.5;
        double wy = fbm(x * 2.0, y * 2.0 + 9.2, z * 2.0, 4) - 0.5;
        double wz = fbm(x * 2.0, y * 2.0, z * 2.0 + 4.4, 4) - 0.5;
        double px = x * 3.0 + wx * 1.1, py = y * 3.0 + wy * 1.1, pz = z * 3.0 + wz * 1.1;

        // Star clouds: bright knots of unresolved stars at two scales, high contrast.
        double big = fbm(px * 1.6, py * 1.6, pz * 1.6, 6), small = fbm(px * 5.0 + 3, py * 5.0, pz * 5.0, 5);
        double knots = Math.pow(smooth(0.30, 0.85, big), 1.6) * (0.55 + 0.9 * smooth(0.35, 0.8, small));
        double glow = band * (0.18 + 2.0 * knots) * (0.6 + 1.6 * core) + bulge * (1.2 + 1.5 * small);

        // Dust. A Great Rift splitting the band lengthwise (widest over the bulge), dark nebula blotches
        // all along it, and fine filaments, so the band reads as glowing star clouds behind dust.
        // The rift meanders at two scales, swells and narrows along its length, and has ragged, feathered
        // edges where dust filaments reach into the star clouds.
        double laneOffset = 0.38 * (fbm(px * 0.7, py * 0.7, pz * 0.7, 3) - 0.5) + 0.025 * (fbm(px * 3.2 + 2, py * 3.2, pz * 3.2, 4) - 0.5);
        double laneWidth = (0.0075 + 0.010 * core) * (0.5 + 1.0 * fbm(px * 1.4 + 31, py * 1.4, pz * 1.4, 3));
        double ragged = 0.30 + 0.70 * smooth(0.35, 0.65, fbm(px * 5.0 + 47, py * 5.0, pz * 5.0, 5));
        double rift = Math.exp(-Math.pow((b + laneOffset) / laneWidth, 2)) * ragged
                * smooth(0.22, 0.48, fbm(px * 2.4 + 9, py * 1.2, pz * 1.2, 4));
        double blotch = smooth(0.52, 0.78, fbm(px * 2.6 + 17, py * 2.6, pz * 2.6, 5)) * band;
        double ridged = ridged(px * 3.5 + 5.0, py * 3.5, pz * 3.5, 5);
        double filament = smooth(0.55, 0.85, ridged) * band * 0.8;
        double dust = Math.min(1.0, rift * 0.9 + blotch * 0.55 + filament * 0.5);
        glow *= 1.0 - 0.50 * dust; // dark lanes, but the band keeps glowing through them


        // Colour. A hue field drifts along the band (iq cosine palette), so star clouds and nebulae
        // shade through magenta, cyan, gold, violet and sapphire; the bulge glows gold-orange.
        double along = Math.atan2(x * CZ - z * CX, (x * CX + y * CY + z * CZ)) / Math.PI; // -1..1 along the band; jumps by 2 where it wraps
        double hue = along * 0.5 + fbm(px * 0.7 + 41, py * 0.7, pz * 0.7, 4) * 0.9;
        // along * 0.5 jumps by exactly one palette period at the wrap, so the hue stays seamless there.
        double[] hc = palette(hue);
        double hr = hc[0], hg = hc[1], hb = hc[2];
        double warm = Math.min(1.0, core * 1.1 + bulge);
        double r = lerp(lerp(0.85, hr, 0.92), 1.00, warm * 0.8), g = lerp(lerp(0.85, hg, 0.92), 0.72, warm * 0.8), bl = lerp(lerp(0.95, hb, 0.92), 0.42, warm * 0.8);
        r *= glow; g *= glow; bl *= glow;

        // Glowing nebula clouds: broad, soft, saturated, layered at two scales and lit by the knots.
        double n1 = fbm(px * 0.85 + 13, py * 0.85, pz * 0.85, 6), n2 = fbm(px * 2.6 + 29, py * 2.6, pz * 2.6, 5);
        double cloud = smooth(0.36, 0.78, n1) * (0.55 + 0.45 * smooth(0.35, 0.8, n2));
        double nebulaMask = lerp(halo, band, 0.62) * cloud * (1.0 - 0.6 * dust) * (0.45 + 0.8 * knots + 0.7 * core);
        double[] nc = palette(hue + 0.35 * n2);
        r += nebulaMask * 2.3 * nc[0]; g += nebulaMask * 2.3 * nc[1]; bl += nebulaMask * 2.3 * nc[2];
        // Luminous coloured haze along the whole band: the dreamy glow the star clouds sit in.
        double sheen = band * band; r += sheen * 0.30 * hr; g += sheen * 0.30 * hg; bl += sheen * 0.30 * hb;

        // A Rho Ophiuchi-like complex beside the bulge: gold, crimson and blue nebulae braided together.
        double ophX = 0.10, ophY = 0.80, ophZ = 0.59; // ~0.4 rad from the core, off the plane
        double oph = Math.exp(-((x - ophX) * (x - ophX) + (y - ophY) * (y - ophY) + (z - ophZ) * (z - ophZ)) / 0.035);
        if (oph > 0.01) {
            double f1 = fbm(px * 4.0 + 61, py * 4.0, pz * 4.0, 6), f2 = fbm(px * 4.0, py * 4.0 + 73, pz * 4.0, 6);
            double gold = smooth(0.45, 0.8, f1) * oph, crimson = smooth(0.5, 0.82, f2) * oph, blue = smooth(0.55, 0.85, 1.0 - f1 * 0.5 - f2 * 0.5) * oph;
            r += gold * 1.30 + crimson * 1.25 + blue * 0.25;
            g += gold * 0.85 + crimson * 0.18 + blue * 0.55;
            bl += gold * 0.25 + crimson * 0.30 + blue * 1.45;
        }

        // Bright emission knots (rose-magenta) and blue reflection nebulae around bright star clouds.
        double hii = smooth(0.62, 0.88, fbm(px * 3.0 + 13, py * 3.0, pz * 3.0, 5)) * band * (1.0 - 0.5 * dust);
        double refl = smooth(0.66, 0.9, fbm(px * 3.0, py * 3.0 + 21, pz * 3.0, 5)) * band * knots * (1.0 - 0.6 * dust);
        r += hii * 1.10; g += hii * 0.22; bl += hii * 0.70;
        r += refl * 0.20; g += refl * 0.65; bl += refl * 1.40;

        // Warm glow along the rims of the dust lanes, where starlight scatters off the dust.
        double rim = dust * (1.0 - dust) * 4.0 * band;
        r += rim * 0.35; g += rim * 0.16; bl += rim * 0.04;

        // Faint coloured nebulosity over the whole sky, so the dark sky away from the band isn't empty.
        double s1 = fbm(px * 0.9 + 83, py * 0.9, pz * 0.9, 5), s2 = fbm(px * 2.2 + 97, py * 2.2, pz * 2.2, 4);
        double wide = smooth(0.52, 0.85, s1) * (0.4 + 0.6 * smooth(0.4, 0.8, s2)) * (0.3 + 0.7 * halo);
        double[] wc = palette(hue + 0.5 * s1 + 0.3);
        r += wide * 0.12 * wc[0]; g += wide * 0.12 * wc[1]; bl += wide * 0.12 * wc[2];

        // The Great Rift: a deep, continuous dark lane down the middle that swallows the nebulae and the
        // haze too, so the band splits in two.
        // Fine broken cracks branching off it: thin ridged dust lines, fragmented, densest near the rift.
        double crackLine = smooth(0.78, 0.92, ridged(px * 6.0 + 71, py * 6.0, pz * 6.0, 4));
        double crackGate = smooth(0.42, 0.62, fbm(px * 4.0 + 83, py * 4.0, pz * 4.0, 3));
        double near = Math.exp(-Math.pow((b + laneOffset) / (0.09 + 0.05 * core), 2));
        double cracks = crackLine * crackGate * band * (0.35 + 0.65 * near);
        // Side scars: three thinner, more broken lanes alongside the main rift, like healed scar tissue.
        double scars = 0.0;
        for (int i = 0; i < 3; i++) {
            double o = (i - 1) * 0.055 + (i == 1 ? 0.11 : 0.0);
            double mo = 0.30 * (fbm(px * 0.9 + 101 + 17 * i, py * 0.9, pz * 0.9, 3) - 0.5)
                    + 0.03 * (fbm(px * 4.0 + 7 * i, py * 4.0 + 3, pz * 4.0, 3) - 0.5);
            double sw = (0.004 + 0.005 * core) * (0.5 + 1.0 * fbm(px * 2.0 + 131 + 11 * i, py * 2.0, pz * 2.0, 3));
            double gate = smooth(0.40, 0.58, fbm(px * 3.0 + 151 + 23 * i, py * 3.0, pz * 3.0, 4));
            double rag = 0.35 + 0.65 * smooth(0.35, 0.65, fbm(px * 6.0 + 171 + 29 * i, py * 6.0, pz * 6.0, 4));
            scars = Math.max(scars, Math.exp(-Math.pow((b + o + mo) / sw, 2)) * gate * rag * band);
        }
        // Dust as backlit silhouettes, not ink: opacity varies along each lane (thick knots, thin veils),
        // the dust reddens what it lets through, and starlight scattering off the edges leaves a faint
        // warm rim around every scar.
        double opacity = 0.62 + 0.36 * smooth(0.35, 0.70, fbm(px * 3.3 + 191, py * 3.3, pz * 3.3, 4));
        double occ = Math.max(Math.max(smooth(0.08, 0.90, rift), 0.9 * smooth(0.12, 0.85, scars)), 0.85 * cracks) * opacity;
        double edge = occ * (1.0 - occ) * 4.0 * band;
        double glowLum = 0.2126 * r + 0.7152 * g + 0.0722 * bl;
        r += edge * glowLum * 0.45; g += edge * glowLum * 0.24; bl += edge * glowLum * 0.08;
        r *= 1.0 - occ * 0.93; g *= 1.0 - occ * 0.95; bl *= 1.0 - occ * 0.97;
        // Contrast: bright star clouds brighter, faint glow fainter, for a dark, deep sky.
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * bl;
        double k = Math.pow(Math.max(lum, 1e-4) / 0.8, 0.45);
        r *= k; g *= k; bl *= k;

        out[0] = (float) (r * 0.5); out[1] = (float) (g * 0.5); out[2] = (float) (bl * 0.5);
    }

    // A jewel-tone gradient that never passes through olive or yellow-green: magenta, violet, sapphire,
    // cyan, gold, rose, back to magenta.
    private static final double[][] JEWELS = {
            {1.00, 0.18, 0.75}, {0.50, 0.25, 1.00}, {0.18, 0.42, 1.00}, {0.10, 0.88, 0.95}, {1.00, 0.70, 0.25}, {1.00, 0.30, 0.48}};
    private static double[] palette(double t) {
        t = t - Math.floor(t);
        double f = t * JEWELS.length;
        int i = (int) Math.floor(f) % JEWELS.length, j = (i + 1) % JEWELS.length;
        double w = f - Math.floor(f);
        w = w * w * (3 - 2 * w);
        return new double[]{lerp(JEWELS[i][0], JEWELS[j][0], w), lerp(JEWELS[i][1], JEWELS[j][1], w), lerp(JEWELS[i][2], JEWELS[j][2], w)};
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777215.0;
    }

    private static double noise(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double a = lerp(hash(ix, iy, iz), hash(ix + 1, iy, iz), fx);
        double bb = lerp(hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz), fx);
        double c = lerp(hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1), fx);
        double d = lerp(hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1), fx);
        return lerp(lerp(a, bb, fy), lerp(c, d, fy), fz);
    }

    private static double fbm(double x, double y, double z, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x, y, z); norm += amp;
            x = x * 2.03 + 1.7; y = y * 2.03 + 3.1; z = z * 2.03 + 5.3; amp *= 0.5;
        }
        return sum / norm;
    }

    private static double ridged(double x, double y, double z, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int i = 0; i < octaves; i++) {
            double n = 1.0 - Math.abs(noise(x, y, z) * 2.0 - 1.0);
            sum += amp * n * n; norm += amp;
            x = x * 2.1 + 2.3; y = y * 2.1 + 7.9; z = z * 2.1 + 1.1; amp *= 0.55;
        }
        return sum / norm;
    }

    private NightSkyTexture() {}
}
