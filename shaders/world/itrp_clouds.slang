// ITRP-PTGI 0.8.31 style blocky cloud deck for Caustica's sky miss path.
//
// Re-derived from the pack's observable output and its published defaults, not from its source. The
// density field and its coverage/wetness remap are written here as an explicit normalised weighted
// average plus a single affine step; the coverage constants are kept because they define the look.
//
// Structural facts about the target look:
//   * The clouds are NOT a volumetric march. PTGI_CloudPlane intersects a single horizontal plane above
//     the camera, evaluates one cloud colour at that point, and alpha-composites it over the sky. That
//     is already the cheap 2.5D representation, so no low-resolution cloud image is needed and no sky
//     ray pays for a volume traversal.
//   * The density field is sampled in XZ only, so the cloud is a column extruded vertically - that is
//     the "blocky" look, and it is reinforced by flooring the noise coordinate to a unit grid before the
//     lookup. It is not a low-res filter over a smooth volume.
//   * Density is 3 octaves with a gain of 0.23 (much lower than the 0.5 a fBm normally uses), and the
//     coverage threshold is applied after normalising by the weight sum.
//   * Distant clouds are compressed toward the camera along XZ, which gives the characteristic flattened
//     far field instead of an infinite repeating plane.
//   * The plane uses absolute world Y=940 and shape scale 0.002 (~500 blocks per noise tile),
//     wind 0.02/s with a per-octave shear, BOTTOM_BRIGHTNESS 0.7, OUTSCATTER_FACTOR 1.3, SHADOW_STRENGTH
//     0.0 (no cloud shadows reach the terrain by default), coverage 0.24 clear / 0.96 rain, density 1.25
//     clear / 10.0 rain, sunlighting 0.2 / 0.4, skylighting 1.0 / 0.8.
//
// Default SkyImage behaviour is reconstructed independently below; protected noise stays external.

import world_common;
import itrp_atmosphere;

// ---- pack defaults ---------------------------------------------------------------------------------
static const float CLOUD_PLANE_ALTITUDE = 940.0;
static const float CLOUD_SCALE          = 0.002;
static const float CLOUD_WIND           = 0.02;
static const int   CLOUD_OCTAVES        = 3;
static const float CLOUD_OCTAVE_GAIN    = 0.23;
static const float CLOUD_BOTTOM_BRIGHTNESS = 0.7;
static const float CLOUD_OUTSCATTER     = 1.3;

// Clear / rain parameter pairs, straight from the pack's Settings.glsl defaults.
static const float CLOUD_CLEAR_COVERY = 0.24;
static const float CLOUD_CLEAR_DENSITY = 1.25;
static const float CLOUD_CLEAR_SUN = 0.2;
static const float CLOUD_CLEAR_SKY = 1.0;
static const float CLOUD_RAIN_COVERY = 0.96;
static const float CLOUD_RAIN_DENSITY = 10.0;
static const float CLOUD_RAIN_SUN = 0.4;
static const float CLOUD_RAIN_SKY = 0.8;

// ---- hash-based value noise ------------------------------------------------------------------------
// Replaces the pack's 64x64 RGBA noise texture with arithmetic, so nothing protected is redistributed.
// The floor() before hashing is what makes the field read as voxels rather than as smooth fBm.
float itrpHash12(float2 p) {
    float3 p3 = frac(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

// UNORM data lookup, linear filtering and repeat wrap. Alpha drives 2D shape; R drives sliced 3D detail.
float itrpDataTexel(uint64_t address, int2 p, uint shift) {
    uint packed=((uint*)address)[(uint(p.y)&63u)*64u+(uint(p.x)&63u)];
    return float((packed>>shift)&255u)/255.0;
}
float itrpDataNoise(uint64_t address,float2 xy,uint channel) {
    int2 p=int2(floor(xy));float2 f=frac(xy);
    return lerp(lerp(itrpDataTexel(address,p,channel),itrpDataTexel(address,p+int2(1,0),channel),f.x),
                lerp(itrpDataTexel(address,p+int2(0,1),channel),itrpDataTexel(address,p+int2(1,1),channel),f.x),f.y);
}

float itrpNoise2D(uint64_t noiseAddr, float2 pos, bool blocky = true) {
    pos += 0.5;
    float2 p = floor(pos);
    float2 f = fract(pos);
    if (blocky) {
    // This is the pack's actual "blocky" mechanism: under PTGI_CLOUDS_BLOCKY it smoothsteps the
    // fractional coordinate three times in a row before the lookup, which pushes the interpolation
    // toward a step function and sharpens every cell boundary. Without it the lookup interpolates
    // linearly and the field reads as smooth streaks. The procedural fallback is an approximation, not the original field.
    f = f * f * (3.0 - 2.0 * f);
    f = f * f * (3.0 - 2.0 * f);
    f = f * f * (3.0 - 2.0 * f);
    }

    if(noiseAddr!=0) return itrpDataNoise(noiseAddr,p+f,24u);
    float a = itrpHash12(p);
    float b = itrpHash12(p + float2(1.0, 0.0));
    float c = itrpHash12(p + float2(0.0, 1.0));
    float d = itrpHash12(p + float2(1.0, 1.0));
    return lerp(lerp(a, b, f.x), lerp(c, d, f.x), f.y);
}

float itrpHash13(float3 p) {
    float3 p3 = frac(p * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// 3D value noise with plain trilinear interpolation. The pack's 3D lookup has no blocky loop - only its
// 2D one does - so no smoothstepping here: this is the fine detail source, and sharpening it would turn
// the cloud edges into staircases instead of the ragged fractal lace the original shows.
float itrpNoise3D(uint64_t noiseAddr, float3 pos) {
    pos += 0.5;
    float3 p = floor(pos);
    float3 f = fract(pos);

    if(noiseAddr!=0) {
        float2 uv=p.xy+p.z*17.0+f.xy;
        return lerp(itrpDataNoise(noiseAddr,uv,0u),itrpDataNoise(noiseAddr,uv+17.0,0u),f.z);
    }
    float a = itrpHash13(p);
    float b = itrpHash13(p + float3(1.0, 0.0, 0.0));
    float c = itrpHash13(p + float3(0.0, 1.0, 0.0));
    float d = itrpHash13(p + float3(1.0, 1.0, 0.0));
    float e = itrpHash13(p + float3(0.0, 0.0, 1.0));
    float g = itrpHash13(p + float3(1.0, 0.0, 1.0));
    float h = itrpHash13(p + float3(0.0, 1.0, 1.0));
    float i = itrpHash13(p + float3(1.0, 1.0, 1.0));

    float x0 = lerp(lerp(a, b, f.x), lerp(c, d, f.x), f.y);
    float x1 = lerp(lerp(e, g, f.x), lerp(h, i, f.x), f.y);
    return lerp(x0, x1, f.z);
}

// The pack's edge erosion. Crucially it is indexed by the VIEW DIRECTION, not by world position: a unit
// direction times a scalar sweeps a sphere, and four octaves of that give the fractal lace along cloud
// silhouettes while staying stable in screen space. It is then scaled by distance and subtracted from the
// density, so it eats into cloud edges rather than adding noise on top of them.
float itrpCloudPerturb(uint64_t noiseAddr, float3 dir, float cloudDist, float time, float speed, bool expensive) {
    float3 motion = float3(time * 0.005 * speed, 0.0, 0.0);
    float3 ppos = dir + itrpNoise3D(noiseAddr, dir * 20.0) * 0.02;

    float p = (1.0 - itrpNoise3D(noiseAddr, (ppos - motion) * 50.0)) * 2.0 / (1.0 + cloudDist * 0.0002);
    if (expensive) {
        p += (1.0 - itrpNoise3D(noiseAddr, (ppos - motion) * 100.0)) * 1.0 / (1.0 + cloudDist * 0.0001);
        p += (1.0 - itrpNoise3D(noiseAddr, (ppos - motion) * 200.0)) * 0.5 / (1.0 + cloudDist * 0.00005);
        p += (1.0 - itrpNoise3D(noiseAddr, (ppos - motion) * 400.0)) * 0.25 / (1.0 + cloudDist * 0.000025);
    }
    p *= cloudDist * 0.0015 + 1.0;
    return p * 1.2;
}

// ---- density --------------------------------------------------------------------------------------
// level = octave count, gain = per-octave amplitude falloff. camXZ is needed for the radial compression,
// which must be applied to the camera-relative offset, never to the world origin.
float itrpCloudDensity(uint64_t noiseAddr, float3 pos, float3 camXZ, float time, float speed, float wetness, int level, float gain,
                       float coverBias, float size, bool blocky = true) {
    float3 p = pos;
    float cloudDist = length(p.xz - camXZ.xz);
    p.xz = camXZ.xz + (p.xz - camXZ.xz) / (cloudDist * 0.00015 + 1.0);
    p *= CLOUD_SCALE / size;
    p.xy += float2(1.0, 0.1) * time * CLOUD_WIND * speed;

    // Octave stack written as an explicit normalised weighted average: octave k contributes
    // noise * gain^k and the divisor is the accumulated weight, so the field is scale-free with
    // respect to the octave count instead of relying on an unnormalised running sum.
    float weight = 1.0;
    float weightTotal = 0.0;
    float weightedNoise = 0.0;
    for (int octave = 0; octave < level; ++octave) {
        weightedNoise += itrpNoise2D(noiseAddr, p.xz, blocky) * weight;
        weightTotal += weight;
        p.x += time * CLOUD_WIND * speed;      // per-octave shear: layers must not stack rigidly
        p *= 3.0;                              // lacunarity
        weight *= gain;
    }
    float cloud = weightedNoise / max(weightTotal, 1.0e-4);

    // Coverage threshold and clear/rain blend collapsed into one affine step instead of three
    // sequential updates; the arithmetic is the same remap, evaluated once.
    float clearRain = 1.0 - 0.4 * wetness;
    cloud = (1.5 * cloud - 0.35) * clearRain + 0.6 * wetness;
    return saturate(cloud + coverBias);
}

// Cheap march toward a light: a handful of taps along the ray, accumulating the density the light has to
// cross. Short step counts are what the original's own shadow trace uses in its non-detail path.
float itrpTraceDensity(uint64_t noiseAddr, float3 pos, float3 dir, float cloudDist, float stepLen, int steps, float3 camXZ,
                       float time, float speed, float wetness, float coverBias, float size) {
    float acc = 0.0;
    for (int i = 0; i < steps; i++) {
        pos += dir * stepLen;
        acc += itrpCloudDensity(noiseAddr, pos, camXZ, time, speed, wetness, 2, CLOUD_OCTAVE_GAIN, coverBias, size);
    }
    return acc;
}

// ---- single-point cloud colour ---------------------------------------------------------------------
// Returns rgb = radiance, a = coverage. Mirrors the original's flux decomposition: a sunlight term that
// survives only where the cloud is thin toward the sun, and a skylight term dominated by the ambient sky
// colour, plus a bottom-brightness lift so cloud undersides are not black.
float4 itrpCloudColor(uint64_t noiseAddr, float3 pos, float3 dir, float3 sunDir, float3 sunColor, float3 cirrusSunColor, float3 skyColor, float3 atmosphereColor, float3 camXZ,
                      float time, float speed, float wetness, float sunStrength, float skyStrength, bool expensive, bool cameraDetail,float4 shape,float4 lighting,float4 details) {
    float LdotV = dot(dir, sunDir);
    float cloudDist = length(pos.xz - camXZ.xz);

    float coverBias = shape.x;
    float size = shape.z;
    float densityScale = shape.y; // PTGI defaults use density directly; the clear/rain pair belongs to another cloud path.

    float d = itrpCloudDensity(noiseAddr, pos, camXZ, time, speed, wetness, CLOUD_OCTAVES, CLOUD_OCTAVE_GAIN, coverBias, size);
    // Edge erosion, performed the way the pack does it: the perturb field is subtracted twice, the second
    // time with 0.01 added back, which is what leaves each cloud with a thin translucent rim instead of a
    // hard cut edge. This is the single largest contributor to the original's silhouette.
    // The camera sky uses detail=true; the sky-image path used by secondary lighting is detail=false.
    float perturbNoise = (cameraDetail ? itrpCloudPerturb(noiseAddr,-dir,cloudDist,time,speed,true) : 2.0*(cloudDist*0.0015+1.0)*1.2)*details.x;
    d = max(0.0, d - perturbNoise * 0.005);
    d = max(0.0, d - perturbNoise * 0.005 + 0.01);
    if (d <= 0.0) return float4(0.0, 0.0, 0.0, 0.0);

    // Sun light: march toward the sun. The step count is the quality knob (quality levels differ here and
    // in the sky march below, not in the cloud shape).
    float angular=pow(saturate(0.5-0.5*LdotV),0.25);
    float3 lightDelta=sunDir-dir;
    float3 toward=lightDelta*rsqrt(max(dot(lightDelta,lightDelta),1e-12))-dir*cloudDist*0.0003;
    toward*=angular;
    float3 start=pos-dir*d*0.1*cloudDist;
    float shadow=0.0,skyOccl=0.0;
    float tapLength=200.0*(1.0+perturbNoise*0.05)*saturate(d*0.9+0.1);
    for(int tap=1;tap<=2;tap++) {
        shadow+=max(0.0,itrpCloudDensity(noiseAddr,start+toward*tapLength*float(tap),camXZ,time,speed,wetness,expensive?3:2,CLOUD_OCTAVE_GAIN,coverBias,size)-cloudDist*0.00002);
        skyOccl+=max(0.0,itrpCloudDensity(noiseAddr,pos-dir*angular*tapLength*float(tap),camXZ,time,speed,wetness,expensive?3:2,CLOUD_OCTAVE_GAIN,coverBias,size)-cloudDist*0.00002);
    }
    shadow=max(0.0,(shadow-perturbNoise*0.0021)/(abs(sunDir.y)*2.0+1.0));
    float sunFlux=3.0*exp2(-8.5*shadow)+0.035*exp2(-0.7*shadow);
    float skyFlux=exp2(-2.5*skyOccl)+0.175*exp2(-0.7*skyOccl);
    float sunScatter=sunFlux*itrpPhase(exp(-18.0*d),LdotV)*9.0*0.24;
    if(cameraDetail) sunScatter+=exp2(-15.7*shadow)*10.035*0.24*itrpPhase(0.8,LdotV);

    float3 col=sunColor*sunScatter*sunStrength*lighting.z;
    float thin=exp2(-12.0*d);
    col+=lerp(skyColor*skyFlux,atmosphereColor*0.5,thin)*skyStrength*lighting.y;
    col+=lerp(cirrusSunColor*skyFlux*2.0,cirrusSunColor*7.0,thin)*0.12;
    col+=sunColor*max(0.0,2.5-skyFlux)*0.2*(1.0-wetness)*sunStrength*lighting.z;

    // Coverage -> alpha. Thin edges stay see-through; dense cores go opaque. The distance term lets the
    // far field stay soft instead of turning into a hard wall.
    float nd = d * densityScale / (cloudDist * 0.0001 + 0.25);
    float alpha = 1.0 - exp(-nd * nd * 45.0);
    return float4(col, saturate(alpha));
}

// Exponential column-density integrals in reference radiance units. Work is local to a sky miss;
// no new images, frame uploads or passes are required. Night amplitude is applied before exposure.
float3 itrpCloudBeam(float3 activeLight, float altitude, float nightAmplitude) {
    float elevation=max(activeLight.y,1e-6);
    float column=0.28*exp(-0.5*altitude)/elevation;
    float horizonTail=column*exp(-500.0*elevation);
    return exp(-(float3(0.17,0.40,0.99)+0.05)*column-horizonTail)
        *saturate(activeLight.y*40.0)*nightAmplitude;
}
float3 itrpCloudTransmission(float elevation,float distance,float rain) {
    float k=0.5*elevation;
    float column=0.14*distance*itrpExprel(-k*distance);
    return exp(-(float3(0.17,0.40,0.99)+0.05+rain*0.5)*max(column,0.0));
}
float itrpCirrusDensity(uint64_t noiseAddr,float3 pos,float3 camera,float time) {
    float2 anchor=camera.xz*float2(1.0,0.5);
    float2 delta=pos.xz*float2(1.0,0.5)-anchor;
    float distance=length(delta);
    float2 uv=(anchor+delta/(1.0+distance*0.00005))*0.001;
    float sum=0.0,weight=1.0,norm=0.0;
    for(int octave=0;octave<3;octave++) {
        sum+=itrpNoise2D(noiseAddr,uv+float2(1.0,0.1)*time*0.04)*weight;
        norm+=weight;
        uv.y+=uv.x*0.15*sum;
        uv.x+=uv.y*0.15*sum;
        uv*=2.52;weight*=0.43;
    }
    return saturate((sum/norm+distance*0.00004)*1.5-0.25);
}
float4 itrpCirrusColor(uint64_t noiseAddr,float3 pos,float3 camera,float3 view,float3 light,
        float3 beam,float3 skyUp,float3 atmosphereColor,float time,bool cameraDetail) {
    float distance=length(pos.xz-camera.xz);
    float cosine=clamp(dot(view,light),-1.0,1.0);
    float density=itrpCirrusDensity(noiseAddr,pos,camera,time);
    density=saturate(density-pow(saturate(0.5+0.5*cosine),35.0)*0.35);
    float erosion=3.5;
    if(cameraDetail) {
        float3 inward=-view;
        float3 p=inward-float3(0.0,1.0,0.0)*itrpNoise3D(noiseAddr,inward*50.0)*0.02-density*0.027;
        p*=0.2/(sqrt(abs(inward.y))+0.01);p.x*=0.25;p.z+=p.x*0.4;
        float3 motion=float3(time*0.002,0.0,0.0);
        erosion=2.0*(1.0-itrpNoise3D(noiseAddr,(p-motion)*50.0));
        p+=inward*erosion*0.015;
        erosion+=2.0*(1.0-itrpNoise3D(noiseAddr,(p-motion)*100.0));
        erosion+=1.0-itrpNoise3D(noiseAddr,(p-motion)*200.0);
        erosion+=0.5*(1.0-itrpNoise3D(noiseAddr,(p-motion)*400.0));
    }
    density=max(0.0,density-erosion*(sqrt(distance)*0.08+1.0)*0.02);
    float3 radiance=beam*(6.0*(itrpPhase(exp(-density),cosine)+0.5))
        +lerp(skyUp,atmosphereColor,exp2(-12.0*density));
    return float4(radiance,1.0-exp(-4.0*density*density));
}
float3 itrpCloudNightAdapt(float3 color,float nightFactor) {
    // Rod adaptation expects reference ENV-scaled units, not the native 21-unit scene source.
    float l=dot(color,float3(0.2126,0.7152,0.0722));
    return lerp(color,l*float3(0.3,0.6,1.35),0.6*nightFactor/(1000.0*l+1.0));
}
// Transmittance of the sky behind the last itrpCloudPlane() call (1 = clear), for the display-res night sky.
public static float itrpCloudTransmittance = 1.0;
public float3 itrpCloudPlane(uint64_t noiseAddr,float3 background,float3 dir,float3 light,float3 sun,
        float3 skyUp,float3 atmosphereColor,float3 camera,float time,float rain,int quality,bool cameraRay,float4 shape,float4 lighting,float4 details) {
    itrpCloudTransmittance=1.0;
    if(quality<=0||dir.y<=0.0) return background;
    rain=saturate(rain*details.z);
    float distance=(shape.w-camera.y)/dir.y;
    if(distance<=0.0||distance>1e7) return background;
    float3 pos=camera+dir*distance;
    bool detail=cameraRay && quality>=2;
    float nightAmplitude=sun.y<0.0?0.002:1.0;
    float3 lowBeam=itrpCloudBeam(light,1.0,nightAmplitude);
    float3 highBeam=itrpCloudBeam(light,1.5,nightAmplitude);
    float4 low=itrpCloudColor(noiseAddr,pos,dir,light,lowBeam,highBeam,skyUp,atmosphereColor,
        camera,time,lighting.w,rain,1.0,1.0,quality>=2,detail,shape,lighting,details);
    float4 high=float4(0.0);
    if(details.y>0.0) {
        high=itrpCirrusColor(noiseAddr,pos,camera,dir,light,highBeam*lighting.z,skyUp,atmosphereColor,time*lighting.w,detail);
        high.a=saturate(high.a*details.y);
    }
    float3 transmission=itrpCloudTransmission(dir.y,distance*0.0015,rain);
    float3 groundBeam=itrpCloudBeam(light,0.0,nightAmplitude)*exp2(-float3(0.17,0.40,0.99)*0.2);
    float3 haze=atmosphereColor*(1.0-pow(saturate(dir.y)+0.001,0.4))
        +float3(0.17,0.40,0.99)*0.5*groundBeam*(1.0-rain);
    if(detail) haze=itrpReferenceAtmosphere(dir,sun,1.0,distance*0.0015)
        +itrpReferenceAtmosphere(dir,-sun,1.0,distance*0.0015)*0.002;
    float envScale=0.18/6.6;
    float nightFactor=saturate(-sun.y*10.0);
    low.rgb=itrpCloudNightAdapt((low.rgb*transmission+haze)*envScale,nightFactor)*21.0;
    high.rgb=itrpCloudNightAdapt((high.rgb*transmission+haze)*envScale,nightFactor)*21.0;
    itrpCloudTransmittance=(1.0-high.a)*(1.0-low.a);
    return lerp(lerp(background,high.rgb*lighting.x,high.a),low.rgb*lighting.x,low.a);
}

// Shared ground/volume celestial visibility. Project the receiver into the SAME world-space deck
// used by the visible clouds, with identical density, motion and camera-relative far compression.
// No screen UVs, image pass or extra geometry ray. Secondary NEE receives this too, so GI follows.
public float itrpCloudShadow(WorldPush w,float3 receiver,float3 light) {
    uint mode=(w.flags >> 7u)&3u;
    if(mode==0u || w.environment.x!=0.0 || light.y<=0.001)return 1.0;
    float3 origin=receiver-w.camOffset+w.cloudWorld.xyz;
    float t=(w.cloudShape.w-origin.y)/light.y;
    if(t<=0.0 || t>1e7)return 1.0;
    float3 pos=origin+light*t;
    float distance=length(pos.xz-w.cloudWorld.xz);
    float rain=saturate(w.cloudWorld.w*w.cloudDetail.z);
    // The blocky cell sharpening makes the visible clouds' edges straight and nearly binary. Projected along
    // the sun that drew a hard straight line through rooms lit by a single slit (and across every floor
    // outdoors). A cloud's shadow has a wide penumbra from its thickness and the sun's disk, so the shadow
    // reads the same field with plain interpolation: same clouds, soft edges, and fewer instructions.
    float density=itrpCloudDensity(w.cloudNoiseAddr,pos,w.cloudWorld.xyz,w.waterParams.w,
        w.cloudLight.w,rain,CLOUD_OCTAVES,CLOUD_OCTAVE_GAIN,w.cloudShape.x,w.cloudShape.z,false);
    // Low-detail edge erosion is identical to the secondary sky query's silhouette estimate.
    float erosion=2.0*(distance*.0015+1.0)*1.2*w.cloudDetail.x;
    density=max(0.0,density-erosion*.005);
    density=max(0.0,density-erosion*.005+.01);
    float optical=density*w.cloudShape.y/(distance*.0001+.25);
    float transmission=exp(-optical*optical*45.0);
    // Finite disk / unresolved cloud structure approximation; sky fill still comes from actual GI.
    transmission=lerp(.08,1.0,transmission);
    if(w.cloudDetail.y>0) {
        float cirrus=itrpCirrusDensity(w.cloudNoiseAddr,pos,w.cloudWorld.xyz,w.waterParams.w*w.cloudLight.w);
        transmission*=exp(-.5*cirrus*cirrus*w.cloudDetail.y);
    }
    return saturate(transmission);
}
