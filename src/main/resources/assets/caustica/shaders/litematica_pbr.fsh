#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
layout(std140) uniform ChunkFix {
    ivec2 TextureSize; float ChunkVisibility; int UseRgss; int hasShadersOn;
};
layout(std140) uniform CausticaSchematic {
    uvec4 CeIndex;
    vec4 CeLight;
    vec4 CeControls;
    vec4 CeRadiance;
    vec4 CeOptions;
};
layout(buffer_reference,std430,buffer_reference_align=4) readonly buffer CeUvIndex { uint words[]; };
uniform sampler2D Sampler0;
uniform sampler2D CeDepth;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 ceViewPosition;
out vec4 fragColor;
uniform sampler2D CeSurface0;
uniform sampler2D CeSurfaceB0;
uniform sampler2D CeNormal0;
uniform sampler2D CeNormalB0;
uniform sampler2D CeSpec0;
uniform sampler2D CeSpecB0;
uniform sampler2D CeSurface1;
uniform sampler2D CeSurfaceB1;
uniform sampler2D CeNormal1;
uniform sampler2D CeNormalB1;
uniform sampler2D CeSpec1;
uniform sampler2D CeSpecB1;
uniform sampler2D CeSurface2;
uniform sampler2D CeSurfaceB2;
uniform sampler2D CeNormal2;
uniform sampler2D CeNormalB2;
uniform sampler2D CeSpec2;
uniform sampler2D CeSpecB2;
uniform sampler2D CeSurface3;
uniform sampler2D CeSurfaceB3;
uniform sampler2D CeNormal3;
uniform sampler2D CeNormalB3;
uniform sampler2D CeSpec3;
uniform sampler2D CeSpecB3;
uniform sampler2D CeSurface4;
uniform sampler2D CeSurfaceB4;
uniform sampler2D CeNormal4;
uniform sampler2D CeNormalB4;
uniform sampler2D CeSpec4;
uniform sampler2D CeSpecB4;
uniform sampler2D CeSurface5;
uniform sampler2D CeSurfaceB5;
uniform sampler2D CeNormal5;
uniform sampler2D CeNormalB5;
uniform sampler2D CeSpec5;
uniform sampler2D CeSpecB5;
uniform sampler2D CeSurface6;
uniform sampler2D CeSurfaceB6;
uniform sampler2D CeNormal6;
uniform sampler2D CeNormalB6;
uniform sampler2D CeSpec6;
uniform sampler2D CeSpecB6;
uniform sampler2D CeSurface7;
uniform sampler2D CeSurfaceB7;
uniform sampler2D CeNormal7;
uniform sampler2D CeNormalB7;
uniform sampler2D CeSpec7;
uniform sampler2D CeSpecB7;
vec4 ceSurface(uint page,vec2 uv,float lod) {
 switch(page) {
case 0u:return vec4(textureLod(CeSurface0,uv,lod).rg,textureLod(CeSurfaceB0,uv,lod).rg);
case 1u:return vec4(textureLod(CeSurface1,uv,lod).rg,textureLod(CeSurfaceB1,uv,lod).rg);
case 2u:return vec4(textureLod(CeSurface2,uv,lod).rg,textureLod(CeSurfaceB2,uv,lod).rg);
case 3u:return vec4(textureLod(CeSurface3,uv,lod).rg,textureLod(CeSurfaceB3,uv,lod).rg);
case 4u:return vec4(textureLod(CeSurface4,uv,lod).rg,textureLod(CeSurfaceB4,uv,lod).rg);
case 5u:return vec4(textureLod(CeSurface5,uv,lod).rg,textureLod(CeSurfaceB5,uv,lod).rg);
case 6u:return vec4(textureLod(CeSurface6,uv,lod).rg,textureLod(CeSurfaceB6,uv,lod).rg);
case 7u:return vec4(textureLod(CeSurface7,uv,lod).rg,textureLod(CeSurfaceB7,uv,lod).rg);
} return vec4(0.0);
}
vec4 ceNormal(uint page,vec2 uv,float lod) {
 switch(page) {
case 0u:return vec4(textureLod(CeNormal0,uv,lod).rg,textureLod(CeNormalB0,uv,lod).rg);
case 1u:return vec4(textureLod(CeNormal1,uv,lod).rg,textureLod(CeNormalB1,uv,lod).rg);
case 2u:return vec4(textureLod(CeNormal2,uv,lod).rg,textureLod(CeNormalB2,uv,lod).rg);
case 3u:return vec4(textureLod(CeNormal3,uv,lod).rg,textureLod(CeNormalB3,uv,lod).rg);
case 4u:return vec4(textureLod(CeNormal4,uv,lod).rg,textureLod(CeNormalB4,uv,lod).rg);
case 5u:return vec4(textureLod(CeNormal5,uv,lod).rg,textureLod(CeNormalB5,uv,lod).rg);
case 6u:return vec4(textureLod(CeNormal6,uv,lod).rg,textureLod(CeNormalB6,uv,lod).rg);
case 7u:return vec4(textureLod(CeNormal7,uv,lod).rg,textureLod(CeNormalB7,uv,lod).rg);
} return vec4(0.0);
}
vec4 ceSpec(uint page,vec2 uv,float lod) {
 switch(page) {
case 0u:return vec4(textureLod(CeSpec0,uv,lod).rg,textureLod(CeSpecB0,uv,lod).rg);
case 1u:return vec4(textureLod(CeSpec1,uv,lod).rg,textureLod(CeSpecB1,uv,lod).rg);
case 2u:return vec4(textureLod(CeSpec2,uv,lod).rg,textureLod(CeSpecB2,uv,lod).rg);
case 3u:return vec4(textureLod(CeSpec3,uv,lod).rg,textureLod(CeSpecB3,uv,lod).rg);
case 4u:return vec4(textureLod(CeSpec4,uv,lod).rg,textureLod(CeSpecB4,uv,lod).rg);
case 5u:return vec4(textureLod(CeSpec5,uv,lod).rg,textureLod(CeSpecB5,uv,lod).rg);
case 6u:return vec4(textureLod(CeSpec6,uv,lod).rg,textureLod(CeSpecB6,uv,lod).rg);
case 7u:return vec4(textureLod(CeSpec7,uv,lod).rg,textureLod(CeSpecB7,uv,lod).rg);
} return vec4(0.0);
}
vec2 cePageSize(uint page) { switch(page) {
case 0u:return vec2(textureSize(CeNormal0,0));
case 1u:return vec2(textureSize(CeNormal1,0));
case 2u:return vec2(textureSize(CeNormal2,0));
case 3u:return vec2(textureSize(CeNormal3,0));
case 4u:return vec2(textureSize(CeNormal4,0));
case 5u:return vec2(textureSize(CeNormal5,0));
case 6u:return vec2(textureSize(CeNormal6,0));
case 7u:return vec2(textureSize(CeNormal7,0));
} return vec2(1.0); }

float cf(CeUvIndex data,uint i) {return uintBitsToFloat(data.words[i]);}
vec2 canonical(vec2 uv,vec4 rect) {return rect.xy+clamp(uv,vec2(0.0),vec2(1.0))*rect.zw;}
float heightDepth(uint page,vec2 uv,vec4 rect,float lod) {return 1.0-ceNormal(page,canonical(uv,rect),lod).a;}
vec3 linearColor(vec3 v) {return mix(v/12.92,pow((v+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),v));}
vec3 displayColor(vec3 v) {
    v=clamp(v,vec3(0.0),vec3(1.0));
    return mix(v*12.92,1.055*pow(v,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),v));
}
void main() {
    vec2 screen=gl_FragCoord.xy/CeControls.zw;
    float worldDepth=texture(CeDepth,screen).a; // first visible surface (alpha of the RT albedo guide)
    // Same reversed-Z convention as the RT guide. Preserve world occlusion, including walls.
    if(worldDepth>gl_FragCoord.z+max(1e-6,abs(gl_FragCoord.z)*0.002))discard;
    vec2 atlasUv=texCoord0;
    vec2 atlasDx=dFdx(atlasUv),atlasDy=dFdy(atlasUv);
    vec3 dpdx=dFdx(ceViewPosition),dpdy=dFdy(ceViewPosition);
    vec3 V=normalize(-ceViewPosition);
    vec3 N=cross(dpdx,dpdy);N*=inversesqrt(max(dot(N,N),1e-20));
    if(dot(N,V)<0.0)N=-N;
    vec3 geometricNormal=N;
    vec4 materialRect=vec4(0.0),atlasRect=vec4(0.0);
    uint page=0u,features=0u,maxLod=0u;
    bool found=false;
    if(CeIndex.w!=0u) {
        CeUvIndex data=CeUvIndex(uint64_t(CeIndex.x)|(uint64_t(CeIndex.y)<<32));
        uvec2 cell=uvec2(clamp(floor(atlasUv*float(CeIndex.z)),vec2(0),vec2(CeIndex.z-1u)));
        uint header=(cell.y*CeIndex.z+cell.x)*2u;
        uint offset=data.words[header],count=data.words[header+1u];
        for(uint i=0u;i<count;i++) {
            uint r=data.words[offset+i];
            vec4 ar=vec4(cf(data,r),cf(data,r+1u),cf(data,r+2u),cf(data,r+3u));
            if(all(greaterThanEqual(atlasUv,ar.xy)) && all(lessThan(atlasUv,ar.zw))) {
                atlasRect=vec4(ar.xy,ar.zw-ar.xy);
                materialRect=vec4(cf(data,r+4u),cf(data,r+5u),cf(data,r+6u),cf(data,r+7u));
                page=data.words[r+8u];maxLod=data.words[r+9u];features=data.words[r+10u];found=true;break;
            }
        }
    }
    vec2 uv=found?(atlasUv-atlasRect.xy)/atlasRect.zw:vec2(0);
    vec3 T=vec3(1,0,0),B=vec3(0,1,0);
    vec3 dualU=vec3(0),dualV=vec3(0);
    float lod=0.0,relief=0.0,depthScale=CeControls.x;
    if(found) {
        vec2 dx=atlasDx/atlasRect.zw,dy=atlasDy/atlasRect.zw;
        float det=dx.x*dy.y-dx.y*dy.x;
        if(abs(det)>1e-12) {
            vec3 dpdu=(dpdx*dy.y-dpdy*dx.y)/det;
            vec3 dpdv=(dpdy*dx.x-dpdx*dy.x)/det;
            float a=dot(dpdu,dpdu),b=dot(dpdu,dpdv),c=dot(dpdv,dpdv),metric=a*c-b*b;
            if(metric>1e-12) {dualU=(c*dpdu-b*dpdv)/metric;dualV=(a*dpdv-b*dpdu)/metric;}
            T=normalize(dpdu-N*dot(N,dpdu));B=normalize(cross(N,T))*sign(dot(cross(N,T),dpdv));
        }
        vec2 tileSize=materialRect.zw*cePageSize(page);
        lod=max(0.0,log2(max(length(dx*tileSize),max(length(dy*tileSize),1e-6))));
        float fade=1.0-smoothstep(1.0,2.0,lod);
        if((features&2u)!=0u && CeControls.y>0.0 && fade>0.0) {
            vec2 ray=vec2(dot(V,dualU),dot(V,dualV))*(depthScale*fade/max(dot(N,V),CeControls.y<1.5?0.25:0.08));
            if(CeControls.y<1.5) {relief=heightDepth(page,uv,materialRect,min(lod,float(maxLod)));uv=clamp(uv-ray*relief,vec2(0),vec2(1));}
            else if(heightDepth(page,uv,materialRect,min(lod,float(maxLod)))>1e-6) {
                int steps=min(40,int(CeOptions.x)*4+int((1.0-max(dot(N,V),0.0))*8.0));
                vec2 start=uv;float lo=0.0,hi=1.0;
                for(int i=1;i<=40;i++) {
                    if(i>steps)break;
                    float d=float(i)/float(steps);
                    if(d>=heightDepth(page,start-ray*d,materialRect,min(lod,float(maxLod)))) {hi=d;break;}
                    lo=d;
                }
                for(int i=0;i<3;i++) {float d=(lo+hi)*0.5;
                    if(d>=heightDepth(page,start-ray*d,materialRect,min(lod,float(maxLod))))hi=d;else lo=d;}
                relief=(lo+hi)*0.5;uv=clamp(start-ray*relief,vec2(0),vec2(1));
            }
        }
        atlasUv=atlasRect.xy+uv*atlasRect.zw;
    }
    vec4 texel=textureGrad(Sampler0,atlasUv,atlasDx,atlasDy);
    vec4 color=texel*vertexColor*ColorModulator;
#ifdef ALPHA_CUTOUT
    if(color.a<ALPHA_CUTOUT)discard;
#endif
    float rough=0.8,metal=0.0,emission=0.0,ao=1.0;
    vec3 F0=vec3(0.04);
    if(found) {
        float materialLod=min(lod,float(maxLod));vec2 puv=canonical(uv,materialRect);
        if((features&1u)!=0u) {vec4 s=ceSurface(page,puv,materialLod);rough=s.r;metal=s.g;emission=s.b;F0=ceSpec(page,puv,materialLod).rgb;}
        if((features&2u)!=0u) {
            vec4 normalAo=ceNormal(page,puv,materialLod);vec2 nxy=normalAo.rg*2.0-1.0;
            N=normalize(T*nxy.x+B*nxy.y+N*sqrt(max(1.0-dot(nxy,nxy),0.001)));ao=normalAo.b;
            if(CeControls.y>1.5 && CeOptions.y>0.5 && relief>1e-6) {
                vec2 texel=1.0/max(materialRect.zw*cePageSize(page),vec2(1));
                float hu=heightDepth(page,uv+vec2(texel.x,0),materialRect,materialLod)-heightDepth(page,uv-vec2(texel.x,0),materialRect,materialLod);
                float hv=heightDepth(page,uv+vec2(0,texel.y),materialRect,materialLod)-heightDepth(page,uv-vec2(0,texel.y),materialRect,materialLod);
                vec3 geometric=normalize(cross(dpdx,dpdy));if(dot(geometric,V)<0.0)geometric=-geometric;
                N=normalize(geometric-depthScale*(hu/(2.0*texel.x)*dualU+hv/(2.0*texel.y)*dualV));
            }
        }
    }
    vec3 albedo=linearColor(max(texel.rgb,vec3(0)))*vertexColor.rgb*ColorModulator.rgb;
    vec3 L=normalize(CeLight.xyz),sumVL=V+L;
    vec3 H=sumVL*inversesqrt(max(dot(sumVL,sumVL),1e-12));
    float nl=max(dot(N,L),0.0),nv=max(dot(N,V),0.001),nh=max(dot(N,H),0.0),vh=max(dot(V,H),0.0);
    float alpha=max(rough*rough,0.0025),a2=alpha*alpha;
    float d=a2/(3.14159265*pow(max(nh*nh*(a2-1.0)+1.0,0.0001),2.0));
    float gv=2.0*nv/(nv+sqrt(a2+(1.0-a2)*nv*nv));
    float gl=2.0*nl/(nl+sqrt(a2+(1.0-a2)*nl*nl));
    vec3 fresnel=F0+(1.0-F0)*pow(1.0-vh,5.0);
    vec3 diffuse=albedo*(1.0-metal)/3.14159265;
    vec3 spec=d*gv*gl*fresnel/max(4.0*nv*nl,0.0001);
    // Keep Litematica's already lit base appearance. Applying the world's raw HDR sunlight and
    // a second scene tonemap to this UI-authored preview blew out grass and crushed dark wood.
    // Normal and material response remain independent raster approximations, not RT scene lighting.
    float baseline=0.35+max(dot(geometricNormal,L),0.0);
    float normalResponse=(0.35+nl)/baseline;
    float lightPeak=max(CeRadiance.r,max(CeRadiance.g,CeRadiance.b));
    vec3 lightColor=CeRadiance.rgb/max(1.0,lightPeak);
    vec3 highlight=spec*nl;
    highlight=highlight/(vec3(1.0)+highlight);
    vec3 radiance=albedo*normalResponse*mix(1.0,ao,0.5)*(1.0-metal*0.5)
            +highlight*lightColor*0.35+albedo*(emission/(1.0+emission));
    fragColor=apply_fog(vec4(displayColor(radiance),color.a),sphericalVertexDistance,cylindricalVertexDistance,
            FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);
}
