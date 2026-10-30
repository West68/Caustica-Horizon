import world_common;
import end_environment;
// Independent low-budget environment approximations; no original ITRP assets or extra image passes.
public float envHash(float3 p) { return frac(sin(dot(p,float3(127.1,311.7,74.7)))*43758.5453); }
public float heldFalloff(float d,float radius) {
    if(radius<=0 || d>=radius)return 0;
    // Flattened near field: 1/(d^2+0.25) put ~8x more light under the hand than 3 blocks out, a bright
    // pool that followed the player. Spread it like block light instead.
    float w=saturate(1.0-pow(d/radius,4.0));return w*w/(d*d+4.0);
}
public float hgPhase(float cosine,float g) {
    return (1.0-g*g)/(12.56637061436*pow(max(1.0+g*g-2.0*g*clamp(cosine,-1.0,1.0),1e-6),1.5));
}
public float2 rainRippleGradient(float2 p,float time,float rain) {
    if(rain<=0)return float2(0,0);
    float2 cell=floor(p*1.6),q=p*1.6;float2 gradient=float2(0,0);
    for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++) {
        float2 id=cell+float2(x,y);
        float seed=envHash(float3(id,19));float age=frac(time*0.8+seed);
        float2 center=id+float2(envHash(float3(id,2)),envHash(float3(id,5)));
        float2 r=q-center;float dist=length(r);float ring=dist-age*.7;
        float envelope=exp(-ring*ring*24.0)*(1.0-age)*(1.0-age)*smoothstep(.02,.12,age);
        float edge=saturate((dist-.8)*5.0);
        float support=1.0-edge*edge*(3.0-2.0*edge);
        float supportDerivative=(dist>.8 && dist<1.0)?-30.0*edge*(1.0-edge):0.0;
        float derivative=envelope*((22.0*cos(ring*22.0)-48.0*ring*sin(ring*22.0))*support
                +sin(ring*22.0)*supportDerivative);
        gradient+=r/max(dist,0.03)*derivative*0.004;
    }
    return gradient*(rain*1.6); // q=p*1.6: include the spatial chain rule
}
public float puddleField(float2 p) {
    float2 cell=floor(p),f=frac(p);f=f*f*(3.0-2.0*f);
    return lerp(lerp(envHash(float3(cell,3)),envHash(float3(cell+float2(1,0),3)),f.x),
                lerp(envHash(float3(cell+float2(0,1),3)),envHash(float3(cell+float2(1,1),3)),f.x),f.y);
}
public float3 dimensionRadiance(float3 dir,float dimension,float time,float3 fogColor,uint64_t noiseAddr,bool detailed,float starGain,float haloGain) {
    if(dimension<1.5)return fogColor; // Nether background is the fog asymptote, no horizon gradient.
    return endRadiance(dir,time,noiseAddr,detailed,starGain,haloGain);
}
