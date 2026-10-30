// Projective portal layers. Caller samples actual RenderType Sampler1, not End-sky Sampler0.
public float2 portalLayerUv(float2 projected,float time,uint index) {
    float layer=float(index)+1;
    float angle=(layer*layer*4321.0+layer*9.0)*.03490658504;
    // texProj * (scaleRotate * translate * SCALE_TRANSLATE): row-vector order matters.
    float c=cos(angle),s=sin(angle);
    float2 uv=float2(c*projected.x-s*projected.y,s*projected.x+c*projected.y)*(9-layer*.5);
    uv+=float2(17/layer,(2+layer/1.5)*frac(time/1200.0)*1.5);
    return uv*.5+float2(.25,.25);
}
public float3 portalLayerColor(uint i) {
    static const float3 colors[16]={
        float3(.022087,.098399,.110818),float3(.011892,.095924,.089485),
        float3(.027636,.101689,.100326),float3(.046564,.109883,.114838),
        float3(.064901,.117696,.097189),float3(.063761,.086895,.123646),
        float3(.084817,.111994,.166380),float3(.097489,.154120,.091064),
        float3(.106152,.131144,.195191),float3(.097721,.110188,.187229),
        float3(.133516,.138278,.148582),float3(.070006,.243332,.235792),
        float3(.196766,.142899,.214696),float3(.047281,.315338,.321970),
        float3(.204675,.390010,.302066),float3(.080955,.314821,.661491)};
    return colors[min(i,15u)];
}
