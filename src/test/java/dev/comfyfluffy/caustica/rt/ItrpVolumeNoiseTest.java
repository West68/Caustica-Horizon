package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItrpVolumeNoiseTest {
    private static int channel(byte[] t, int n, int x, int y, int z, int c) {
        return t[((z * n + y) * n + x) * 4 + c] & 255;
    }

    @Test void shapeNoiseSpansTheRangeAndTiles() {
        long start = System.nanoTime();
        int n = ItrpVolumeNoise.SHAPE_SIZE;
        byte[] t = ItrpVolumeNoise.shapeTexels(n);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("shape noise generated in " + ms + " ms");
        assertEquals(n * n * n * 4, t.length);
        for (int c = 0; c < 4; c++) {
            int min = 255, max = 0;
            long sum = 0;
            for (int i = c; i < t.length; i += 4) { int v = t[i] & 255; min = Math.min(min, v); max = Math.max(max, v); sum += v; }
            double mean = sum / (double) (n * n * n);
            assertTrue(max - min > 120, "channel " + c + " range " + min + ".." + max);
            assertTrue(mean > 40 && mean < 215, "channel " + c + " mean " + mean);
        }
        // Periodic: the texel next to the last one along each axis is texel 0, so neighbours across the
        // seam must differ no more than typical neighbours do.
        long seam = 0, inner = 0;
        for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) {
            seam += Math.abs(channel(t, n, n - 1, y, z, 1) - channel(t, n, 0, y, z, 1));
            inner += Math.abs(channel(t, n, n / 2, y, z, 1) - channel(t, n, n / 2 + 1, y, z, 1));
        }
        assertTrue(seam < inner * 1.6 + n * n, "seam " + seam + " vs inner " + inner);
    }

    @Test void detailNoiseHasOpaqueAlpha() {
        int n = ItrpVolumeNoise.DETAIL_SIZE;
        byte[] t = ItrpVolumeNoise.detailTexels(n);
        for (int i = 3; i < t.length; i += 4) assertEquals(-1, t[i]);
    }
}
