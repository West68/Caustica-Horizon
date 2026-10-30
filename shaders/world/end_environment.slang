import world_common;
// Independent End composition from geometric/transfer behaviour, without source or asset redistribution.
// Planet ratios and cycle follow the user reference; noise, particulate BRDF and accretion quadrature
// are approximations. Offline compilation cannot establish visual equivalence.
float4 endSourcePixel(uint64_t address,int2 p) {
    p=(p%128+128)%128;uint packed=ConstPtr<uint>(address)[4100+p.y*128+p.x];
    return float4(packed&255u,(packed>>8u)&255u,(packed>>16u)&255u,packed>>24u)/255.0;
}
float endSourceNoise(uint64_t address,float3 pos) {
    float3 ip=floor(pos),f=frac(pos);float2 uv=float2(ip.x+55*ip.y+f.x,ip.z+f.z)-.5;
    int2 p=int2(floor(uv));float2 t=frac(uv);
    float2 a=lerp(endSourcePixel(address,p).zw,endSourcePixel(address,p+int2(1,0)).zw,t.x);
    float2 b=lerp(endSourcePixel(address,p+int2(0,1)).zw,endSourcePixel(address,p+int2(1,1)).zw,t.x);
    float2 n=lerp(a,b,t.y);return lerp(n.x,n.y,f.y);
}
float3 endBlackbody(float temperature) {
    float T=clamp(temperature,1667.0,25000.0),u=1/T;
    float x=T<4000?((-.2661293e9*u-.2343589e6)*u+.8776956e3)*u+.179910:
                         ((-3.0258469e9*u+2.1070479e6)*u+.2226347e3)*u+.240390;
    float y=T<2222?((-1.1063814*x-1.34811020)*x+2.18555832)*x-.20219683:
            T<4000?((-.9549476*x-1.37418593)*x+2.09137015)*x-.16748867:
                      ((3.0817580*x-5.87338670)*x+3.75112997)*x-.37001483;
    float X=x/max(y,1e-6),Z=(1-x-y)/max(y,1e-6);
    return max(float3(3.24097*X-1.53738-.49861*Z,-.96924*X+1.87597+.04156*Z,.05563*X-.20398+1.05697*Z),float3(0));
}
uint endWellons(uint n) {n^=n>>17;n*=0xed5ad4bbu;n^=n>>11;n*=0xac4c1b51u;n^=n>>15;n*=0x31848babu;n^=n>>14;return n;}
float endRingNoise(float x) {uint a=uint(floor(x));return lerp(float(endWellons(a)),float(endWellons(a+1)),frac(x))/4294967295.0;}
float endHash(float3 p) {
    p=frac(p*float3(.1031,.1030,.0973));p+=dot(p,p.yzx+33.33);
    return frac((p.x+p.y)*p.z);
}
float endNoise(float3 p) {
    float3 c=floor(p),f=frac(p);f=f*f*(3.0-2.0*f);
    float a=lerp(lerp(endHash(c),endHash(c+float3(1,0,0)),f.x),
                 lerp(endHash(c+float3(0,1,0)),endHash(c+float3(1,1,0)),f.x),f.y);
    float b=lerp(lerp(endHash(c+float3(0,0,1)),endHash(c+float3(1,0,1)),f.x),
                 lerp(endHash(c+float3(0,1,1)),endHash(c+float3(1,1,1)),f.x),f.y);
    return lerp(a,b,f.z);
}
static const float3 END_LIGHT = float3(.85649968,.2763556486,.4359310193);
float endCycle(float time) { return frac(time/900.0+.282)*6.28318530718; }
public float endPlanetVisibility(float time) {
    float phase=endCycle(time);
    return 1.0-smoothstep(.35,.62,phase)+smoothstep(1.6,1.87,phase);
}
float3 endRotateY(float3 p,float a) {float c=cos(a),s=sin(a);return float3(c*p.x-s*p.z,p.y,s*p.x+c*p.z);}
float3 endRotateX(float3 p,float a) {float c=cos(a),s=sin(a);return float3(p.x,c*p.y-s*p.z,s*p.y+c*p.z);}
float3 endRotateZ(float3 p,float a) {float c=cos(a),s=sin(a);return float3(c*p.x-s*p.y,s*p.x+c*p.y,p.z);}
float3 endPlanetFrame(float3 p,float time) {
    float phase=endCycle(time);
    return endRotateX(endRotateY(p,phase),1.57079633-.2*sin(phase+3.0)+.1);
}
float endPhase(float g,float c) {
    c=clamp(c,-1.0,1.0);float gg=g*g;
    return .1193662*(1-gg)*(1+c*c)/((2+gg)*pow(max(1+gg-2*g*c,1e-6),1.5));
}
float3 endStarfield(float3 dir,float time) {
    float3 rotated=endRotateY(dir,time*.001);
    float3 light=normalize(END_LIGHT),axis=cross(light,float3(0,0,1));
    float cosine=light.z;
    // Stable Rodrigues alignment; unlike 1/|axis|^2 this also handles the pole limit.
    float3 aligned=cosine*rotated+cross(axis,rotated)+axis*dot(axis,rotated)/max(1+cosine,1e-6);
    float3 q=aligned*384.0;
    float3 cell=floor(q),f=frac(q)-.5;
    float3 hash=frac(cell*float3(443.897,441.423,437.195));hash+=dot(hash,hash.yzx+19.19);
    float2 pair=frac((hash.xx+hash.yz)*hash.zy);
    float threshold=saturate((pair.x-.993)/.007);
    float profile=saturate(1.0-dot(f,f)/.25);
    float t=2*pair.y-4*pair.y*pair.y+3*pair.y*pair.y*pair.y;
    float3 rgb=endBlackbody(lerp(4000.0,9000.0,t));
    return .03*profile*threshold*threshold*rgb;
}
// End nebula: violet gas spread across the whole sky with magenta, rose and teal regions and dark
// dust, slowly turning with the starfield. Broad structure only (render-resolution sky). Camera and
// specular rays only; diffuse misses keep the plain starfield, so End lighting and cost are unchanged.
float endFbm(float3 p) {
    float s=0,a=.5;
    for(uint i=0;i<5;i++) {s+=a*endNoise(p);p=p*2.11+float3(1.7,9.2,4.4);a*=.5;}
    return s/.96875;
}
float3 endNebula(float3 dir,float time) {
    float3 d=endRotateY(dir,time*.001);
    float3 warp=float3(endFbm(d*1.8+3.1),endFbm(d*1.8+11.7),endFbm(d*1.8+23.3))-.5;
    float3 p=d*2.4+warp*1.6;
    float gas=endFbm(p);
    float body=smoothstep(.30,.75,gas);
    float bright=smoothstep(.55,.85,endFbm(p*1.9+5.0));
    float dust=smoothstep(.55,.80,endFbm(p*2.6+17.0));
    float hueA=endFbm(p*.7+41.0),hueB=endFbm(p*.9+57.0);
    float3 col=float3(.42,.20,.95);                                   // violet base
    col=lerp(col,float3(.95,.25,.80),smoothstep(.45,.70,hueA));       // magenta
    col=lerp(col,float3(1.0,.45,.55),smoothstep(.62,.82,hueB)*.7);    // rose
    col=lerp(col,float3(.20,.75,.95),smoothstep(.66,.86,1.0-hueA)*.6);// teal
    float glow=body*(.35+1.4*bright)*(1.0-.75*dust);
    return col*glow*.018+float3(.30,.16,.55)*.0025;                   // + faint violet airglow everywhere
}
// Bounded emission/absorption quadrature along a deflected background ray. No hardware ray is added.
// 50 finite segments, with optional external source noise; no additional hardware ray.
float3 endAccretion(inout float3 ray,float time,uint64_t noiseAddr,float starGain) {
    float3 light=normalize(END_LIGHT);
    float3 position=-light*8.0+ray*3.0;
    float trans=1.0;float3 radiance=float3(0);
    const float dt=.2;
    position+=ray*.1; // midpoint quadrature; the reference uses temporal sub-step jitter.
    for(uint i=0;i<50;i++) {
        float3 disc=endRotateX(endRotateZ(position,-.265-.14835299),.1);
        float r=length(disc),height=disc.y;
        float radial=length(disc.xz),azimuth=atan2(-disc.x,-disc.z);
        float ringDistance=length(float2(radial-.75,height+.02));
        float bloom=r>=.4?1/(ringDistance*ringDistance*ringDistance+.001):0;
        float3 glow=float3(.827,.994,1.566)*bloom*.02;
        float gradient=saturate(1.09-r*.15);
        float thickness=1-abs(height)*20/max(gradient,1e-6);
        float base=pow(gradient,4.0)*(1-gradient)*12.207;
        float haze=saturate(thickness+1)*.03/(height*height+pow(r-.1,4.0)*.001+1e-12);
        float density=saturate(lerp(base*saturate(thickness),haze*haze,.1));
        if(density>.0001) {
            float3 coord=float3(r*30,azimuth*(4-gradient*2.5),height*8);
            float detail=0,weight=1;
            for(uint octave=0;octave<4;octave++) {
                float sampleValue;
                if(noiseAddr!=0)sampleValue=endSourceNoise(noiseAddr,coord);
                else sampleValue=endNoise(coord);
                detail+=weight*sampleValue;
                coord=(coord+float3(time*.2,time*.1,0))*2.7;weight*=.75;
            }
            density*=(saturate(detail*1.5-2.25)*.9+.1)*base;
            float g=pow(1-gradient,4.0),strength=1/(g*200+.002);
            float doppler=sin(azimuth-1.07);
            float temperature=min(2700+strength*20,9999.0)*(1+.25*doppler);
            float3 emission=lerp(endBlackbody(temperature),float3(1.15,.75,1.35),.15-g*.1)*strength*(1+.8*doppler);
            float alpha=1-exp2(-density*7);
            trans*=1-alpha;glow+=alpha*emission;
        }
        radiance+=trans*glow*.02;
        float radius=max(length(position),1e-6);
        float3 bent=ray-position*(.06/(radius*radius*radius));
        ray=bent/max(length(bent),1e-6);position+=ray*dt;
        if(trans<.0001)break;
    }
    return max(radiance,float3(0))+(endStarfield(ray,time)+endNebula(ray,time))*trans*starGain;
}
// ITRP PlanetEnd2 ring optical depth: CurveToLinear(saturate(accum - offset) * scale), faded in over
// the inner 10% of the ring (the smoothstep sits inside the exponent, as in the reference).
float endRingCurve(float r,uint octaves,float offset,float scale) {
    if(r<=1.6 || r>=2.6)return 0;
    float value=0,weight=.5,position=r*15+6;
    for(uint i=0;i<octaves;i++) {value+=weight*endRingNoise(position);position*=4;weight*=.45;}
    float c=saturate(value-offset)*scale;c*=c;
    return c*c*smoothstep(1.6,1.76,r);
}
float endBand(float x,float size,float gain) {
    float a=saturate((x-1+size)*gain);a=a*a*(3-2*a);return a*a;
}
float endSphereHit(float3 origin,float3 dir,float3 center,float radius) {
    float3 offset=origin-center;float b=dot(offset,dir),c=dot(offset,offset)-radius*radius;
    float d=b*b-c;if(d<0)return 1e10;
    float t=-b-sqrt(d);return t>0?t:1e10;
}
public float3 endHalo(float3 direction) {
    // Bounded angular scattering approximation, evaluated on sky misses (including specular misses).
    // Keep the central silhouette dark; both lobes smoothly approach zero away from the disc.
    float angle2=max(2.0*(1.0-dot(normalize(direction),normalize(END_LIGHT))),0.0);
    float tail=exp(-angle2/.055)+.15*exp(-angle2/.24);
    float center=smoothstep(.006,.022,angle2);
    return float3(.022,.025,.035)*(tail*center);
}
public float3 endRadiance(float3 worldDir,float time,uint64_t noiseAddr,bool detailed,float starGain,float haloGain) {
    if(noiseAddr!=0) {
        if(ConstPtr<uint>(noiseAddr)[4096]!=0x454e4431u)noiseAddr=0;
    }
    float3 backgroundDir=worldDir;
    float3 color=detailed?endAccretion(backgroundDir,time,noiseAddr,starGain):endStarfield(worldDir,time)*starGain;
    if(detailed)color+=endHalo(worldDir)*haloGain;
    float3 dir=endPlanetFrame(worldDir,time);
    float3 light=endPlanetFrame(normalize(END_LIGHT),time);
    float3 center=float3(0,-1.75,0);
    float ringTilt=.008*sin(endCycle(time)+4.6);
    float3 ringNormal=float3(0,sin(ringTilt),cos(ringTilt));
    float planetT=endSphereHit(float3(0),dir,center,1.0);
    float atmosphereT=endSphereHit(float3(0),dir,center,1.005);
    if(planetT<1e9) {
        float3 point=dir*planetT,normal=normalize(point-center);
        float nl=saturate(dot(normal,light)),nv=max(dot(normal,-dir),1e-4);
        float3 albedo=float3(.98,.87,.55)*.96;
        // Chandrasekhar H approximation for a particulate surface; horizon-safe Lommel-Seeliger term.
        float3 effective=albedo*(nl*nl*(3-2*nl));
        float3 root=sqrt(1-effective),reflectance=(1-root)/(1+root);
        float u=max(nl,1e-5);
        float3 Hlight=1/max(1-effective*u*(reflectance+(.5-reflectance*u)*log((1+u)/u)),float3(1e-6));
        float3 Hview=1/max(1-effective*nv*(reflectance+(.5-reflectance*nv)*log((1+nv)/nv)),float3(1e-6));
        float3 diffuse=clamp(effective*Hlight*Hview/(12.5663706144*max(nl+nv,1e-4)),float3(0),float3(1));
        float denom=dot(light,ringNormal);
        if(abs(denom)>1e-6) {
            float t=dot(center-point,ringNormal)/denom;
            if(t>0)diffuse*=exp(-endRingCurve(length(point+light*t-center),4,.1,1.6)); // ring shadow on the planet
        }
        float up=saturate(dot(ringNormal,normal)),down=saturate(dot(-ringNormal,normal));
        float3 outward=float3(-light.x,-light.y,0);outward/=max(length(outward),1e-6);
        outward=endRotateX(outward,-ringTilt);
        float away=saturate(dot(outward,normal));
        float ringLight=(endBand(up,1.2,1.5)*(1-endBand(up,3.4,.3))
                       +endBand(down,1.2,1.5)*(1-endBand(down,3.4,.3)))*(1-endBand(away,.7,1.3));
        color=.9*(diffuse+albedo*(.00015+ringLight*.01));
    }
    if(atmosphereT<1e9) {
        float b=dot(dir,center),distance2=max(dot(center,center)-b*b,0.0);
        float shell=4.0*(sqrt(max(1.010025-distance2,0))-sqrt(max(1-distance2,0)));
        float hazeGate=1.0;
        if(planetT<1e9)hazeGate=1-smoothstep(.4,.9,normalize(dir*planetT-center).y);
        color+=.9*float3(.65,.70,1)*shell*shell*endPhase(.8,dot(dir,light))*hazeGate;
    }
    // Ring, as ITRP PlanetEnd2: soft planet shadow across it, brightness following the planet-shadow
    // cycle, and the edge-on moment where the ring collapses to a line and the whole sky dims briefly.
    float planetShadow=endPlanetVisibility(time);
    float ring=0,ringTransmittance=1;
    float denom=dot(dir,ringNormal);
    if(abs(denom)>1e-6) {
        float t=dot(center,ringNormal)/denom;
        if(t>0 && t<planetT) {
            float3 p=dir*t-center;
            ring=endRingCurve(length(p),6,.15,1.4);
            ringTransmittance=exp2(-ring*2.5);
            float axis=length(cross(light,p));
            ring*=.98*max(smoothstep(.8,1.2,axis),step(0,dot(light,p)))+.02;
        }
    }
    float inRing=saturate(abs(ringTilt)*3000-.8);
    ring=lerp(planetShadow*.49+.01,ring,inRing);
    ring*=1+endPhase(.8,dot(dir,light))*10*planetShadow;
    ringTransmittance=lerp(.7,ringTransmittance,inRing);
    color=color*ringTransmittance+ring*(.04*float3(1,.85,.60));
    return max(color,float3(0));
}
public float3 endFogRadiance(float distance,float3 dir,float time) {
    float phase=endCycle(time),d=min(max(distance,0),1024.0);
    // ITRP EndFog also tilts its frame by the ring wobble (-0.008 sin(t + 4.7) on the X angle).
    float3 local=endRotateX(endRotateY(dir,phase),1.57079633-.2*sin(phase+3.0)+.1+.008*sin(phase+4.7));
    float h=abs(local.z)*d*.03;
    float integrated=d*(h<1e-4 ? .69314718 : (1-exp2(-h))/h);
    float shadow=endPlanetVisibility(time);
    float angular=endPhase(saturate(d*.03-.6)*.4,dot(dir,normalize(END_LIGHT)));
    float density=integrated*(angular*(6e-5*.8)*shadow+2e-6*.8)*(.07+.93*shadow);
    return float3(density);
}
