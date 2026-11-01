// Any-hit alpha/tint handling. Radiance rays only invoke this for true alpha cutout. Shadow rays invoke it
// for cutout, translucent, and water buckets so those materials can pass/tint sunlight without closest-hit.
//  * terrain (per-section): InstanceID() indexes the section table → uv buffers, interpolate the atlas
//    UV, discard below the cutoff (leaves/grass/glass see-through).
//  * entity (ENTITY_BIT): InstanceID() (low bits) indexes the entity geometry table → interpolate the
//    entity UV, sample the per-prim bindless entity texture, discard transparent texels (so entity
//    cutout — hat/jacket overlays, eyes, capes, … — is see-through). Accepted hits fall through to the
//    closest-hit. Opaque entity geometry bypasses this shader; every alpha/transmissive material shares
//    one non-opaque geometry and routes here through its SBT record.
//
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace. Shadow traversal uses albedo as accumulated transmittance and hitT as the nearest-water
// crossing; radiance cutout paths do not touch either field.
import world_common;
import glass_optics;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(2, 0)]] Sampler2D blockAlbedoAtlas;
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];

static const float ALPHA_CUTOFF = 0.5;        // terrain (matches the cutout block models)
static const float ENTITY_ALPHA_CUTOFF = 0.1; // entities: only discard near-fully-transparent texels
// Faint baseline extinction added to every translucent hit regardless of its color, so perfectly clear
// glass/ice still dims a shadow ray a little instead of being fully invisible to it (real glass isn't a
// perfect transmitter either). Additive with the color-derived extinction below, not a floor/clamp — a
// tinted pane still absorbs its own color on top of this.
static const float TRANSLUCENT_NEUTRAL_EXTINCTION = 0.15;
static const float WATER_SHADOW_TINT = 0.5;

// Progressive 8x8 blue-noise-style alpha threshold pattern, with a golden-ratio temporal rotation. This keeps
// translucent entity hits stochastic, but trades per-pixel white noise for a stable spatial distribution
// that temporal upscaling can integrate with less sparkle.
static const float ALPHA_DITHER_8X8[64] = {
    0.281250, 0.968750, 0.375000, 0.859375, 0.250000, 0.937500, 0.406250, 0.812500,
    0.593750, 0.187500, 0.734375, 0.046875, 0.578125, 0.218750, 0.703125, 0.015625,
    0.453125, 0.765625, 0.328125, 0.921875, 0.484375, 0.796875, 0.343750, 0.890625,
    0.640625, 0.093750, 0.546875, 0.156250, 0.671875, 0.078125, 0.515625, 0.125000,
    0.265625, 0.953125, 0.421875, 0.828125, 0.296875, 0.984375, 0.390625, 0.843750,
    0.562500, 0.234375, 0.687500, 0.000000, 0.609375, 0.203125, 0.718750, 0.031250,
    0.468750, 0.781250, 0.359375, 0.875000, 0.437500, 0.750000, 0.312500, 0.906250,
    0.656250, 0.062500, 0.500000, 0.140625, 0.625000, 0.109375, 0.531250, 0.171875
};

float alphaDitherThreshold(uint salt) {
    uint2 p = (DispatchRaysIndex().xy + uint2(salt * 3u, salt * 5u)) & uint2(7u, 7u);
    float base = ALPHA_DITHER_8X8[int(p.y * 8u + p.x)];
    return frac(base + float((pc.frameIndex + salt) & 255u) * 0.61803398875);
}

#include "dh_visibility.slang"

[shader("anyhit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    uint pid = PrimitiveIndex();
    float2 attribs = attr.barycentrics;
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);

    uint instanceKind = InstanceID() & (ENTITY_BIT | PARTICLE_BIT);
    if (instanceKind != 0u) {
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID() & IDX_MASK];
        pid += g.triBase[GeometryIndex()];
        ConstPtr<uint> indices = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvs = ConstPtr<float2>(g.uvAddr);
        uint i0 = indices[3u * pid + 0u];
        uint i1 = indices[3u * pid + 1u];
        uint i2 = indices[3u * pid + 2u];
        float2 uv = bary.x * uvs[i0] + bary.y * uvs[i1] + bary.z * uvs[i2];
        Prim epr = ConstPtr<Prim>(g.primAddr)[pid];
        if((epr.flags&2048u)!=0u) {
            dhVisibility(payload,g,epr);
            return;
        }

        int texSlot = int(epr.tint.w + 0.5);
        bool bakedFoil=instanceKind==ENTITY_BIT && (epr.flags&16384u)!=0u;
        if(bakedFoil && entityAlbedoTex[NonUniformResourceIndex(epr.flags>>16u)].SampleLevel(uv,0).a<ENTITY_ALPHA_CUTOFF) {
            IgnoreHit();return;
        }
        // Baked foil shades the base material plus a faint deterministic tint, never an opaque/noisy shell.
        if(bakedFoil) {
            if ((RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER) != 0u && (epr.flags & 8192u) == 0u) IgnoreHit();
            return;
        }
        MaterialHeader materialHeader = (MaterialHeader)0;
        if (instanceKind == ENTITY_BIT) {
            materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[epr.materialId];
            // Glass alpha controls the slab tint, not geometric coverage. Dropping
            // low-alpha texels makes a held clear-glass block disappear exactly where
            // its refraction should be visible. The closest-hit shader samples the
            // texture once and resolves that tint; skip the duplicate any-hit fetch.
            // Shadow rays still sample below to filter transmitted light by texel.
            if (materialHeader.model == MATERIAL_GLASS
                    && (RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER) == 0u) return;
        }
        float2 effectUv=uv;
        float4 texel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(effectUv, 0.0);
        if((epr.flags&512u)!=0u)texel.a*=dot(bary,float3(epr.aux0&255u,(epr.aux0>>8u)&255u,(epr.aux0>>16u)&255u)/255.0);
        bool stochasticAlpha = instanceKind == PARTICLE_BIT;
        // ITRP Weather_FS: a rain texel is either part of the streak (alpha >= 0.1) or absent. The
        // streak's opacity is applied as a deterministic veil in world.rgen, never as random coverage.
        if (instanceKind == PARTICLE_BIT && (epr.flags & 32768u) != 0u) {
            if (texel.a < 0.1 || (RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER) != 0u) IgnoreHit();
            return;
        }
        if (instanceKind == ENTITY_BIT) {
            stochasticAlpha = (materialHeader.features & MATERIAL_FEATURE_STOCHASTIC_ALPHA) != 0u;
            if((materialHeader.features & 524288u)!=0u) {
                // Additive layers carry RGB intensity, often with alpha=1 on a black background.
                // Black is transparent; using alpha alone turns foil/energy shells into solid masks.
                if((RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER)!=0u) {IgnoreHit();return;}
                // Deterministic decal: a bright texel is the glowing layer (its closest hit shades the
                // layer colour and emission), a dark one is absent. The layer is coplanar with the body,
                // so it cannot pass light through like a veil; a random half-coverage crawled under RR.
                if(max(texel.r,max(texel.g,texel.b))<.3) {IgnoreHit();return;}
                return;
            }
            if((materialHeader.features & 32u)!=0u)return; // special portal plane, not sampled star alpha cutout
        }
        // Low alpha in a glass texture is weak colorant, not an opening in
        // the slab. Keep both radiance and shadow boundaries continuous.
        bool shadowQuery = (RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER) != 0u;
        if (stochasticAlpha && instanceKind == ENTITY_BIT && (materialHeader.features & 524288u) == 0u
                && materialHeader.model != MATERIAL_GLASS) {
            // Translucent mob layers (slime shell, ...) are a tinted veil, never a random coverage
            // mask: the closest hit turns texels below 0.99 alpha into a clear eta=1 slab, and shadows
            // are filtered by the same tint. No random number at all, so nothing for DLSS-RR to crawl.
            float a = clamp(texel.a, 0.0, 1.0);
            if (a < 0.02) { IgnoreHit(); return; }
            if (shadowQuery && a < 0.99) {
                payload.albedo *= glassBoundaryTransmission(glassSlabTint(srgbToLinear(texel.rgb) * epr.tint.rgb, a), 1.0);
                IgnoreHit();
            }
            return;
        }
        if (materialHeader.model != MATERIAL_GLASS) {
            // EVERY entity texel test is a cutout test now, never a random coverage mask.
            //
            // The dither that used to live here rolled a per-frame threshold against the texel alpha, so
            // every partly transparent texel of a mob/player layer (resource-pack HD skins and ETF overlay
            // layers are full of them) flipped in and out per frame. DLSS-RR cannot integrate a mask that
            // changes with the frame index: the model came out covered in white sparkle, worst on the lit
            // side, and the same pixels leaked over everything behind it. Translucent mob layers are
            // already a tinted veil (see the branch above, and the eta=1 slab in the closest hit), so
            // coverage here only has to remove genuinely empty texels.
            if (texel.a < ENTITY_ALPHA_CUTOFF) {
                IgnoreHit();
            }
        }

        // Radiance rays accept the surface and continue to closest-hit. Shadow rays use the same material
        // model here so entity dielectrics transmit instead of becoming opaque shadow blockers.
        bool shadowRay = (RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER) != 0u;
        if (instanceKind == ENTITY_BIT && shadowRay && materialHeader.model == MATERIAL_GLASS) {
            float3 tint=glassSlabTint(srgbToLinear(texel.rgb)*epr.tint.rgb,texel.a);
            payload.albedo*=glassShadowBoundary(tint,materialHeader.params.z,materialHeader.params.w);
            IgnoreHit();
        }
        if (instanceKind == ENTITY_BIT && shadowRay && materialHeader.model == MATERIAL_WATER) {
            float3 tint = srgbToLinear(texel.rgb) * epr.tint.rgb;
            payload.albedo *= lerp(float3(1.0, 1.0, 1.0), tint, WATER_SHADOW_TINT)
                    * clamp(materialHeader.params.w, 0.0, 1.0);
            payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
            IgnoreHit();
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    uint bucket = GeometryIndex();
    uint tri = pid + sec.triBase[bucket];

    // Water radiance rays use a closest-hit-only SBT record. Shadow rays run this record: tint sunlight by
    // the biome water color, then keep walking so submerged terrain is lit by colored transmission.
    if (bucket == BUCKET_WATER) {
        TerrainPrim pr = terrainPrimitive(sec.primAddr,tri);
        payload.albedo *= lerp(float3(1.0, 1.0, 1.0), pr.tint.rgb, WATER_SHADOW_TINT);
        // Record the NEAREST water crossing (any-hit order is arbitrary) in the otherwise-unused alpha
        // lane. For an underwater shading point this is the exit point of its sun shadow ray, where
        // world.rgen evaluates the wave-refraction caustic. visibility() seeds the -1 sentinel.
        payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
        IgnoreHit();
    }

    // Translucent radiance rays use a closest-hit-only SBT record; shadow rays tint and pass through here.
    // This doesn't touch the atlas at all: a shadow ray only needs the pane's overall tint/opacity, so
    // MaterialHeader carries a precomputed whole-sprite average {r,g,b,a}; per-primitive biome tint is
    // applied here. The average is compiled once per resource epoch rather than copied per triangle.
    //
    // Modeled as Beer-Lambert absorption (matching the water medium in world.rgen): a per-channel
    // extinction derived from how dark the average is, scaled by the average alpha (how much of the
    // sprite is glass-colorant vs. see-through frame), so saturated panes darken transmitted light
    // non-linearly. Multiplying into payload.albedo compounds correctly across stacked panes.
    if (bucket == BUCKET_TRANSLUCENT) {
        TerrainPrim pr = terrainPrimitive(sec.primAddr,tri);
        MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        if(materialHeader.model==MATERIAL_GLASS) {
            // Use the same local linear tint/alpha as camera transmission, not a raw gamma average.
            ConstPtr<float2> guv=ConstPtr<float2>(terrainUvTriangleAddress(sec.uvAddr,tri));
            float2 coord=bary.x*guv[0]+bary.y*guv[1]+bary.z*guv[2];
            float4 texel=blockAlbedoAtlas.SampleLevel(coord,0.0);
            float3 tint=glassSlabTint(srgbToLinear(texel.rgb)*pr.tint.rgb,texel.a);
            payload.albedo*=glassShadowBoundary(tint,materialHeader.params.z,materialHeader.params.w);
            IgnoreHit();
            return;
        }
        // Per-texel cutout FIRST: "fancy" leaves and HD leaf packs keep their holes in the translucent
        // layer, and the whole-sprite average below is opaque. Without this test every leaf block became a
        // solid cube that also blocked its own shadow ray, so leaves no longer let light through at all.
        ConstPtr<float2> cutoutUv = ConstPtr<float2>(terrainUvTriangleAddress(sec.uvAddr, tri));
        float2 cutoutCoord = bary.x * cutoutUv[0] + bary.y * cutoutUv[1] + bary.z * cutoutUv[2];
        float4 cutoutTexel = blockAlbedoAtlas.SampleLevel(cutoutCoord, 0.0);
        if (cutoutTexel.a < ALPHA_CUTOFF) {
            IgnoreHit();
            return;
        }
        float3 avgColor = max(materialHeader.average.rgb * pr.tint.rgb,
                float3(1.0e-3, 1.0e-3, 1.0e-3));
        float3 colorExtinction = max(-log(avgColor), float3(0.0, 0.0, 0.0));
        // The neutral floor is a flat per-hit dimming, NOT scaled by alpha: vanilla clear glass has a low
        // natural alpha, so folding it into the alpha-scaled term crushed it to near-zero for exactly the
        // white/clear-glass case it's meant to cover.
        payload.albedo *= exp(-colorExtinction * materialHeader.average.a - TRANSLUCENT_NEUTRAL_EXTINCTION);
        IgnoreHit();
    }

    // Cutout geometry: alpha from the atlas. PrimitiveIndex() restarts at 0 per geometry, so re-add this
    // bucket's triangle base to index the section's packed corner-UV array (3 UVs per triangle).
    ConstPtr<float2> uvs = ConstPtr<float2>(terrainUvTriangleAddress(sec.uvAddr,tri));
    float2 uv = bary.x * uvs[0] + bary.y * uvs[1] + bary.z * uvs[2];
    if (blockAlbedoAtlas.SampleLevel(uv, 0.0).a < ALPHA_CUTOFF) {
        IgnoreHit();
    }
}
