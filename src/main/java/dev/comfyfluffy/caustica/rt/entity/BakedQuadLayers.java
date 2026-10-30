package dev.comfyfluffy.caustica.rt.entity;

/** Terrain's bounded coplanar-layer rule for one dynamic block/item submission. CPU scratch only. */
final class BakedQuadLayers {
    static final int LIMIT = 128;
    static final float OFFSET = 2e-4f;
    private static final float EPS = 1e-4f;
    private final int[] vertices = new int[LIMIT];
    private final int[] primitives = new int[LIMIT];
    private final boolean[] overlay = new boolean[LIMIT];
    private final int[] group = new int[LIMIT];
    private final double[] cx = new double[LIMIT], cy = new double[LIMIT], cz = new double[LIMIT];
    private int depth, count;

    void begin() {
        if (depth++ == 0) count = 0;
    }

    void record(RtEntityCapture capture, boolean isOverlay) {
        if (depth == 0) return;
        int i = count++;
        if (i >= LIMIT) return; // Match terrain: leave pathological models unchanged.
        vertices[i] = capture.verts.size() - 12;
        primitives[i] = capture.prim.size() - 24;
        overlay[i] = isOverlay;
        int base = vertices[i];
        cx[i] = cy[i] = cz[i] = 0;
        for (int v = 0; v < 4; v++) {
            cx[i] += capture.verts.getFloat(base + v * 3);
            cy[i] += capture.verts.getFloat(base + v * 3 + 1);
            cz[i] += capture.verts.getFloat(base + v * 3 + 2);
        }
    }

    void end(RtEntityCapture capture) {
        if (--depth != 0 || count < 2 || count > LIMIT) return;
        java.util.Arrays.fill(group, 0, count, -1);
        // Determine all groups before moving vertices, independent of quad winding and emission order.
        for (int i = 0; i < count; i++) {
            if (group[i] != -1) continue;
            group[i] = i;
            for (int j = i + 1; j < count; j++) {
                if (group[j] == -1 && Math.abs(cx[i]-cx[j]) < 4*EPS
                        && Math.abs(cy[i]-cy[j]) < 4*EPS && Math.abs(cz[i]-cz[j]) < 4*EPS
                        && coincident(capture, vertices[i], vertices[j])) group[j] = i;
            }
        }
        for (int r = 0; r < count; r++) {
            if (group[r] != r) continue;
            int rank = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < count; i++) {
                    if (group[i] != r || overlay[i] != (pass == 1)) continue;
                    if (rank > 0) offset(capture, i, rank * OFFSET);
                    rank++;
                }
            }
        }
    }

    private static boolean coincident(RtEntityCapture capture, int a, int b) {
        for (int k = 0; k < 4; k++) {
            boolean found = false;
            for (int m = 0; m < 4; m++) {
                int x = a + 3 * k, y = b + 3 * m;
                if (Math.abs(capture.verts.getFloat(x) - capture.verts.getFloat(y)) < EPS
                        && Math.abs(capture.verts.getFloat(x+1) - capture.verts.getFloat(y+1)) < EPS
                        && Math.abs(capture.verts.getFloat(x+2) - capture.verts.getFloat(y+2)) < EPS) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private void offset(RtEntityCapture capture, int i, float distance) {
        int p = primitives[i];
        // appendQuad already normalized this normal; preserve material, UV, topology and tint.
        for (int v = 0; v < 4; v++) {
            for (int axis = 0; axis < 3; axis++) {
                int at = vertices[i] + 3 * v + axis;
                capture.verts.set(at, capture.verts.getFloat(at) + capture.prim.getFloat(p + axis) * distance);
            }
        }
    }
}
