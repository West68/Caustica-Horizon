package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Particle capture adapter. MC's {@code QuadParticleRenderState.buildLayer} drives a {@link VertexConsumer}
 * with the chained form {@code addVertex(x,y,z).setUv(u,v).setColor(argb).setLight(packed)} — four verts
 * per billboard quad. This adapter forwards each completed vertex to {@link RtEntityCapture}'s bulk
 * {@code addVertex} (with a zero normal, so {@code emitQuad} derives a geometric one from the quad edges),
 * letting particles reuse the entity mesh layout + BLAS/geometry-table/append path verbatim.
 *
 * <p>Positions arrive camera-relative ({@code SingleQuadParticle.extract} subtracts the camera position);
 * a per-frame {@link #setOffset offset} (camPos − rebaseOrigin) shifts them into the renderer's rebased
 * space so the TLAS instance transform is identity, exactly like captured entities. The per-particle colour
 * rides through as raw albedo; raygen applies RT direct/indirect lighting instead of baking vanilla's
 * lightmap here. The texture slot comes through {@link RtEntityCapture#currentTexSlot} (set per layer by
 * the caller).
 */
public final class RtParticleCapture implements VertexConsumer {
    private final RtEntityCapture out;
    private float ox, oy, oz;

    // Fully-lit packed lightmap (block 15 << 4 | sky 15 << 20) — default so a particle that never calls
    // setLight renders at full brightness rather than black.
    private static final int FULL_BRIGHT = 0xF000F0;

    // One buffered vertex: the chained protocol calls addVertex first, then setUv/setColor/setLight for the
    // SAME vertex, so the vertex is only complete at the next addVertex (or flush()).
    private boolean pending;
    // Billboards arrive as 4-vertex quads with one colour. Vanilla's ambient effect swirls (beacon effects)
    // are drawn at ~15% alpha and are barely visible; stochastic alpha + denoising turned them into solid
    // bright spirals around the camera. Drop near-transparent quads whole.
    private int quadVertex;
    private boolean skipQuad;
    private boolean rainStyle,weatherStyle;
    private double weatherCamX,weatherCamY,weatherCamZ,weatherTime;
    public void weatherStyle(boolean enabled,double cx,double cy,double cz,double time) {
        weatherStyle=enabled;weatherCamX=cx;weatherCamY=cy;weatherCamZ=cz;weatherTime=time;
    }
    public void rainStyle(boolean enabled) { rainStyle=enabled; }
    private float x, y, z, u, v;
    private int color = 0xFFFFFFFF;
    private int light = FULL_BRIGHT;

    public RtParticleCapture(RtEntityCapture out) {
        this.out = out;
    }

    /** Camera-relative → rebased-space offset (camPos − rebaseOrigin), added to every captured vertex. */
    public void setOffset(float ox, float oy, float oz) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    /** Forward the buffered vertex to the entity capture (zero normal -> geometric; ARGB -> tint; slot). */
    public void flush() {
        if (!pending) {
            return;
        }
        // ITRP weather writes dark neutral streaks; retain the original alpha/shape, not blue cubes.
        // A small coverage, not a dark opaque wall. Snow and ordinary particles keep their colours.
        int tint=rainStyle ? (Math.round((color>>>24)*.18f)<<24)|0xB0B4B8 : color;
        if((quadVertex++&3)==0)skipQuad=!rainStyle && (color>>>24)<64;
        if(skipQuad){pending=false;return;}
        int before=out.prim.size();
        out.addVertex(x, y, z, tint, u, v, 0, 0, 0f, 0f, 0f);
        if(rainStyle && out.prim.size()>before) {
            for(int p=before;p<out.prim.size();p+=12)
                out.prim.set(p+9,Float.intBitsToFloat(Float.floatToRawIntBits(out.prim.getFloat(p+9))|32768));
        }
        pending = false;
    }

    @Override
    public VertexConsumer addVertex(float vx, float vy, float vz) {
        flush(); // finalize the previous vertex (setUv/setColor follow addVertex in the chained protocol)
        // ITRP Weather_VS: camera-relative wind displacement and world-pinned angular disturbance.
        double angle=(vx+weatherCamX)*3+(vy+weatherCamY)*.5+(vz+weatherCamZ)*3+weatherTime*.2;
        x = vx + ox + (weatherStyle?(float)((.1+Math.sin(angle)*.05)*vy):0);
        y = vy + oy;
        z = vz + oz + (weatherStyle?(float)((.05+Math.cos(angle)*.05)*vy):0);
        u = 0f;
        v = 0f;
        color = 0xFFFFFFFF;
        light = FULL_BRIGHT;
        pending = true;
        return this;
    }

    @Override public VertexConsumer setUv(float pu, float pv) { u = pu; v = pv; return this; }
    @Override public VertexConsumer setColor(int c) { color = c; return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        color = (a << 24) | (r << 16) | (g << 8) | b;
        return this;
    }
    @Override public VertexConsumer setLight(int packed) { light = packed; return this; }

    // Unused VertexConsumer surface (buildLayer only calls addVertex/setUv/setColor/setLight).
    @Override public VertexConsumer setUv1(int u1, int v1) { return this; }
    @Override public VertexConsumer setUv2(int u2, int v2) { return this; }
    @Override public VertexConsumer setNormal(float nx, float ny, float nz) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
