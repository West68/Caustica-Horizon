package dev.comfyfluffy.caustica.rt.entity;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Maps the final view-model mesh into camera-relative RT space without changing its projected size. */
public final class HandProjection {
    private HandProjection() {}
    /** Depth of the eye plane the hand is clipped against (raster hands are cut by the near plane too). */
    static final float NEAR = .08f;

    /**
     * Cut every triangle of the posed hand at {@link #NEAR} depth, as the raster near plane does. YSM arms
     * can reach behind the eye; moving the whole arm forward instead put its open cross-section right in
     * front of the camera (the hollow orange box). Clipped polygons keep their winding, UVs and per-prim data.
     */
    static void clipNear(RtEntityCapture c, Matrix4fc viewRotation) {
        int vertexCount = c.verts.size() / 3;
        float[] depth = new float[vertexCount];
        Vector3f v = new Vector3f();
        boolean any = false;
        for (int i = 0; i < vertexCount; i++) {
            v.set(c.verts.getFloat(i * 3), c.verts.getFloat(i * 3 + 1), c.verts.getFloat(i * 3 + 2));
            viewRotation.transformDirection(v);
            depth[i] = -v.z;
            any |= depth[i] < NEAR;
        }
        if (!any) return;
        IntArrayList idx = new IntArrayList(c.idx.size());
        FloatArrayList prim = new FloatArrayList(c.prim.size());
        IntArrayList alpha = new IntArrayList(c.alphaBuckets.size());
        int[] poly = new int[4];
        for (int t = 0; t < c.idx.size() / 3; t++) {
            int n = 0;
            for (int k = 0; k < 3; k++) {
                int a = c.idx.getInt(t * 3 + k), b = c.idx.getInt(t * 3 + (k + 1) % 3);
                boolean ina = depth[a] >= NEAR, inb = depth[b] >= NEAR;
                if (ina) poly[n++] = a;
                if (ina != inb) poly[n++] = split(c, a, b, (NEAR - depth[a]) / (depth[b] - depth[a]));
            }
            for (int k = 1; k + 1 < n; k++) {
                idx.add(poly[0]); idx.add(poly[k]); idx.add(poly[k + 1]);
                prim.addElements(prim.size(), c.prim.elements(), t * 12, 12);
                alpha.add(c.alphaBuckets.getInt(t));
            }
        }
        // Drop vertices no triangle uses any more: they sit behind the eye and would blow up in the remap.
        int[] remap = new int[c.verts.size() / 3];
        java.util.Arrays.fill(remap, -1);
        FloatArrayList verts = new FloatArrayList(c.verts.size()), uvs = new FloatArrayList(c.uvList.size());
        for (int i = 0; i < idx.size(); i++) {
            int o = idx.getInt(i);
            if (remap[o] < 0) {
                remap[o] = verts.size() / 3;
                verts.addElements(verts.size(), c.verts.elements(), o * 3, 3);
                uvs.addElements(uvs.size(), c.uvList.elements(), o * 2, 2);
            }
            idx.set(i, remap[o]);
        }
        c.verts.clear(); c.verts.addAll(verts);
        c.uvList.clear(); c.uvList.addAll(uvs);
        c.idx.clear(); c.idx.addAll(idx);
        c.prim.clear(); c.prim.addAll(prim);
        c.alphaBuckets.clear(); c.alphaBuckets.addAll(alpha);
    }

    private static int split(RtEntityCapture c, int a, int b, float f) {
        int out = c.verts.size() / 3;
        for (int k = 0; k < 3; k++) c.verts.add(c.verts.getFloat(a * 3 + k) + (c.verts.getFloat(b * 3 + k) - c.verts.getFloat(a * 3 + k)) * f);
        for (int k = 0; k < 2; k++) c.uvList.add(c.uvList.getFloat(a * 2 + k) + (c.uvList.getFloat(b * 2 + k) - c.uvList.getFloat(a * 2 + k)) * f);
        return out;
    }

    /** Move a posed hand forward before the projective remap if any vertex crossed the eye plane. */
    public static float nearPlanePush(float minimumDepth) {
        if (!Float.isFinite(minimumDepth)) return 0f;
        return Math.max(0f, .08f - minimumDepth);
    }
    public static float safeDepthScale(float requested,float nearestDepth) {
        if(!Float.isFinite(nearestDepth)||nearestDepth<=0f)return 1f;
        // Uniform camera-centred scaling preserves x/z and y/z. Permit expansion when
        // an animated positive-depth vertex has already crossed the safety plane.
        // Clamping to 1 previously left precisely these close-up poses unprotected.
        return Math.max(Math.clamp(requested,.2f,1f),.08f/nearestDepth);
    }

    /** Full clip-space equivalence, including off-axis/hurt/bob transforms in the world projection. */
    public static Matrix4f toWorld(Matrix4fc worldProjection, Matrix4fc handProjection, Matrix4fc view,
                                    float x, float y, float z) {
        Matrix4f result = new Matrix4f().translation(x,y,z).mul(new Matrix4f(view).invert())
            .mul(new Matrix4f(worldProjection).invert()).mul(handProjection).mul(view);
        if (!result.isFinite()) throw new IllegalArgumentException("Invalid view-model projection");
        return result;
    }

    public static Matrix4f toWorld(Matrix4fc worldProjection, Matrix4fc view, float hudFov, float aspect,
                                    float x, float y, float z) {
        float tan = (float) Math.tan(Math.toRadians(hudFov) * 0.5);
        float sx = 1f / (tan * aspect * Math.abs(worldProjection.m00()));
        float sy = 1f / (tan * Math.abs(worldProjection.m11()));
        if (!Float.isFinite(sx) || !Float.isFinite(sy) || sx <= 0 || sy <= 0)
            throw new IllegalArgumentException("Invalid first-person projection");
        return new Matrix4f().translation(x, y, z).mul(new Matrix4f(view).invert())
                .scale(sx, sy, 1f).mul(view);
    }
}
