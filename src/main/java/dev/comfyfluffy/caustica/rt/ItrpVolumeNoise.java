package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.pipeline.RtVolumeTexture;

/**
 * Tileable 3D noise for the volumetric cloud deck (cloud mode 3). Generated procedurally on the CPU once,
 * uploaded as two RGBA8 3D textures with repeat addressing and trilinear filtering:
 * <ul>
 *   <li>shape (64^3): r = Perlin-Worley, g/b/a = Worley fBm at 4, 8 and 16 cells per tile.</li>
 *   <li>detail (32^3): r/g/b = Worley fBm at 4, 8 and 16 cells per tile; a = 1.</li>
 * </ul>
 * This is the well-known cloud noise layout from the "Nubis" papers; the generator here is written from
 * the published technique and contains no shader-pack code or data.
 */
public final class ItrpVolumeNoise {
    static final int SHAPE_SIZE = 64;
    static final int DETAIL_SIZE = 32;

    private RtVolumeTexture shape;
    private RtVolumeTexture detail;
    private boolean attempted;

    /** Creates both textures on first use; null views mean generation failed and mode 3 renders no clouds. */
    public void ensure(RtContext ctx) {
        if (attempted) return;
        attempted = true;
        try {
            long start = System.nanoTime();
            byte[] s = shapeTexels(SHAPE_SIZE);
            byte[] d = detailTexels(DETAIL_SIZE);
            shape = RtVolumeTexture.uploadRgba8(ctx, SHAPE_SIZE, s, "volumetric cloud shape noise");
            detail = RtVolumeTexture.uploadRgba8(ctx, DETAIL_SIZE, d, "volumetric cloud detail noise");
            CausticaMod.LOGGER.info("Volumetric cloud noise ready in {} ms", (System.nanoTime() - start) / 1_000_000L);
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Volumetric cloud noise unavailable", t);
            destroy();
        }
    }

    public RtVolumeTexture shape() { return shape; }
    public RtVolumeTexture detail() { return detail; }

    public void destroy() {
        if (shape != null) shape.destroy();
        if (detail != null) detail.destroy();
        shape = null;
        detail = null;
    }

    // ---- generation ---------------------------------------------------------------------------------

    static byte[] shapeTexels(int n) {
        byte[] out = new byte[n * n * n * 4];
        for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float u = (x + .5f) / n, v = (y + .5f) / n, w = (z + .5f) / n;
            // Statistics measured against ITRP's shipped 128^3 cloud noise (mean / std / mean spectral
            // frequency per tile): R .672/.133/8.6, Worley .565/.13 at 5.3, 9.8, 18.3 cycles. The old
            // texture (R .738/.070/4.1) made cumulus twice as large and so flat that the coverage threshold
            // merged them into one sheet. Contrast-stretched period-9 Perlin, Worley at 5/10/20 cells lifted
            // by .09, and a partial Worley floor reproduce those numbers within a few percent.
            float perlin = Math.clamp((perlinFbm(u, v, w, 9, 4) - .5f) * 2.4f + .5f, 0f, 1f);
            float worley4 = Math.clamp(worleyFbm(u, v, w, 5, 101) + .09f, 0f, 1f);
            float worley8 = Math.clamp(worleyFbm(u, v, w, 10, 202) + .09f, 0f, 1f);
            float worley16 = Math.clamp(worleyFbm(u, v, w, 20, 303) + .09f, 0f, 1f);
            // Perlin-Worley: billowy Perlin whose low end is carved by Worley cells.
            float floor = worley4 * .6f;
            float pw = floor + perlin * (1f - floor);
            int i = ((z * n + y) * n + x) * 4;
            out[i] = unorm(pw);
            out[i + 1] = unorm(worley4);
            out[i + 2] = unorm(worley8);
            out[i + 3] = unorm(worley16);
        }
        return out;
    }

    static byte[] detailTexels(int n) {
        byte[] out = new byte[n * n * n * 4];
        for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float u = (x + .5f) / n, v = (y + .5f) / n, w = (z + .5f) / n;
            int i = ((z * n + y) * n + x) * 4;
            out[i] = unorm(worleyFbm(u, v, w, 4, 404) + .08f);
            out[i + 1] = unorm(worleyFbm(u, v, w, 8, 505) + .08f);
            out[i + 2] = unorm(worleyFbm(u, v, w, 16, 606) + .08f);
            out[i + 3] = (byte) 255;
        }
        return out;
    }

    private static byte unorm(float v) {
        return (byte) Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }

    static float remap(float v, float lo, float hi, float newLo, float newHi) {
        return newLo + (v - lo) / (hi - lo) * (newHi - newLo);
    }

    private static int hash(int x, int y, int z, int seed) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    private static float hashFloat(int x, int y, int z, int seed) {
        return (hash(x, y, z, seed) & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Inverted cellular noise (1 at feature points), periodic over the unit cube with {@code cells}^3 cells. */
    static float worley(float u, float v, float w, int cells, int seed) {
        float px = u * cells, py = v * cells, pz = w * cells;
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
        float best = Float.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int gx = cx + dx, gy = cy + dy, gz = cz + dz;
            int wx = Math.floorMod(gx, cells), wy = Math.floorMod(gy, cells), wz = Math.floorMod(gz, cells);
            float fx = gx + hashFloat(wx, wy, wz, seed);
            float fy = gy + hashFloat(wx, wy, wz, seed + 17);
            float fz = gz + hashFloat(wx, wy, wz, seed + 31);
            float ex = fx - px, ey = fy - py, ez = fz - pz;
            best = Math.min(best, ex * ex + ey * ey + ez * ez);
        }
        return 1f - Math.min((float) Math.sqrt(best), 1f);
    }

    static float worleyFbm(float u, float v, float w, int cells, int seed) {
        return worley(u, v, w, cells, seed) * .625f
                + worley(u, v, w, cells * 2, seed + 1) * .25f
                + worley(u, v, w, cells * 4, seed + 2) * .125f;
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float grad(int h, float x, float y, float z) {
        switch (h & 15) {
            case 0: return x + y; case 1: return -x + y; case 2: return x - y; case 3: return -x - y;
            case 4: return x + z; case 5: return -x + z; case 6: return x - z; case 7: return -x - z;
            case 8: return y + z; case 9: return -y + z; case 10: return y - z; case 11: return -y - z;
            case 12: return x + y; case 13: return -x + y; case 14: return -y + z; default: return -y - z;
        }
    }

    /** Periodic gradient noise in roughly [-1,1]. */
    static float perlin(float u, float v, float w, int period, int seed) {
        float px = u * period, py = v * period, pz = w * period;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py), z0 = (int) Math.floor(pz);
        float fx = px - x0, fy = py - y0, fz = pz - z0;
        float ux = fade(fx), uy = fade(fy), uz = fade(fz);
        float acc = 0f;
        for (int k = 0; k < 8; k++) {
            int ox = k & 1, oy = (k >> 1) & 1, oz = (k >> 2) & 1;
            int h = hash(Math.floorMod(x0 + ox, period), Math.floorMod(y0 + oy, period), Math.floorMod(z0 + oz, period), seed);
            float g = grad(h, fx - ox, fy - oy, fz - oz);
            float wgt = (ox == 1 ? ux : 1f - ux) * (oy == 1 ? uy : 1f - uy) * (oz == 1 ? uz : 1f - uz);
            acc += g * wgt;
        }
        return acc;
    }

    static float perlinFbm(float u, float v, float w, int period, int octaves) {
        float sum = 0f, amp = 1f, norm = 0f;
        int p = period;
        for (int o = 0; o < octaves; o++) {
            sum += perlin(u, v, w, p, 7 + o) * amp;
            norm += amp;
            amp *= .5f;
            p *= 2;
        }
        return Math.clamp(sum / norm * .5f + .5f, 0f, 1f);
    }
}
