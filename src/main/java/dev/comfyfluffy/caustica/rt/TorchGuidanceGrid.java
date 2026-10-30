package dev.comfyfluffy.caustica.rt;

import java.util.Arrays;

/** Pure CPU proposal grid. Hints carry no radiance; occlusion/emission remain in the real path trace. */
public final class TorchGuidanceGrid {
    public static final int SIZE = 16;
    public static final int CELL_SIZE = 4;
    public static final int REACH = 16;
    public static final float RADIUS = .65f;
    public static final int FLOATS = SIZE * SIZE * SIZE * 4;
    private TorchGuidanceGrid() {}

    /** Inputs and output centers are relative to the grid origin, never large world floats. */
    public static float[] build(float[] centers) {
        if (centers.length % 3 != 0) throw new IllegalArgumentException("xyz triples required");
        float[] grid = new float[FLOATS];
        float[] best = new float[FLOATS / 4];
        Arrays.fill(best, REACH * REACH);
        for (int i = 0; i < centers.length; i += 3) {
            float x = centers[i], y = centers[i + 1], z = centers[i + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
            int x0 = lower(x), x1 = upper(x), y0 = lower(y), y1 = upper(y), z0 = lower(z), z1 = upper(z);
            for (int iz = z0; iz <= z1; iz++) for (int iy = y0; iy <= y1; iy++) for (int ix = x0; ix <= x1; ix++) {
                float dx = (ix + .5f) * CELL_SIZE - x, dy = (iy + .5f) * CELL_SIZE - y,
                        dz = (iz + .5f) * CELL_SIZE - z;
                float d2 = dx * dx + dy * dy + dz * dz;
                int cell = (iz * SIZE + iy) * SIZE + ix;
                if (d2 >= best[cell]) continue;
                best[cell] = d2;
                int offset = cell * 4;
                grid[offset] = x; grid[offset + 1] = y; grid[offset + 2] = z; grid[offset + 3] = RADIUS;
            }
        }
        return grid;
    }
    private static int lower(float p) { return Math.max(0, (int)Math.ceil((p - REACH) / CELL_SIZE - .5)); }
    private static int upper(float p) { return Math.min(SIZE - 1, (int)Math.floor((p + REACH) / CELL_SIZE - .5)); }
}
