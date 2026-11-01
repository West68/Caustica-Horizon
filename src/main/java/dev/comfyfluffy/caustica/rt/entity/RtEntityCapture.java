package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A {@link VertexConsumer} that records the posed entity geometry vanilla emits — exactly the same bulk
 * {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} that {@code ModelPart.Cube.compile} calls
 * (4 verts/quad → 2 triangles). {@link RtEntityCollector} drives it by calling
 * {@code model.renderToBuffer(pose, this, …)}.
 *
 * <p>Accumulators use the same layout as terrain's {@code SectionMesh} (positions, indices, atlas UV,
 * per-prim {@code {normal.xyz, reserved}, {tint.rgb, albedoSlot}, {materialId, flags, aux0, aux1}}) so entities
 * share the terrain upload + BLAS path verbatim.
 */
public final class RtEntityCapture implements VertexConsumer {
    private static final int DEFAULT_VERTEX_CAPACITY = 1024;
    // Same magnitude as RtTerrain.QuadCapture.OFFSET (2e-4 blocks) — proven large enough to break a BVH
    // depth tie without a visible gap at terrain/entity scale.
    private static final float ORDER_OFFSET = 2.0e-4f;

    final FloatArrayList verts = new FloatArrayList(DEFAULT_VERTEX_CAPACITY * 3);   // 3 floats/vertex (capture-space position)
    final IntArrayList idx = new IntArrayList(indexCapacity(DEFAULT_VERTEX_CAPACITY)); // 3 indices/triangle
    final FloatArrayList uvList = new FloatArrayList(DEFAULT_VERTEX_CAPACITY * 2);  // 2 floats/vertex (entity-texture UV)
    final FloatArrayList prim = new FloatArrayList(primCapacity(DEFAULT_VERTEX_CAPACITY)); // 12 floats/triangle
    // One classification per triangle in capture order. The upload path repacks indices + primitive
    // records into fixed {opaque, any-hit} BLAS geometries while positions/UVs stay shared. Keeping
    // capture order here preserves glow meshes, parity checks and motion topology.
    final IntArrayList alphaBuckets = new IntArrayList(indexCapacity(DEFAULT_VERTEX_CAPACITY) / 3);
    private final IntArrayList packedIdx = new IntArrayList(indexCapacity(DEFAULT_VERTEX_CAPACITY));
    private final FloatArrayList packedPrim = new FloatArrayList(primCapacity(DEFAULT_VERTEX_CAPACITY));
    private final int[] packedBucketTris = new int[RtAccel.ENTITY_BUCKETS];

    // Bindless texture slot for the geometry currently being submitted (set by the collector per
    // submitModel, so body + feature layers get their own texture). Stored per-prim in tint.w;
    // the hit shader samples entityAlbedoTex[texSlot].
    int currentTexSlot;
    // Canonical MaterialHeader ID for this submission. Entity, block-entity and block-atlas geometry all
    // use the same table; albedo remains a separate bindless slot in tint.w.
    int currentMaterialId;
    // Conservative default: unknown submissions retain alpha testing instead of incorrectly becoming
    // opaque. RtEntityCollector assigns this from the RenderPipeline before every known submission.
    int currentAlphaBucket = RtAccel.ENTITY_BUCKET_ANY_HIT;
    // Decal-stacking rank for the current submission (0 = no offset). Set by the collector from
    // SubmitNodeCollector#order(int) — see emitQuad's coincident-layer push.
    int currentOrder;
    // When a model textures from an atlas sprite (block entities: chests/signs/beds via a Material),
    // its ModelPart UVs are 0..1 in a virtual texture and must be remapped into the sprite's atlas
    // region — the work vanilla's sprite-coordinate-expander VertexConsumer does, which we bypass.
    // Off for full-texture models (mobs, sprite == null) and for baked quads (already atlas-space).
    private boolean uvRemap;
    org.joml.Matrix4fc textureTransform;
    private float uvU0, uvV0, uvDU, uvDV;

    private int n; // quad vertex accumulator (0..3)
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];
    private final float[] qu = new float[4], qv = new float[4];
    private final float[] qnx = new float[4], qny = new float[4], qnz = new float[4];
    private final int[] qcol = new int[4];
    private final Vector3f scratch = new Vector3f(); // baked-quad position transform scratch

    /** Clear all accumulators for a fresh entity capture. */
    public void reset() {
        reset(0);
    }

    /** Clear and pre-size for a known previous topology to avoid add() growth during capture. */
    public void reset(int expectedVertices) {
        verts.clear();
        idx.clear();
        uvList.clear();
        prim.clear();
        alphaBuckets.clear();
        packedIdx.clear();
        packedPrim.clear();
        ensureVertexCapacity(expectedVertices);
        n = 0;
        currentTexSlot = 0;
        currentMaterialId = 0;
        currentAlphaBucket = RtAccel.ENTITY_BUCKET_ANY_HIT;
        currentOrder = 0;
        textureTransform = null;
        uvRemap = false;
    }

    private void ensureVertexCapacity(int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        verts.ensureCapacity(vertexCount * 3);
        idx.ensureCapacity(indexCapacity(vertexCount));
        uvList.ensureCapacity(vertexCount * 2);
        prim.ensureCapacity(primCapacity(vertexCount));
        alphaBuckets.ensureCapacity(indexCapacity(vertexCount) / 3);
        packedIdx.ensureCapacity(indexCapacity(vertexCount));
        packedPrim.ensureCapacity(primCapacity(vertexCount));
    }

    /** Reserve room for an upcoming direct-model submission without changing any logical sizes. */
    void ensureAdditionalVertexCapacity(int additionalVertices) {
        if (additionalVertices > 0) {
            ensureVertexCapacity(verts.size() / 3 + additionalVertices);
        }
    }

    private static int indexCapacity(int vertexCount) {
        int quadCount = (vertexCount + 3) / 4;
        return quadCount * 6;
    }

    private static int primCapacity(int vertexCount) {
        int quadCount = (vertexCount + 3) / 4;
        return quadCount * 24;
    }

    /** Remap subsequent {@link #addVertex} (ModelPart) UVs from 0..1 into a sprite's atlas region. */
    public void setUvRemap(float u0, float v0, float u1, float v1) {
        textureTransform = null;
        uvRemap = true;
        uvU0 = u0;
        uvV0 = v0;
        uvDU = u1 - u0;
        uvDV = v1 - v0;
    }

    /** Use ModelPart UVs as-is (full-texture models). */
    public void clearUvRemap() {
        textureTransform = null;
        uvRemap = false;
    }

    /** Copy the per-submission material/UV state into a second capture used by the parity harness. */
    void copySubmissionStateTo(RtEntityCapture target) {
        target.textureTransform = textureTransform;
        target.currentTexSlot = currentTexSlot;
        target.currentMaterialId = currentMaterialId;
        target.currentAlphaBucket = currentAlphaBucket;
        target.currentOrder = currentOrder;
        target.uvRemap = uvRemap;
        target.uvU0 = uvU0;
        target.uvV0 = uvV0;
        target.uvDU = uvDU;
        target.uvDV = uvDV;
    }

    /**
     * Require one submission appended to this capture to exactly match a standalone reference capture.
     * Float comparison uses raw bits because the entity cache hashes raw bits; numeric deltas are included
     * only to localize failures and never relax the pass criterion.
     */
    void assertSubmissionBitwiseIdentical(int vertStart, int idxStart, int uvStart, int primStart,
                                          RtEntityCapture reference, String label) {
        if (n != 0 || reference.n != 0) {
            throw new IllegalStateException(label + " left an incomplete quad: actual=" + n
                    + ", reference=" + reference.n);
        }
        assertFloatRange("positions", verts.elements(), vertStart, verts.size() - vertStart,
                reference.verts.elements(), reference.verts.size(), label);
        assertIndexRange(idx.elements(), idxStart, idx.size() - idxStart,
                reference.idx.elements(), reference.idx.size(), vertStart / 3, label);
        assertFloatRange("uvs", uvList.elements(), uvStart, uvList.size() - uvStart,
                reference.uvList.elements(), reference.uvList.size(), label);
        assertFloatRange("primitives", prim.elements(), primStart, prim.size() - primStart,
                reference.prim.elements(), reference.prim.size(), label);
        int triangleStart = idxStart / 3;
        int triangleCount = (idx.size() - idxStart) / 3;
        if (triangleCount != reference.alphaBuckets.size()) {
            throw new IllegalStateException(label + " alpha bucket size mismatch: actual=" + triangleCount
                    + ", reference=" + reference.alphaBuckets.size());
        }
        for (int i = 0; i < triangleCount; i++) {
            int actual = alphaBuckets.getInt(triangleStart + i);
            int expected = reference.alphaBuckets.getInt(i);
            if (actual != expected) {
                throw new IllegalStateException(label + " alpha bucket[" + i + "] mismatch: actual="
                        + actual + ", reference=" + expected);
            }
        }
    }

    private static void assertFloatRange(String component, float[] actual, int actualStart, int actualSize,
                                         float[] reference, int referenceSize, String label) {
        if (actualSize != referenceSize) {
            throw new IllegalStateException(label + " " + component + " size mismatch: actual="
                    + actualSize + ", reference=" + referenceSize);
        }
        for (int i = 0; i < actualSize; i++) {
            float a = actual[actualStart + i];
            float b = reference[i];
            int ab = Float.floatToRawIntBits(a);
            int bb = Float.floatToRawIntBits(b);
            if (ab != bb) {
                throw new IllegalStateException(label + " " + component + '[' + i + "] mismatch: actual="
                        + a + " (0x" + Integer.toHexString(ab) + "), reference=" + b + " (0x"
                        + Integer.toHexString(bb) + "), delta=" + (a - b));
            }
        }
    }

    private static void assertIndexRange(int[] actual, int actualStart, int actualSize,
                                         int[] reference, int referenceSize, int referenceBaseVertex,
                                         String label) {
        if (actualSize != referenceSize) {
            throw new IllegalStateException(label + " indices size mismatch: actual="
                    + actualSize + ", reference=" + referenceSize);
        }
        for (int i = 0; i < actualSize; i++) {
            int expected = reference[i] + referenceBaseVertex;
            if (actual[actualStart + i] != expected) {
                throw new IllegalStateException(label + " indices[" + i + "] mismatch: actual="
                        + actual[actualStart + i] + ", reference=" + expected
                        + " (local " + reference[i] + ")");
            }
        }
    }

    public boolean isEmpty() {
        return idx.isEmpty();
    }

    /**
     * Repack triangles into the fixed entity BLAS geometry order. PrimitiveIndex restarts at zero for
     * each Vulkan geometry, so the returned counts also define the triangle bases written to EntityGeom.
     */
    PackedGeometry packGeometry() {
        int triangleCount = idx.size() / 3;
        if (alphaBuckets.size() != triangleCount || prim.size() != triangleCount * 12) {
            throw new IllegalStateException("Malformed entity capture: triangles=" + triangleCount
                    + ", alphaBuckets=" + alphaBuckets.size() + ", primFloats=" + prim.size());
        }
        packedIdx.clear();
        packedPrim.clear();
        java.util.Arrays.fill(packedBucketTris, 0);
        packedIdx.ensureCapacity(idx.size());
        packedPrim.ensureCapacity(prim.size());
        int[] indices = idx.elements();
        float[] primitives = prim.elements();
        for (int bucket = 0; bucket < RtAccel.ENTITY_BUCKETS; bucket++) {
            for (int tri = 0; tri < triangleCount; tri++) {
                if (alphaBuckets.getInt(tri) != bucket) {
                    continue;
                }
                int indexBase = tri * 3;
                packedIdx.add(indices[indexBase]);
                packedIdx.add(indices[indexBase + 1]);
                packedIdx.add(indices[indexBase + 2]);
                int primBase = tri * 12;
                for (int lane = 0; lane < 12; lane++) {
                    packedPrim.add(primitives[primBase + lane]);
                }
                packedBucketTris[bucket]++;
            }
        }
        if (packedIdx.size() != idx.size() || packedPrim.size() != prim.size()) {
            throw new IllegalStateException("Entity capture contains an invalid alpha bucket");
        }
        return new PackedGeometry(packedIdx, packedPrim, packedBucketTris);
    }

    record PackedGeometry(IntArrayList indices, FloatArrayList primitives, int[] bucketTris) {
        int[] copyBucketTris() {
            return bucketTris.clone();
        }
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        if (uvRemap) { // ModelPart 0..1 UV → sprite's atlas region (block-entity Material sprites)
            u = uvU0 + u * uvDU;
            v = uvV0 + v * uvDV;
        }
        qx[n] = x; qy[n] = y; qz[n] = z;
        qu[n] = u; qv[n] = v;
        qnx[n] = nx; qny[n] = ny; qnz[n] = nz;
        qcol[n] = color;
        if (++n == 4) {
            emitQuad();
            n = 0;
        }
    }

    /**
     * Capture a {@link BakedQuad} (held/dropped items via {@code submitItem}, falling blocks via {@code
     * submitBlockModel}) — its 4 positions transformed by {@code pose}, atlas UV from {@code packedUV},
     * a flat {@code color} tint. These quads carry no authored normal, so emitQuad computes a geometric
     * one. They sample the block atlas (the capture's {@code currentTexSlot} = 0, the bindless fallback).
     */
    public void addBakedQuad(Matrix4f pose, BakedQuad quad, int color) {
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            pose.transformPosition(p.x(), p.y(), p.z(), scratch);
            long uv = quad.packedUV(i);
            qx[n] = scratch.x; qy[n] = scratch.y; qz[n] = scratch.z;
            qu[n] = Float.intBitsToFloat((int) (uv >>> 32));
            qv[n] = Float.intBitsToFloat((int) uv);
            qnx[n] = 0f; qny[n] = 0f; qnz[n] = 0f; // no authored normal → emitQuad falls back to geometric
            qcol[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }
    }

    /** Foil is a separate additive decal; never replace the item's base atlas with the foil texture. */
    void addFoilQuad(Matrix4f pose,BakedQuad quad,net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,int baseSlot,int baseColor) {
        int primStart=prim.size(),vertexStart=verts.size()/3;
        for(int i=0;i<4;i++) {
            Vector3fc p=quad.position(i);var m=pose;
            qx[n]=m.m00()*p.x()+m.m10()*p.y()+m.m20()*p.z()+m.m30();
            qy[n]=m.m01()*p.x()+m.m11()*p.y()+m.m21()*p.z()+m.m31();
            qz[n]=m.m02()*p.x()+m.m12()*p.y()+m.m22()*p.z()+m.m32();
            long packed=quad.packedUV(i);float u=Float.intBitsToFloat((int)(packed>>>32)),v=Float.intBitsToFloat((int)packed);
            qu[n]=sprite==null?u:(u-sprite.getU0())/(sprite.getU1()-sprite.getU0());
            qv[n]=sprite==null?v:(v-sprite.getV0())/(sprite.getV1()-sprite.getV0());
            qnx[n]=qny[n]=qnz[n]=0;qcol[n]=baseColor;
            if(++n==4){emitQuad();n=0;}
        }
        markFoilCoverage(primStart,vertexStart,baseSlot,
                quadUv(quad,0,true),quadUv(quad,0,false),quadUv(quad,1,true),quadUv(quad,1,false),
                quadUv(quad,2,true),quadUv(quad,2,false),quadUv(quad,3,true),quadUv(quad,3,false));
    }
    private static float quadUv(BakedQuad q,int i,boolean u) {
        return Float.intBitsToFloat((int)(u?q.packedUV(i)>>>32:q.packedUV(i)));
    }
    private int packedEffectUv(int vertex) {
        return (Float.floatToFloat16(uvList.getFloat(vertex*2))&65535)
                |(Float.floatToFloat16(uvList.getFloat(vertex*2+1))&65535)<<16;
    }
    /** Bake model glint on the existing base triangles; never introduce opaque effect geometry. */
    void applyModelFoil(int start, int end, int foilSlot, Matrix4f transform, float strength) {
        for (int p = start; p < end; p += 12) {
            int oldFlags = Float.floatToRawIntBits(prim.getFloat(p + 9));
            if ((oldFlags & (512 | 1024 | 16384)) != 0) continue;
            int triangle = p / 12;
            int baseSlot = Math.round(prim.getFloat(p + 7));
            for (int corner = 0; corner < 3; corner++) {
                int vertex = idx.getInt(triangle * 3 + corner);
                float u = uvList.getFloat(vertex * 2), v = uvList.getFloat(vertex * 2 + 1);
                float fu = transform == null ? u : transform.m00() * u + transform.m10() * v + transform.m30();
                float fv = transform == null ? v : transform.m01() * u + transform.m11() * v + transform.m31();
                int packed = (Float.floatToFloat16(fu) & 65535) | (Float.floatToFloat16(fv) & 65535) << 16;
                prim.set(p + (corner == 0 ? 3 : corner == 1 ? 10 : 11), Float.intBitsToFloat(packed));
            }
            prim.set(p + 7, foilSlot);
            // 8192 distinguishes a decorated BASE surface from the pre-existing item decal path.
            prim.set(p + 9, Float.intBitsToFloat((oldFlags & 4096) | 8192 | 16384 | Math.clamp(Math.round(strength * 255f), 0, 255) | (baseSlot << 16)));
        }
    }

    void markFoilCoverage(int primStart,int vertexStart,int baseSlot,
            float u0,float v0,float u1,float v1,float u2,float v2,float u3,float v3) {
        for(int t=0;t<2;t++) {
            int p=primStart+t*12,b=vertexStart+(t==0?1:2),c=vertexStart+(t==0?2:3);
            prim.set(p+3,Float.intBitsToFloat(packedEffectUv(vertexStart)));
            prim.set(p+9,Float.intBitsToFloat(16384|(baseSlot<<16)));
            prim.set(p+10,Float.intBitsToFloat(packedEffectUv(b)));
            prim.set(p+11,Float.intBitsToFloat(packedEffectUv(c)));
        }
        int v=vertexStart*2;
        uvList.set(v,u0);uvList.set(v+1,v0);uvList.set(v+2,u1);uvList.set(v+3,v1);
        uvList.set(v+4,u2);uvList.set(v+5,v2);uvList.set(v+6,u3);uvList.set(v+7,v3);
    }

    void addTriangleVertex(float x,float y,float z,int color,float u,float v,float nx,float ny,float nz) {
        addVertex(x,y,z,color,u,v,0,0,nx,ny,nz);
        if(n==3) {
            qx[3]=qx[2];qy[3]=qy[2];qz[3]=qz[2];qu[3]=qu[2];qv[3]=qv[2];
            emitQuad();
            idx.size(idx.size()-3);prim.size(prim.size()-12);alphaBuckets.size(alphaBuckets.size()-1);
            verts.size(verts.size()-3);uvList.size(uvList.size()-2);
            int alpha=(qcol[0]>>>24)|((qcol[1]>>>24)<<8)|((qcol[2]>>>24)<<16);
            if(alpha!=0xFFFFFF)alphaBuckets.set(alphaBuckets.size()-1,RtAccel.ENTITY_BUCKET_ANY_HIT);
            int start=prim.size()-12;
            // Triangle effects need barycentric alpha AND colour, e.g. the dragon's fading death rays.
            // RGB1 replaces the otherwise unused custom-triangle emission float; RGB2 uses aux1.
            // 24-bit RGB is always a finite float bit-pattern; do not pack ARGB NaNs here.
            prim.set(start+9,Float.intBitsToFloat(512|1024));
            prim.set(start+10,Float.intBitsToFloat(alpha));
            prim.set(start+3,Float.intBitsToFloat(qcol[1]&0xFFFFFF));
            prim.set(start+11,Float.intBitsToFloat(qcol[2]&0xFFFFFF));
            n=0;
        }
    }

    private void emitQuad() {
        appendQuad(qx, qy, qz, null, qu, qv, qnx[0], qny[0], qnz[0], qcol[0], false, 0f);
    }

    /**
     * Append one already-transformed model quad without routing its four vertices through the
     * {@link VertexConsumer} accumulator. The direct cuboid path supplies the same polygon order,
     * authored normal, UVs and flat submission colour as {@code ModelPart.Cube.compile}.
     */
    void addDirectQuad(float[] x, float[] y, float[] z, float[] u, float[] v,
                       float nx, float ny, float nz, int color) {
        addDirectQuad(x, y, z, u, v, nx, ny, nz, color, 0f);
    }

    /** Append a direct quad with an explicit per-primitive emission strength. */
    void addDirectQuad(float[] x, float[] y, float[] z, float[] u, float[] v,
                       float nx, float ny, float nz, int color, float emission) {
        appendQuad(x, y, z, null, u, v, nx, ny, nz, color, uvRemap, emission);
    }

    /** Fail fast before a later submission can accidentally complete a malformed custom-geometry quad. */
    void requireCompleteQuads(String label) {
        if (n != 0) {
            int incomplete = n;
            n = 0;
            throw new IllegalStateException(label + " left an incomplete quad (" + incomplete + " vertices)");
        }
    }

    /** Append a face whose positions reference a transformed eight-corner cube template. */
    void addIndexedDirectQuad(float[] x, float[] y, float[] z, int[] corners, float[] u, float[] v,
                              float nx, float ny, float nz, int color) {
        appendQuad(x, y, z, corners, u, v, nx, ny, nz, color, uvRemap, 0f);
    }

    private void appendQuad(float[] x, float[] y, float[] z, int[] corners, float[] u, float[] v,
                            float nx, float ny, float nz, int color, boolean remapUv, float emission) {
        // Authored model normal (pose-transformed by compile); planar quad, so vertex 0's normal is the
        // face normal. Baked quads (items/blocks) pass no normal → fall back to a geometric one from the
        // quad edges. The closest-hit flips it toward the viewer, as for terrain. Computed BEFORE the
        // positions are staged so a same-order offset (below) can push along it.
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 1.0e-6f) {
            int p0 = positionIndex(corners, 0);
            int p1 = positionIndex(corners, 1);
            int p2 = positionIndex(corners, 2);
            float ex1 = x[p1] - x[p0], ey1 = y[p1] - y[p0], ez1 = z[p1] - z[p0];
            float ex2 = x[p2] - x[p0], ey2 = y[p2] - y[p0], ez2 = z[p2] - z[p0];
            nx = ey1 * ez2 - ez1 * ey2;
            ny = ez1 * ex2 - ex1 * ez2;
            nz = ex1 * ey2 - ey1 * ex2;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len > 1.0e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        // Stacked decal layers (banner/shield patterns: base cloth + per-pattern cutout layers, all the
        // SAME coplanar mesh submitted repeatedly via SubmitNodeCollector#order) tie exactly in the BVH —
        // push each later layer outward along the face normal by rank, same fix as terrain's coincident
        // grass-overlay resolution (RtTerrain.QuadCapture), so any-hit cutout lets the ray fall through a
        // discarded pattern texel to the layer behind instead of a random BVH pick.
        boolean offset = currentOrder != 0 && len > 1.0e-6f;
        float off = offset ? ORDER_OFFSET * currentOrder : 0f;

        int base = verts.size() / 3;
        for (int i = 0; i < 4; i++) {
            int p = positionIndex(corners, i);
            verts.add(offset ? x[p] + nx * off : x[p]);
            verts.add(offset ? y[p] + ny * off : y[p]);
            verts.add(offset ? z[p] + nz * off : z[p]);
            float tu=remapUv ? uvU0 + u[i]*uvDU : u[i];
            float tv=remapUv ? uvV0 + v[i]*uvDV : v[i];
            if(textureTransform!=null) {
                float transformedU=textureTransform.m00()*tu+textureTransform.m10()*tv+textureTransform.m30();
                tv=textureTransform.m01()*tu+textureTransform.m11()*tv+textureTransform.m31();tu=transformedU;
            }
            uvList.add(tu);uvList.add(tv);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);
        // Vertex colour as a flat per-prim tint (ARGB → rgb). White (-1) for most models → grey when lit.
        int c = color;
        float tr = ((c >> 16) & 0xFF) * (1f / 255f);
        float tg = ((c >> 8) & 0xFF) * (1f / 255f);
        float tb = (c & 0xFF) * (1f / 255f);
        for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
            prim.add(nx);
            prim.add(ny);
            prim.add(nz);
            prim.add(emission);
            prim.add(tr);
            prim.add(tg);
            prim.add(tb);
            prim.add((float) currentTexSlot); // tint.w = bindless texture slot
            prim.add(Float.intBitsToFloat(currentMaterialId));
            int vertexAlpha=c>>>24;
            prim.add(Float.intBitsToFloat(vertexAlpha==255?0:512)); // uniform vertex alpha
            prim.add(Float.intBitsToFloat(vertexAlpha==255?0:vertexAlpha|(vertexAlpha<<8)|(vertexAlpha<<16))); // aux0
            prim.add(0f); // aux1
            alphaBuckets.add((c>>>24)==255?currentAlphaBucket:RtAccel.ENTITY_BUCKET_ANY_HIT);
        }
    }

    private static int positionIndex(int[] corners, int vertex) {
        return corners == null ? vertex : corners[vertex];
    }

    // Unused VertexConsumer surface — ModelPart.Cube.compile only calls the bulk addVertex above.
    @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setColor(int color) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
