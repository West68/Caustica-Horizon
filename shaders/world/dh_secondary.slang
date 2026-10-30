// View-dependent ray intersections against DH's existing raster depth, NOT a full far-world BVH.
// No new hardware rays, images, passes or acceleration structures. Missing/offscreen data stays a miss.
bool dhClipPlane(float a,float b,inout float lo,inout float hi) {
    if(abs(b)<1e-12)return a>=0.0;
    float t=-a/b;
    if(b>0.0)lo=max(lo,t);else hi=min(hi,t);
    return lo<=hi;
}
bool dhDepthDifference(float3 ndc,out float difference,out float depth) {
    float2 uv=ndc.xy*.5+.5;
    difference=0;depth=0;
    if(any(uv<0.0)||any(uv>=1.0))return false;
    depth=dhInputTextures[worldPush.dhTextures.y].SampleLevel(uv,0).r;
    float clearDepth=worldPush.dhTextures.w!=0?0.0:1.0;
    if(!isfinite(depth)||abs(depth-clearDepth)<1e-7)return false;
    float rayDepth=worldPush.dhTextures.z!=0?ndc.z*.5+.5:ndc.z;
    difference=(rayDepth-depth)*(worldPush.dhTextures.w!=0?-1.0:1.0);
    return true;
}
bool dhResolveSecondary(float3 origin,float3 direction,float3 ndc,float2 range,
        uint width,uint height,out float hitT,out float3 color,out float alpha) {
    hitT=-1;color=float3(0);alpha=0;
    float difference,depth;
    if(!dhDepthDifference(ndc,difference,depth))return false;
    float2 uv=ndc.xy*.5+.5;
    float3 scene=dhUnproject(uv,depth);
    if(!all(isfinite(scene)))return false;
    float t=dot(scene-origin,direction);
    // Reject silhouette/depth-discontinuity crossings. Thickness follows two raster texels,
    // not a huge fixed world slab that would invent reflections through foreground objects.
    float thickness=max(.1,2.0*length(scene)/float(min(width,height)));
    if(t<range.x || t>range.y || length(scene-(origin+direction*t))>thickness)return false;
    float4 texel=dhInputTextures[worldPush.dhTextures.x].SampleLevel(uv,0);
    alpha=saturate(texel.a);
    if(alpha<1e-4)return false;
    color=pow(max(texel.rgb/alpha,0.0),float3(2.2));
    hitT=t;return true;
}
bool dhSecondarySurface(float3 worldOrigin,float3 direction,float maxDistance,
        out float hitT,out float3 color,out float alpha) {
    hitT=-1;color=float3(0);alpha=0;
    if((worldPush.flags & 4096u)==0u || worldPush.dhTextures.x==0 || worldPush.dhTextures.y==0 || maxDistance<=.03)return false;
    float3 origin=worldOrigin-worldPush.camOffset;
    float4 a=mul(worldPush.dhViewProjection,float4(origin,1));
    float4 b=mul(worldPush.dhViewProjection,float4(origin+direction*maxDistance,1))-a;
    float lo=.03/maxDistance,hi=1.0;
    // Homogeneous clipping is essential: a reflected ray can start behind the camera/near plane.
    if(!dhClipPlane(a.w-1e-4,b.w,lo,hi)
       ||!dhClipPlane(a.w+a.x,b.w+b.x,lo,hi)||!dhClipPlane(a.w-a.x,b.w-b.x,lo,hi)
       ||!dhClipPlane(a.w+a.y,b.w+b.y,lo,hi)||!dhClipPlane(a.w-a.y,b.w-b.y,lo,hi)
       ||!dhClipPlane(worldPush.dhTextures.z!=0?a.w+a.z:a.z,worldPush.dhTextures.z!=0?b.w+b.z:b.z,lo,hi)
       ||!dhClipPlane(a.w-a.z,b.w-b.z,lo,hi))return false;
    float4 start=a+b*lo,end=a+b*hi;
    float3 from=start.xyz/start.w,to=end.xyz/end.w;
    float2 range=float2(lo,hi)*maxDistance;
    uint width,height;dhInputTextures[worldPush.dhTextures.y].GetDimensions(width,height);
    float2 pixelSpan=(to.xy-from.xy)*.5*float2(width,height);
    if(dot(pixelSpan,pixelSpan)<1.0)
        return dhResolveSecondary(origin,direction,(from+to)*.5,range,width,height,hitT,color,alpha);
    float previous=0,previousDifference=0;bool previousValid=false;
    // Bounded raster-depth work; preserve every original PT sample and bounce.
    [loop] for(uint i=0;i<=12;i++) {
        float s=float(i)/12.0,difference,depth;
        bool valid=dhDepthDifference(lerp(from,to,s),difference,depth);
        if(valid && previousValid && previousDifference<=0 && difference>=0) {
            float left=previous,right=s,dl=previousDifference,dr=difference;
            [unroll] for(uint refine=0;refine<3;refine++) {
                float f=abs(dr-dl)>1e-12?saturate(-dl/(dr-dl)):.5;
                float candidate=lerp(left,right,f),d,z;
                if(!dhDepthDifference(lerp(from,to,candidate),d,z))break;
                if(d<=0){left=candidate;dl=d;}else{right=candidate;dr=d;}
            }
            float f=abs(dr-dl)>1e-12?saturate(-dl/(dr-dl)):.5;
            // A rejected silhouette is a miss, not permission to search behind it. This also
            // caps refinement to ONE candidate per ray (13 probes + 3 refinements + final depth).
            return dhResolveSecondary(origin,direction,lerp(from,to,lerp(left,right,f)),range,width,height,hitT,color,alpha);
        }
        previous=s;previousDifference=difference;previousValid=valid;
    }
    return false;
}
