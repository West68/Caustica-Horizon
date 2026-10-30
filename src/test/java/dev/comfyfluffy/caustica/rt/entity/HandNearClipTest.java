package dev.comfyfluffy.caustica.rt.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class HandNearClipTest {
    /** One quad (two triangles) along the view axis from behind the eye (z=+1) to in front of it (z=-1). */
    private static RtEntityCapture sleeve() {
        RtEntityCapture c = new RtEntityCapture();
        float[][] p = {{0, 0, 1}, {0, 0, -1}, {0, .1f, -1}, {0, .1f, 1}};
        for (float[] v : p) { c.verts.add(v[0]); c.verts.add(v[1]); c.verts.add(v[2]); c.uvList.add(v[2]); c.uvList.add(v[1]); }
        int[] tris = {0, 1, 2, 0, 2, 3};
        for (int i : tris) c.idx.add(i);
        for (int t = 0; t < 2; t++) { for (int k = 0; k < 12; k++) c.prim.add(t * 100 + k); c.alphaBuckets.add(t); }
        return c;
    }

    @Test void cutsTheHandAtTheEyePlaneInsteadOfMovingIt() {
        RtEntityCapture c = sleeve();
        HandProjection.clipNear(c, new Matrix4f());
        int vertices = c.verts.size() / 3;
        assertEquals(c.uvList.size(), vertices * 2);
        assertEquals(c.prim.size(), c.idx.size() / 3 * 12);
        assertEquals(c.alphaBuckets.size(), c.idx.size() / 3);
        for (int i = 0; i < c.idx.size(); i++) assertTrue(c.idx.getInt(i) < vertices);
        float near = Float.POSITIVE_INFINITY, far = 0;
        for (int i = 0; i < vertices; i++) {
            float depth = -c.verts.getFloat(i * 3 + 2);
            near = Math.min(near, depth); far = Math.max(far, depth);
            // UV follows the interpolated position (u was set to z).
            assertEquals(c.verts.getFloat(i * 3 + 2), c.uvList.getFloat(i * 2), 1e-6f);
        }
        assertEquals(HandProjection.NEAR, near, 1e-6f);
        assertEquals(1f, far, 1e-6f);
        // Every visible piece keeps its own triangle's per-prim data.
        for (int t = 0; t < c.idx.size() / 3; t++) assertEquals(c.alphaBuckets.getInt(t) * 100, c.prim.getFloat(t * 12), 0f);
    }

    @Test void leavesAHandFullyInFrontUntouched() {
        RtEntityCapture c = sleeve();
        for (int i = 2; i < c.verts.size(); i += 3) c.verts.set(i, -Math.abs(c.verts.getFloat(i)));
        HandProjection.clipNear(c, new Matrix4f());
        assertEquals(4, c.verts.size() / 3);
        assertEquals(6, c.idx.size());
    }
}
