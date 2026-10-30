// Independent closed-form exponential-atmosphere evaluation for the user pack's default PTGI E12 mode.
// No LUT, source text or texture from the pack is embedded. Cornette-Shanks phase and exponential
// density integrals are evaluated directly. Clamped exponentials prevent non-finite half-float outputs.
float itrpExpm1(float x) { return abs(x)<0.001 ? x*(1.0+x*(0.5+x/6.0)) : exp(clamp(x,-80.0,80.0))-1.0; }
float3 itrpExpm1(float3 x) { return float3(itrpExpm1(x.x),itrpExpm1(x.y),itrpExpm1(x.z)); }
float itrpExprel(float x) { return abs(x)<0.001 ? 1.0+x*(0.5+x/6.0) : itrpExpm1(x)/x; }
float3 itrpExprel(float3 x) { return float3(itrpExprel(x.x),itrpExprel(x.y),itrpExprel(x.z)); }
float itrpSoftMin(float a,float b,float width) {
    float h=saturate(0.5+0.5*(b-a)/width);return lerp(b,a,h)-width*h*(1.0-h);
}
float itrpPhase(float g,float cosine) {
    float g2=g*g;float denom=max(1.0+g2-2.0*g*cosine,1e-6);
    return 1.5*(1.0-g2)*(1.0+cosine*cosine)/((2.0+g2)*denom*sqrt(denom));
}
float itrpSignedGuard(float value,float epsilon) {return abs(value)<epsilon ? (value<0?-epsilon:epsilon):value;}
float3 itrpReferenceAtmosphere(float3 view,float3 light,float rain,float lengthLimit) {
    // At this elevation the source's energy factors are below 2.1e-9. Avoid overflow in an invisible lobe.
    if(light.y < -0.2) return float3(0.0);
    float c=clamp(dot(view,light),-1.0,1.0);
    float elevation=-itrpSoftMin(-0.01,-light.y,0.07);
    float v=sign(view.y)*pow(abs(view.y),1.0+saturate(0.5-0.5*c)*0.0005/(elevation+0.0005));
    float falloff=0.5-(0.5-0.5*c)*max(0.0,0.01-light.y)*4.0;
    float k=itrpSignedGuard(falloff*itrpSignedGuard(v,0.0001),0.000001);
    float distance=view.y<0.0?min(lengthLimit,0.4/(0.0118-view.y)):lengthLimit;
    float3 molecular=float3(0.17,0.40,0.99);
    float aerosol=0.05+rain*0.5+0.005/(saturate(view.y)+0.05);
    float3 extinction=molecular+aerosol;
    float ratio=(elevation+itrpExpm1(-1000.0*falloff*elevation)*itrpSignedGuard(v,0.0001))/elevation;
    float3 a=extinction*(0.14/k)*(ratio-1.0);
    float3 difference=extinction*(0.14/k)*ratio*itrpExpm1(-k*distance);
    float column=0.14*(-itrpExpm1(-k*distance))/k;
    float3 single=exp(clamp(a,float3(-80.0),float3(80.0)))*column*itrpExprel(difference);
    float rainEnergy=lerp(1.0,0.25,rain*rain);
    single*=exp(min(light.y,0.0)*100.0)*pow(molecular,float3(0.005/(saturate(light.y)+0.009)))*rainEnergy;
    float3 radiance=single*(molecular*itrpPhase(0.0,c)+aerosol*(0.25*itrpPhase(0.9-rain*0.5,c)+0.75*itrpPhase(0.6-rain*0.5,c)));
    float3 multiple=(-itrpExpm1(-extinction*column))/extinction;
    radiance+=max(multiple,float3(0.0))*extinction*rainEnergy*exp(itrpSoftMin(0.0,light.y,0.03)*200.0)*0.05;
    radiance=max(radiance,float3(0.0))*6.6;
    if(view.y<0.0&&lengthLimit>5.0) radiance+=float3(0.2,0.65,1.0)*exp(-extinction*distance*0.1)*0.5*exp(-extinction*(0.2/(saturate(light.y)+0.001)));
    return min(radiance,float3(65504.0));
}
float3 itrpReferenceSky(float3 view,float3 sun,float rain) {
    return itrpReferenceAtmosphere(view,sun,rain,80.0)+itrpReferenceAtmosphere(view,-sun,rain,80.0)*0.002;
}
// Native NEE uses a peak 21 units versus the reference cloud/sky transfer's unit source.
// This bridges the sky to the existing scene radiance; it does not alter NEE, bounce counts or display exposure.
static const float ITRP_SCENE_SKY_SCALE = (0.18/6.6)*21.0;

// Reference E12 finite ground-to-eye column, including its downward long-path cutoff.
// expm1(x)/x is evaluated at its continuous limit near the horizon, avoiding division by view.y.
float3 itrpLandTransmission(float3 view,float depth,float rain) {
    if(depth<=0.0)return float3(1.0);
    if(view.y<0.0 && depth>10.0)return float3(0.0);
    float column=.14*depth*itrpExprel(-.5*view.y*depth);
    float3 extinction=float3(.17,.40,.99)+.05+.5*saturate(rain);
    return exp(-extinction*max(column,0.0));
}
