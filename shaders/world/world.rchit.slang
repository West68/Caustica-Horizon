// Closest-hit shader. Geometry is per-section: InstanceID() indexes a section table (BDA array reached
// from the push constant) holding this section's {prim, uv} buffer addresses. Per-primitive
// {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred to raygen.
//
// tableAddr/entityTableAddr are read from the inline push-constant lanes (pc), not through the
// WorldPush BDA struct: they're read on every single hit (the hottest chain in the frame), and going
// through worldPushAddr first would cost an extra global-memory load just to find the address of the
// load we actually want. The cold WorldPush struct is dereferenced only for the breaking overlay.
import world_common;
import glass_optics;
import environment_effects;
import portal_material;

[[vk::push_constant]] WorldPushConstants pc;

// Private to this closest-hit invocation; query and ordinary shading never share storage.
static bool reliefQuery;
static bool diffuseRay; // diffuse continuation: normal-map detail at its hit averages out of the image
static float3 reliefDirection;
static float reliefInputDepth;

[[vk::binding(2, 0)]] Sampler2D blockAlbedoAtlas;
// Independent bindless arrays: per-draw entity albedo slots and compact canonical material-page bundles.
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];
[[vk::binding(4, 1)]] Sampler2D materialHeightBoxTex[];
// Material pages are BC5: bindings 1-3 hold the RG halves, 5-7 the BA halves (MaterialPageBc5).
[[vk::binding(5, 1)]] Sampler2D materialSurface0BTex[];
[[vk::binding(6, 1)]] Sampler2D materialNormalAoBTex[];
[[vk::binding(7, 1)]] Sampler2D materialSurface1BTex[]; // R8 box means of the height map (RtBlockMaterials.writeHeightBox)

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, float ior, float transmission, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

#include "dh_shading.slang"

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) return EMISSION_SOURCE_OVERRIDE;
    if ((header.features & 128u) != 0u) return 6u;
    if ((header.features & 65536u) != 0u) return 7u;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
}

// Strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. Kept mild because the path
// tracer already computes its own AO from sky-visibility rays (avoid double-darkening).
static const float NORMAL_AO_STRENGTH = 0.5;

// Parallax: a shading-space UV offset driven by the LabPBR height map (the alpha of the _n texture). It
// adds no geometry and no ray - it only shifts which texel the surface reads, so albedo, normal/AO and
// specular must all sample the same displaced UV (see evaluateMaterial). Depth comes from the world push
// flags (see RtComposite.parallaxDepth): 0 = OFF, 1 = one lookup, 2 = bounded adaptive march.
static const uint PARALLAX_FLAG_SHIFT = 5u;
static const uint PARALLAX_FLAG_MASK = 3u;
static const float PARALLAX_SCALE = 0.25;       // max tangential shift at full depth, in material-local UV
static const float PARALLAX_MIN_DEPTH = 1.0 / 255.0;
static const float PARALLAX_MIN_VIEW_Z = 0.25;  // offset limiting: keeps grazing views from streaking
static const float PARALLAX_MAX_LOD = 1.0;      // cone LOD gate: skips distant surfaces AND most secondary hits
static const uint PARALLAX_SMOOTH_STEPS = 3u;   // bounded march; never an unbounded loop

static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
static const float RAY_CONE_MAX_LOD = 12.0;

float2 texSizePx(Sampler2D t) {
    uint w, h;
    t.GetDimensions(w, h);
    return float2(w, h);
}

float rayConeHitWidth(uint rayCone) {
    float2 cone = unpackHalf2(rayCone);
    return max(cone.x + cone.y * max(RayTCurrent(), 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(float3 p0, float3 p1, float2 uv0, float2 uv1, float2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(uint rayCone, float2 textureSizePx, float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth(rayCone) * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(uint rayCone, float2 textureSizePx) {
    float footprint = max(rayConeHitWidth(rayCone) * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

// LabPBR _n decode (shared): rotate the tangent-space normal into world space via a TBN built from the
// hit triangle's vertex positions (VK_KHR_ray_tracing_position_fetch) + UVs. `n` must already be
// oriented toward the viewer (`vdir`). Clamps the result above the horizon so grazing perturbations
// don't invert it through the surface (the black-spot fix). Returns AO (blue) via `ao`; falls back to
// `n` on a degenerate UV triangle. Instance transforms are translation-only, so object edges equal
// world edges.
float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float4 ntex, out float ao) {
    ao = 1.0;
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));       // Gram-Schmidt against the viewer-oriented normal
    // UV determinant alone changes sign if the SAME triangle is reordered. Recover the
    // actual increasing-V derivative, including geometric winding and viewer-side orientation.
    float3 braw = ((p2 - p0) * g1.x - (p1 - p0) * g2.x) * r;
    float3 B = cross(n, T);
    B *= dot(B, braw) < 0.0 ? -1.0 : 1.0;
    float2 nxy = ntex.xy * 2.0 - 1.0;                    // R,G = tangent-space X,Y
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));      // reconstruct Z
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));        // keep above the horizon (no flip)
    }
    ao = lerp(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

struct Surface {
    float3 normal;
    float3 f0;
    float roughness;
    float metalness;
    float emission;
    float sss;
    float ao;
    float porosity;
    // Parallax report: when the material-local UV was displaced, `albedoUv` is the matching albedo-atlas
    // UV and `displaced` is set. The caller owns biome/vertex tint, so it re-samples its own albedo there
    // rather than this function guessing at tint or colour space.
    float2 albedoUv;
    bool displaced;
    float localVisibility;
    // Analytic lights use individual directions; continuation queries use their sampled direction.
    float2 heldVisibility;
    float reliefVisibility;
    float reliefDepth;
};

// Canonical material pages use a compact page index shared by their three dedicated arrays.
float4 samplePageSurface0(uint page, float2 uv, float lod) {
    return float4(materialSurface0Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod).rg,
            materialSurface0BTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod).rg);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    return float4(materialSurface1Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod).rg,
            materialSurface1BTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod).rg);
}
float4 samplePageNormalAo(uint page, float2 uv, float lod) {
    return float4(materialNormalAoTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod).rg,
            materialNormalAoBTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod).rg);
}

#include "smooth_parallax.slang"
#include "sharp_parallax.slang"

// The sole terrain/entity material evaluation path. LabPBR categorical bytes have already been
// decoded on the CPU; every sample below is a continuously filterable physical channel with semantic mips.
// Same curve as RtEmissionHeuristic.compile (alpha coverage is already resolved by any-hit).
float liveEmissionMask(float3 linearAlbedo) {
    float v=saturate(dot(linearAlbedo,float3(0.2126,0.7152,0.0722)));
    return v>=0.1?v*v*v:0.0;
}

Surface evaluateMaterial(MaterialHeader header, float2 atlasUv, float lod, float3 albedo,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        float fallbackRoughness, float fallbackMetalness, float fallbackEmission, bool allowSss,
        bool allowParallax, bool primarySurface, bool modelScaledRelief) {
    Surface surface;
    surface.albedoUv = atlasUv;
    surface.displaced = false;
    surface.localVisibility = 1.0;
    surface.normal = geometricNormal;
    surface.roughness = fallbackRoughness;
    surface.metalness = fallbackMetalness;
    surface.f0 = lerp(float3(0.04, 0.04, 0.04), albedo, fallbackMetalness);
    surface.emission = fallbackEmission;
    surface.sss = 0.0;
    surface.ao = 1.0;
    surface.porosity = 0.0;
    surface.heldVisibility = float2(1,1);
    surface.reliefVisibility = 1.0;
    surface.reliefDepth = -1.0;

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float materialLod = min(lod, maxMaterialLod);
    uint page = header.texturePage;
    bool useHeightNormal=false;
    float3 heightNormal=geometricNormal;
    // A constant/missing alpha was compiled to height=1 on the CPU. OFF never samples a height.
    if (allowParallax && (header.features & MATERIAL_FEATURE_NORMAL) != 0u && lod < 2.0 && !diffuseRay) {
        ConstPtr<WorldPush> parallaxPush=ConstPtr<WorldPush>(pc.worldPushAddr);
        uint mode=(parallaxPush[0].flags >> PARALLAX_FLAG_SHIFT)&PARALLAX_FLAG_MASK;
        float4 options=parallaxPush[0].materialParams;
        float depthWorld=PARALLAX_SCALE*options.x;
        float2 g1=uv1-uv0, g2=uv2-uv0;
        float det=g1.x*g2.y-g1.y*g2.x;
        if(mode!=0 && depthWorld>0 && abs(det)>1e-12) {
            float3 dpdu=((p1-p0)*g2.y-(p2-p0)*g1.y)/det/header.albedoUv.z;
            float3 dpdv=((p2-p0)*g1.x-(p1-p0)*g2.x)/det/header.albedoUv.w;
            float a=dot(dpdu,dpdu), b=dot(dpdu,dpdv), c=dot(dpdv,dpdv);
            float metric=a*c-b*b;
            if(metric>1e-12) {
                // A tile on a held block is uniformly scaled with the item model. Keeping a fixed
                // world-space depth magnifies its relative relief by 1/modelScale. The fourth root
                // of the UV metric determinant is the linear scale (sqrt of tile area), invariant
                // under rotations and mirrored UVs. Terrain keeps its original block-unit depth.
                if(modelScaledRelief)depthWorld*=sqrt(sqrt(metric));
                float3 dualU=(c*dpdu-b*dpdv)/metric;
                float3 dualV=(a*dpdv-b*dpdu)/metric;
                float viewZ=max(dot(vdir,geometricNormal),0.08);
                float fade=1.0-smoothstep(1.0,2.0,lod);
                float2 rayUv=float2(dot(vdir,dualU),dot(vdir,dualV))*(depthWorld*fade/viewZ);
                float2 tileSize=max(float2(1,1),round(header.materialUv.zw*texSizePx(materialNormalAoTex[NonUniformResourceIndex(page)])));
                uint quality=clamp(uint(options.y),2u,8u);
                HeightHit reliefHit; reliefHit.uv=localUv; reliefHit.depth=0; reliefHit.gradient=float2(0);
                if(mode==1) {
                    SharpHeightHit hit=traceSharpHeight(header,localUv,rayUv,tileSize,40u);
                    localUv=hit.uv;
                    reliefHit.uv=hit.uv; reliefHit.depth=hit.depth;
                    surface.displaced=hit.depth>1e-6;
                    surface.reliefDepth=hit.depth;
                    if(surface.displaced && dot(hit.sideNormal,hit.sideNormal)>0 && options.z>0.5) {
                        useHeightNormal=true;
                        heightNormal=normalize(hit.sideNormal.x*dualU+hit.sideNormal.y*dualV);
                    }
                } else {
                    // Full reconstruction/search on the camera surface and whatever a mirror or water shows of it, where
                    // too few steps read as sliced shards; bounded inexpensive secondary approximation under diffuse light.
                    bool sharpRelief=primarySurface || !diffuseRay;
                    // The relief fades out over lod 1..2; past lod 1 a surface spans few texels on screen, so half the
                    // march resolves the same visible depth.
                    uint steps=sharpRelief ? max(8u,uint(float(min(40u,quality*4u+uint((1.0-viewZ)*8.0)))*(1.0-0.5*saturate(lod)))) : 4u;
                    HeightHit hit;
                    if(reliefQuery) {
                        // The first hit already solved this intersection. Reconstruct its exact UV
                        // from full-float depth instead of searching again or quantizing its UV.
                        hit.depth=reliefInputDepth;
                        hit.uv=localUv-rayUv*hit.depth;
                        hit.gradient=float2(0);
                    } else hit=traceSmoothHeight(header,localUv,rayUv,tileSize,steps,sharpRelief ? 3u : 1u);
                    reliefHit=hit;
                    localUv=frac(hit.uv);
                    surface.displaced=hit.depth>1e-6;
                    surface.reliefDepth=hit.depth;
                    if(surface.displaced) {
                        // The height normal is built from the gradient the trace already produced, which costs
                        // nothing extra, but it used to be gated on the primary visible hit. Secondary rays
                        // still ran the search and threw the result away, so GI and reflections kept shading
                        // with the flat geometric normal and the relief vanished exactly where bounced light
                        // is the only thing lighting it. Applying it on every displaced hit is what lets the
                        // surface structure reach the indirect path.
                        useHeightNormal=options.z>0.5;
                        heightNormal=normalize(geometricNormal-depthWorld*fade*(hit.gradient.x*dualU+hit.gradient.y*dualV));
                    }
                    // Protect both packed-page and albedo-atlas neighbours at the final shading sample.
                    float2 inset=0.5/tileSize;
                    localUv=clamp(localUv,inset,1.0-inset);
                }
                // Evaluate the actual outgoing direction, including ANY emissive material or
                // indirect/sky source. There is no light-name list and no nearest-emitter cone.
                if(reliefQuery && surface.displaced) {
                    float lz=dot(reliefDirection,geometricNormal);
                    float visible=0.0;
                    if(lz>0.001) {
                        float2 luv=float2(dot(reliefDirection,dualU),dot(reliefDirection,dualV))
                                *(depthWorld*fade/max(lz,0.01));
                        visible=reliefHeightVisibility(header,reliefHit,tileSize,luv,quality>=6u?6u:4u,mode);
                    }
                    // Squared: a partly occluded relief texel read as almost lit, so torch/lamp relief shadows were faint.
                    surface.reliefVisibility=lerp(1.0,visible*visible,saturate(options.w*parallaxPush[0].surfaceControl.w));
                    return surface;
                }
                if(surface.displaced && !reliefQuery) {
                        float3 light=parallaxPush[0].lightDir.xyz;
                        float lightZ=dot(light,geometricNormal);
                        // Sun self-shadow of the relief: the camera surface only; on a bounced hit it is lost in the
                        // integral and was a second height march per displaced secondary hit.
                        if(primarySurface && options.w>0 && lightZ>0.01) {
                            float2 lightUv=float2(dot(light,dualU),dot(light,dualV))*(depthWorld*fade/max(lightZ,0.1));
                            surface.localVisibility=lerp(1.0,reliefHeightVisibility(header,reliefHit,tileSize,lightUv,quality>=6u ? 6u : 4u,mode),options.w);
                        }
                        // Each analytic held light gets its own height-field visibility.
                        float3 hitPosition=WorldRayOrigin()+WorldRayDirection()*RayTCurrent();
                        for(uint hand=0;hand<2;hand++) {
                            float4 lp=hand==0?parallaxPush[0].mainLightPosition:parallaxPush[0].offLightPosition;
                            float3 delta=lp.xyz-hitPosition;float distance=length(delta);
                            if(options.w>0 && lp.w>0 && distance<lp.w && distance>1e-5) {
                                float3 ld=delta/distance;float lz=dot(ld,geometricNormal);
                                if(lz>0.01) {
                                    float2 luv=float2(dot(ld,dualU),dot(ld,dualV))*(depthWorld*fade/max(lz,0.1));
                                    surface.heldVisibility[hand]=lerp(1.0,reliefHeightVisibility(header,reliefHit,tileSize,luv,4u,mode),options.w);
                                }
                            }
                        }
                    }
                if(surface.displaced) {
                    pageUv=header.materialUv.xy+localUv*header.materialUv.zw;
                    surface.albedoUv=header.albedoUv.xy+localUv/header.albedoUv.zw;
                }
            }
        }
    }
    if(reliefQuery) return surface; // no BRDF/normal/emission work in a visibility query
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION
            | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u) {
        float4 surface0 = samplePageSurface0(page, pageUv, materialLod);
        // surface1 feeds both the LabPBR F0 (rgb) and the override emission mask (a) — fetch it once.
        float4 surface1 = (header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u
                ? samplePageSurface1(page, pageUv, materialLod) : float4(0.0, 0.0, 0.0, 0.0);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            if((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION)==0u)
                surface.porosity=surface1.a;
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            // LabPBR emission is baked from frame 0; gate it by the live atlas texel so animated emitters
            // (campfire/fire flames) follow the current frame instead of a frozen glowing silhouette.
            surface.emission = surface0.b * smoothstep(0.02, 0.25, dot(albedo, float3(0.2126, 0.7152, 0.0722)));
            surface.sss = allowSss ? surface0.a : 0.0;
            surface.f0 = surface1.rgb;
        } else {
            // Eligibility is compiled from block-state semantics; the primitive's exact light level
            // supplies strength. The mask is RtEmissionHeuristic evaluated on the live atlas texel: the
            // page holds only frame 0, which froze animated emitters (campfire, fire, magma...).
            surface.emission = liveEmissionMask(albedo) * fallbackEmission;
        }
        if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) {
            float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
                    & MATERIAL_EMISSION_STRENGTH_MASK)
                    * (MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
            surface.emission = surface1.a * strength;
        }
    }
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u && !diffuseRay) {
        float4 normalAo = samplePageNormalAo(page, pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalAo, surface.ao);
    }
    if(useHeightNormal) surface.normal=heightNormal;
    ConstPtr<WorldPush> controlPtr=ConstPtr<WorldPush>(pc.worldPushAddr); // only the fields used below, not the whole struct
    let control_cloudDetail=controlPtr[0].cloudDetail;
    let control_surfaceControl=controlPtr[0].surfaceControl;
    let control_torchControl=controlPtr[0].torchControl;
    let control_visualLight=controlPtr[0].visualLight;
    float normalCos=max(dot(surface.normal,geometricNormal),1e-3);
    float3 normalTangent=surface.normal-geometricNormal*dot(surface.normal,geometricNormal);
    surface.normal=normalize(geometricNormal*normalCos+normalTangent*control_surfaceControl.y);
    // Optional compatibility for packs encoding black (G=0) as an unspecified dielectric F0.
    // Authored positive F0, conductors, glass/water and portal overrides are not replaced.
    if((header.features & MATERIAL_FEATURE_SPEC)!=0u && surface.metalness<.5
            && max(surface.f0.r,max(surface.f0.g,surface.f0.b))<=0.0)
        surface.f0=float3(control_cloudDetail.w);
    if ((header.features & 1048576u) != 0u) surface.emission *= control_torchControl.x;
    surface.f0*=control_surfaceControl.x;
    surface.localVisibility=lerp(1.0,surface.localVisibility,control_surfaceControl.w);
    surface.heldVisibility=lerp(float2(1,1),surface.heldVisibility,control_surfaceControl.w);
    surface.reliefVisibility=lerp(1.0,surface.reliefVisibility,control_surfaceControl.w);
    if((header.features & 131072u)!=0u) {
        float mask=(header.features & 262144u)!=0u?smoothstep(.025,.35,dot(albedo,float3(.2126,.7152,.0722))):1.0;
        if((header.features & 2097152u)!=0u)mask*=step(.5,atlasUv.x)*(1.0-step(.5,atlasUv.y)); // cube above y=16; base texOffs(0,16) never emits
        // Vanilla entity glow layers are full-bright overlays, not block lights: keep them at the default
        // block-emission gain (4) whatever the user sets for block lights. The raygen multiplies every
        // emitter by visualLight.x, so pre-divide by it here.
        float entityGlowGain=4.0/max(control_visualLight.x,1e-3);
        surface.emission=max(surface.emission,((header.features & 2097152u)!=0u?.3:(header.features & 4194304u)!=0u?.006:.5)*mask*entityGlowGain);
    }
    if((header.features & 96u)!=0u) {
        surface.f0=float3(0);surface.roughness=1;surface.metalness=0;
        surface.ao=1;surface.emission=max(surface.emission,.7)*control_surfaceControl.z;surface.sss=0;
    }
    return surface;
}

// Block-breaking overlay: shared by opaque terrain AND stained-glass/ice. Reconstructs the hit position
// in the same rebased space the breaking list was pushed in, nudges it into the solid block along
// -normal to get the block's integer coordinate, then decal-projects the local [0,1] position onto
// whichever axis pair the normal is most aligned with (same idea as vanilla's
// SheetedDecalTextureGenerator) and multiply-blends the matching destroy-stage crack texture in,
// mirroring vanilla's crumbling blend (DST_COLOR*SRC_COLOR, doubled).
float3 applyBreaking(float3 albedo, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT, float3 n) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo;
    }
    float3 hitPos = rayOrigin + rayDir * hitT;
    int3 blockPos = int3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < breakCount; ++bi) {
        int4 ps = pcRef[0].breaking[bi].posSlot;
        if (all(ps.xyz == blockPos)) {
            float3 local = hitPos - float3(blockPos);
            float3 an = abs(n);
            float2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                           : (an.y >= an.z) ? local.xz
                           : local.xy;
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(ps.w)]));
            float3 crack = srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(ps.w)].SampleLevel(decalUv, decalLod).rgb);
            return clamp(crack * albedo, 0.0, 1.0);
        }
    }
    return albedo;
}

[shader("closesthit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    reliefQuery=(payload.flags & PAYLOAD_RELIEF_QUERY)!=0u;
    diffuseRay=(payload.flags & (PAYLOAD_CAMERA_MISS|PAYLOAD_SHOW_CELESTIAL))==0u;
    reliefDirection=payload.normal; // query input only; ordinary shading overwrites its output
    reliefInputDepth=payload.hitT;
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;

    // Particle billboard: same geom-table/UV/bindless-atlas path as entities. Albedo is atlas texel *
    // per-particle colour; raygen treats material 2 as a primary-visible diffuse receiver with no
    // emission or specular response. Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((InstanceID() & PARTICLE_BIT) != 0u) {
        uint idx = InstanceID() & IDX_MASK;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[idx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];


        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * pid + 0u];
        uint p1 = ib[3u * pid + 1u];
        uint p2 = ib[3u * pid + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        payload.albedo = srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(pslot)].SampleLevel(puv, particleLod).rgb) * pr.tint.rgb;
        if((pr.flags&32768u)!=0u)payload.albedo=float3(.55)*pr.tint.rgb; // neutral translucent rain, not blue/black ink
        payload.normal = pn;
        payload.hitT = RayTCurrent();
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across
        // the billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back
        // to rigidDisp, which particles write as zero.
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = float3(0.0, 0.0, 0.0);
        // Rain marker for world.rgen's ITRP veil: x=-1, y = streak mask (texture alpha, Weather_FS).
        if((pr.flags&32768u)!=0u)payload.f0=float3(-1.0,entityAlbedoTex[NonUniformResourceIndex(pslot)].SampleLevel(puv,particleLod).a,0.0);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0,
                1.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }
    // Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
    // geometry; the low bits index the entity geometry table, not the section table.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[eidx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        if((pr.flags & 2048u)!=0u) {
            shadeDh(payload,g,pr);
            return;
        }
        if ((payload.flags & PAYLOAD_REFLECTION_GUIDE) != 0u) {
            // Alpha tests already ran in any-hit. RR only reads the hit distance and opaque motion;
            // avoid repeating PBR/parallax/texture/weather shading for this non-radiance ray.
            payload.hitT = RayTCurrent();
            payload.flags = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId].model;
            payload.motionPrev = g.rigidDisp.xyz;
            if (g.dispAddr != 0 && (payload.flags & PAYLOAD_MATERIAL_MASK) == MATERIAL_OPAQUE) {
                ConstPtr<uint> indices = ConstPtr<uint>(g.idxAddr);
                ConstPtr<float4> displacement = ConstPtr<float4>(g.dispAddr);
                float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
                payload.motionPrev = bary.x * displacement[indices[3u*pid]].xyz
                    + bary.y * displacement[indices[3u*pid+1u]].xyz
                    + bary.z * displacement[indices[3u*pid+2u]].xyz;
            }
            return;
        }
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        float3 vdir = -WorldRayDirection();
        float3 n = normalize(mul(entityO2w, pr.normal.xyz));
        bool entering = dot(WorldRayDirection(), n) < 0.0;
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        float3 entityBoundaryNormal=n;
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/…).
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * pid + 0u];
        uint e1 = eidxBuf[3u * pid + 1u];
        uint e2 = eidxBuf[3u * pid + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        bool bakedFoil=(pr.flags&16384u)!=0u;
        float3 foilGlow=bakedFoil?srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(int(pr.tint.w+.5))]
                .SampleLevel(foilEffectUv(pr,ebary),0).rgb):float3(0);
        int texSlot = bakedFoil?int(pr.flags>>16u):int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod);
        MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        uint material = header.model;
        // Translucent mob texels (see world.rahit.slang): a clear tinted slab with eta=1 instead of a
        // stochastic coverage mask. Opaque texels of the same layer shade normally.
        bool entityVeil = material == MATERIAL_OPAQUE && (header.features & MATERIAL_FEATURE_STOCHASTIC_ALPHA) != 0u
                && (header.features & 524288u) == 0u && entityTexel.a < 0.99;
        if (entityVeil) material = MATERIAL_GLASS;
        float3 entityTint=pr.tint.rgb;
        bool vertexColor=(pr.flags&1024u)!=0u;
        if(vertexColor) {
            uint rgb1=asuint(pr.normal.w),rgb2=pr.aux1;
            float3 tint1=float3((rgb1>>16u)&255u,(rgb1>>8u)&255u,rgb1&255u)/255.0;
            float3 tint2=float3((rgb2>>16u)&255u,(rgb2>>8u)&255u,rgb2&255u)/255.0;
            entityTint=ebary.x*entityTint+ebary.y*tint1+ebary.z*tint2;
        }
        float3 baseAlbedo = srgbToLinear(entityTexel.rgb) * entityTint;
        float3 albedo = material == MATERIAL_GLASS
                ? glassSlabTint(baseAlbedo,entityTexel.a) : baseAlbedo;
        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, n,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, (vertexColor||bakedFoil)?0.0:pr.normal.w, material == MATERIAL_OPAQUE,
                // Entities were hard-coded out of the parallax path, so a held item or a first-person arm
                // got PBR normals, roughness and F0 but never the height field: the displacement, the
                // displaced UVs and the height-based shading normal all sat behind this flag. Opaque entity
                // surfaces carry the same LabPBR headers as terrain, so they can run the identical path.
                // The camera-miss bit mirrors what the terrain call uses to mean "primary visible surface",
                // which is what a held item seen by the player is.
                material == MATERIAL_OPAQUE, (payload.flags & PAYLOAD_CAMERA_MISS) != 0u, texSlot==0);
        if(reliefQuery) { payload.albedo=float3(surface.reliefVisibility); return; }
        n = surface.normal;
        if (material == MATERIAL_GLASS) {
            // Held/dropped glass uses the entity path, but it is still the same two-boundary
            // dielectric as terrain glass. A normal map on both faces bends the ray twice
            // (and can amplify a nearly coincident pane into a noisy prism). Keep the
            // authored front-face detail, with the same optical slope limit as terrain,
            // and use the geometric normal at the exit face.
            float glassCos = max(dot(n, entityBoundaryNormal), 0.1);
            float3 glassSlope = (n - entityBoundaryNormal * glassCos) / glassCos;
            glassSlope *= min(1.0, 0.18 / max(length(glassSlope), 1.0e-5));
            n = entering ? normalize(entityBoundaryNormal + glassSlope) : entityBoundaryNormal;
        }
        if (surface.displaced) {
            // Keep the entity/held-block albedo on the same relief texel as its material channels,
            // just as for terrain below. The captured tint is unchanged by parallax.
            albedo = srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(texSlot)]
                    .SampleLevel(surface.albedoUv, entityLod).rgb) * entityTint;
        }
        if((header.features & 32u)!=0u) {
            ConstPtr<WorldPush> wPtr=ConstPtr<WorldPush>(pc.worldPushAddr); // only the fields used below, not the whole struct
            let w_curViewProj=wPtr[0].curViewProj;
            let w_surfaceControl=wPtr[0].surfaceControl;
            let w_waterParams=wPtr[0].waterParams;
            float4 projected=mul(w_curViewProj,float4(WorldRayDirection(),0));
            float2 portalUv=projected.xy/max(abs(projected.w),1e-4)*.5+.5;
            albedo=float3(0);
            for(uint layer=0;layer<16;layer++) {
                float2 suv=frac(portalLayerUv(portalUv,w_waterParams.w,layer));
                float3 texel=entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(suv,0).rgb;
                albedo+=srgbToLinear(texel)*portalLayerColor(layer);
            }
            // Emission uses its own intensity instead of the global blocklight multiplier.
            surface.emission=w_surfaceControl.z;
            surface.f0=float3(0);

        }

        // Continuous additive tint retains the base texture instead of stochastic purple replacement.
        if(bakedFoil) {
            albedo+=foilGlow*.06;
            surface.emission=max(surface.emission,.006*max(foilGlow.r,max(foilGlow.g,foilGlow.b)));
        }
        payload.albedo = material==MATERIAL_GLASS?albedo:albedo*surface.ao;
        payload.normal = n;
        payload.hitT = RayTCurrent();
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the same
        // indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = material==MATERIAL_GLASS?entityBoundaryNormal:surface.f0;
        float emission = material == MATERIAL_OPAQUE ? surface.emission : 0.0;
        float sss = material == MATERIAL_OPAQUE ? surface.sss : 0.0;
        payloadSetPacked(payload, material, surface.roughness, surface.metalness,
                emission, sss, entityVeil ? 1.0 : header.params.z, entityVeil ? 1.0 : header.params.w,
                (header.features & 32u)!=0u?EMISSION_SOURCE_PORTAL:materialEmissionSource(header, emission));
        if(g.reserved.x!=0u) payload.flags |= PAYLOAD_HELD_OWNER;
        if(g.reserved.y!=0u) payload.iorTransmission |= FIRST_PERSON_RECEIVER;
        payload.flags |= uint(round(saturate(1.0-surface.localVisibility)*255.0)) << 8u;
        payload.flags |= uint(round(saturate(1.0-surface.heldVisibility.x)*255.0)) << 16u;
        payload.flags |= uint(round(saturate(1.0-surface.heldVisibility.y)*15.0)) << 24u;
        if(material==MATERIAL_OPAQUE && surface.displaced) {
            ConstPtr<WorldPush> wPtr=ConstPtr<WorldPush>(pc.worldPushAddr); // only the fields used below, not the whole struct
            let w_materialParams=wPtr[0].materialParams;
            let w_surfaceControl=wPtr[0].surfaceControl;
            if(w_materialParams.w*w_surfaceControl.w>0.0) {
                payload.flags |= PAYLOAD_HAS_RELIEF;
                payload.rayCone=asuint(surface.reliefDepth);
            }
        }
        if ((material == MATERIAL_WATER || material == MATERIAL_GLASS) && entering) {
            payload.flags |= PAYLOAD_WATER_ENTERING;
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket (solid / cutout /
    // translucent / water). PrimitiveIndex() restarts at 0 per geometry, so re-add this geometry's
    // triangle base to land in the section's packed prim/uv arrays (concatenated in BUCKET order).
    // Water prims keep tint.w == 1, converted to the packed material enum below.
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = terrainPrimitive(sec.primAddr,pid);
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
    if ((payload.flags & PAYLOAD_REFLECTION_GUIDE) != 0u) {
        payload.hitT = RayTCurrent();
        payload.motionPrev = float3(0);
        payload.flags = materialHeader.model == MATERIAL_WATER ? MATERIAL_WATER : MATERIAL_OPAQUE;
        return;
    }
    float3 n = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    // Water prims are single-sided, wound outward from the fluid volume (RtFluidMesher), so the
    // UNFLIPPED geometric normal is a reliable "which side is water" signal: a ray travelling against
    // it is entering the volume, one travelling with it is exiting. Captured before the toward-viewer
    // flip below and carried in the payload so raygen can set inWater from hit orientation instead of
    // toggling parity per crossing (a single missing/extra face no longer desyncs the rest of the path).
    bool entering = dot(WorldRayDirection(), n) < 0.0;

    // Lever B: per-triangle corner UVs in primitive order. uvs[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer still exists for the BLAS build; the shading path just no longer reads it.
    ConstPtr<float2> uvs = ConstPtr<float2>(terrainUvTriangleAddress(sec.uvAddr,pid));
    float2 uv0 = uvs[0];
    float2 uv1 = uvs[1];
    float2 uv2 = uvs[2];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAlbedoAtlas), tp0, tp1, tp2, uv0, uv1, uv2);

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    float3 vdir = -WorldRayDirection(); // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }

    // Stained glass / ice: a thin colored filter resolved in raygen
    // (material 3). albedo carries the transmission tint = texel rgb mixed toward white by (1 − opacity),
    // so a more opaque texel tints transmitted light more strongly.
    if (materialHeader.model == MATERIAL_GLASS) {
        float4 gtex = blockAlbedoAtlas.SampleLevel(uv, blockLod);
        float3 gtexRgb = srgbToLinear(gtex.rgb);
        float3 glassAlbedo = glassSlabTint(gtexRgb*tint,gtex.a);
        float3 glassBoundaryNormal=n;
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, n,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false, false, false, false);
        // A pane's two nearly coincident faces sample the same authored normal texture. Applying
        // that micro-relief at both boundaries nearly cancels the visible bend, while the second
        // high-frequency normal amplifies the noisy reflection/refraction decision. Treat the
        // front of the slab as the textured optical surface and its rear as optically polished.
        // Either viewing side remains symmetric: "entering" always identifies the first face.
        float glassCos=max(dot(glassSurface.normal,glassBoundaryNormal),0.1);
        float3 glassSlope=(glassSurface.normal-glassBoundaryNormal*glassCos)/glassCos;
        // Large authored normal spikes turn a 1/8-block pane into a noisy wide-angle prism.
        // Preserve small PBR detail while bounding the front-face optical slope to about 10 degrees.
        glassSlope*=min(1.0,0.18/max(length(glassSlope),1.0e-5));
        // Vanilla clear glass has no authored normal map. Add smooth, world-anchored
        // micro-relief only when its authored normal is flat. Both colours and the
        // stochastic path remain untouched; the phase never depends on frame/camera.
        if(entering && min(glassAlbedo.x,min(glassAlbedo.y,glassAlbedo.z))>0.98
                && glassSurface.roughness<=0.15 && dot(glassSlope,glassSlope)<1.44e-4) {
            ConstPtr<WorldPush> glassFramePtr=ConstPtr<WorldPush>(pc.worldPushAddr); // only the fields used below, not the whole struct
            let glassFrame_camOffset=glassFramePtr[0].camOffset;
            let glassFrame_cloudWorld=glassFramePtr[0].cloudWorld;
            float3 stableHit=WorldRayOrigin()+WorldRayDirection()*RayTCurrent()
                    -glassFrame_camOffset+glassFrame_cloudWorld.xyz;
            float3 axis=abs(glassBoundaryNormal);
            bool xFace=axis.x>axis.y && axis.x>axis.z;
            float3 tangent=xFace ? float3(0,1,0) : float3(1,0,0);
            float3 bitangent=xFace || axis.y>axis.z ? float3(0,0,1) : float3(0,1,0);
            float2 phase=float2(dot(stableHit,tangent),dot(stableHit,bitangent))*4.5;
            float2 wave=sin(phase),gradient=cos(phase);
            glassSlope+=(tangent*(gradient.x*wave.y)+bitangent*(wave.x*gradient.y))*0.04;
        }
        n = entering ? normalize(glassBoundaryNormal+glassSlope) : glassBoundaryNormal;
        // Translucent blocks (glass, ice, …) are breakable too — apply the same overlay here, reusing
        // gtex (already sampled above) rather than re-fetching blockAlbedoAtlas.
        payload.albedo = applyBreaking(glassAlbedo,
                rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n);
        payload.normal = n;
        payload.hitT = RayTCurrent();
        payload.motionPrev = float3(0.0, 0.0, 0.0);
        payload.f0 = glassBoundaryNormal;
        payloadSetPacked(payload, MATERIAL_GLASS, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, materialHeader.params.z,
                materialHeader.params.w, EMISSION_SOURCE_NONE);
        payload.flags |= PAYLOAD_STATIC_GLASS;
        if(entering)payload.flags |= PAYLOAD_WATER_ENTERING;
        return;
    }

    // Water (tint.w == 1) carries the pure biome water tint (no grey water-texture multiply): raygen
    // shades the surface as a clear dielectric and only needs the tint to derive the per-channel
    // Beer–Lambert absorption. Opaque terrain uses textured albedo.
    payload.albedo = materialHeader.model == MATERIAL_WATER
            ? tint : srgbToLinear(blockAlbedoAtlas.SampleLevel(uv, blockLod).rgb) * tint;
    payload.hitT = RayTCurrent();
    payload.motionPrev = float3(0.0, 0.0, 0.0); // static terrain: camera-only motion vector
    uint material = materialHeader.model == MATERIAL_WATER ? MATERIAL_WATER : MATERIAL_OPAQUE;

    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout /
    // translucent) render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, payload.albedo, n,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid, true, (payload.flags & PAYLOAD_CAMERA_MISS)!=0u, false);
    if(reliefQuery) { payload.albedo=float3(surface.reliefVisibility); return; }
    n = surface.normal;
    if (surface.displaced) {
        // Parallax moved the material-local UV, so re-read the albedo atlas at the same displaced texel and
        // re-apply the untouched biome/vertex tint. Without this the colour would stay at the texel the
        // any-hit pass sampled while the normal, AO, roughness and specular had already shifted.
        payload.albedo = srgbToLinear(blockAlbedoAtlas.SampleLevel(surface.albedoUv, blockLod).rgb) * tint;
    }
    payload.albedo *= surface.ao;
    // Block-breaking overlay: opaque/cutout terrain (water skips — fluids aren't breakable). Evaluate
    // it after normal mapping so decal projection uses the same shading orientation as the old path.
    if (material != MATERIAL_WATER) {
        payload.albedo = applyBreaking(payload.albedo, rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n);
    }
    ConstPtr<WorldPush> weatherPtr=ConstPtr<WorldPush>(pc.worldPushAddr); // only the fields used below, not the whole struct
    let weather_dimensionControl=weatherPtr[0].dimensionControl;
    let weather_environment=weatherPtr[0].environment;
    let weather_flags=weatherPtr[0].flags;
    let weather_mediumParams=weatherPtr[0].mediumParams;
    let weather_waterAnchor=weatherPtr[0].waterAnchor;
    let weather_waterParams=weatherPtr[0].waterParams;
    float3 geometricUp=normalize(pr.normal.xyz);
    float skyAccess=(pr.flags&1u)!=0u
            ? dot(bary,float3(pr.aux0&255u,(pr.aux0>>8u)&255u,(pr.aux0>>16u)&255u))*(15.0/255.0)
            : float(pr.aux0);
    float wet=weather_environment.x==0.0 ? saturate(weather_environment.y)*smoothstep(10.0,15.0,skyAccess)
            *smoothstep(0.1,0.9,geometricUp.y) : 0.0;
    if(wet>0 && material==MATERIAL_OPAQUE) {
        float3 hit=WorldRayOrigin()+WorldRayDirection()*RayTCurrent();
        float2 stable=hit.xz+weather_waterAnchor.xy;
        // Smooth world-space puddles grow as retained wetness accumulates, instead of random hard tiles.
        float pool=puddleField(stable*.18);
        float puddle=smoothstep(.52-.28*wet,.72-.28*wet,pool)*weather_dimensionControl.z;
        // Fill relief recesses before raised faces; the displaced material UV/normal remains authoritative.
        if(surface.reliefDepth>=0.0)puddle*=smoothstep(.005,.07,surface.reliefDepth);
        // ITRP modulates full wetness spatially, then mixes smoothness toward 1.
        // The old .35 floor left rough substrates rough even in full rain.
        float film=wet*lerp(.8,1.0,puddle);
        if((weather_flags & 512u)!=0u) {
            // ITRP Ripple.glsl ApplyPorosity: absorption and wetness share the same adjusted wet value.
            film=saturate(film*(1.8-surface.porosity)-surface.porosity*.2);
            film*=saturate(dot(n,float3(0,1,0))*.5+.5);
            payload.albedo*=1.0-film*sqrt(surface.porosity)*.4;
        }
        surface.roughness=max(.02,surface.roughness*(1.0-film));
        // Thin film follows relief; only pools gently level the material normal.
        n=normalize(lerp(n,geometricUp,.35*film*puddle));
        // Dielectric water-film F0: explicit zero-F0 substrates still receive water reflection.
        // Authored conductors retain their material F0; do not turn every wet block into a metal.
        surface.f0=lerp(surface.f0,lerp(float3(.02),surface.f0,surface.metalness),film);
        if((weather_flags & 512u)==0u)payload.albedo*=1.0-film*.15;
        float2 ripple=rainRippleGradient(stable,weather_waterParams.w,weather_mediumParams.y*film*weather_dimensionControl.w);
        float3 rippleWorld=float3(-ripple.x,0,-ripple.y);
        rippleWorld-=n*dot(n,rippleWorld);
        n=normalize(n+rippleWorld); // Never erase the reconstructed POM normal under the wet lobe.
    }
    if(material==MATERIAL_WATER)surface.roughness=max(surface.roughness,wet*.12);
    payload.normal = n;
    payload.f0 = surface.f0;
    payloadSetPacked(payload, material, surface.roughness, surface.metalness,
            surface.emission, surface.sss, materialHeader.params.z, materialHeader.params.w,
            materialEmissionSource(materialHeader, surface.emission));
    payload.flags |= uint(round(saturate(1.0-surface.localVisibility)*255.0)) << 8u;
    payload.flags |= uint(round(saturate(1.0-surface.heldVisibility.x)*255.0)) << 16u;
    payload.flags |= uint(round(saturate(1.0-surface.heldVisibility.y)*15.0)) << 24u;
    if(material==MATERIAL_OPAQUE && surface.displaced) {
        ConstPtr<WorldPush> wPtr=ConstPtr<WorldPush>(pc.worldPushAddr); // only the fields used below, not the whole struct
        let w_materialParams=wPtr[0].materialParams;
        let w_surfaceControl=wPtr[0].surfaceControl;
        if(w_materialParams.w*w_surfaceControl.w>0.0) {
            payload.flags |= PAYLOAD_HAS_RELIEF;
            payload.rayCone=asuint(surface.reliefDepth);
        }
    }
    if(skyAccess>=14.99) payload.flags |= PAYLOAD_RAIN_EXPOSED; // hit-only rain exposure
    if ((pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) payload.flags |= PAYLOAD_EMITTER_IN_LIST;
    if (material == MATERIAL_WATER && entering) {
        payload.flags |= PAYLOAD_WATER_ENTERING;
    }
}
