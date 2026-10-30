// Independently implemented Mode-3-style height reconstruction. No original shader assets are bundled.
// Four bilinear reads at integer grid corners each produce a 2x2 box mean. Interpolating those
// means is the 3x3 kernel, including zero-box sentinel handling. Tile seams use wrapped texel reads.
struct HeightPatch { float height; float2 gradient; };
float heightTexel(MaterialHeader h, float2 tileSize, int2 pixel) {
    int2 size=int2(tileSize);
    float2 local=(float2(((pixel % size)+size)%size)+0.5)/tileSize;
    return samplePageNormalAo(h.texturePage, h.materialUv.xy + local * h.materialUv.zw, 0.0).a;
}
HeightPatch smoothHeight(MaterialHeader h, float2 local, float2 tileSize) {
    float2 grid = frac(local) * tileSize;
    int2 cell = int2(floor(grid));
    float2 f = frac(grid);
    float4 box;
    if (all(cell > int2(0,0)) && all(cell < int2(tileSize)-1)) {
        float2 base = h.materialUv.xy + float2(cell) / tileSize * h.materialUv.zw;
        float2 px = h.materialUv.zw / tileSize;
        // The four windows end at cell, cell+(1,0), cell+(0,1) and cell+(1,1), so their centres sit half
        // a texel off those corners. A bilinear sample taken exactly there returns the 2x2 box mean, so
        // these four reads reproduce what the original assembles from nine texel fetches. Sampling the
        // corners themselves instead collapses this to plain bilinear - the cheaper mode's field - and
        // the smoothing that defines the quality mode never runs.
        box = float4(samplePageNormalAo(h.texturePage, base + px * float2(-0.5, -0.5), 0).a,
                samplePageNormalAo(h.texturePage, base + px * float2(0.5, -0.5), 0).a,
                samplePageNormalAo(h.texturePage, base + px * float2(-0.5, 0.5), 0).a,
                samplePageNormalAo(h.texturePage, base + px * float2(0.5, 0.5), 0).a);
    } else {
        float a=heightTexel(h,tileSize,cell+int2(-1,-1));
        float b=heightTexel(h,tileSize,cell+int2( 0,-1));
        float c=heightTexel(h,tileSize,cell+int2( 1,-1));
        float d=heightTexel(h,tileSize,cell+int2(-1, 0));
        float e=heightTexel(h,tileSize,cell);
        float g=heightTexel(h,tileSize,cell+int2( 1, 0));
        float i=heightTexel(h,tileSize,cell+int2(-1, 1));
        float j=heightTexel(h,tileSize,cell+int2( 0, 1));
        float k=heightTexel(h,tileSize,cell+int2( 1, 1));
        box=0.25*float4(a+b+d+e,b+c+e+g,d+e+i+j,e+g+j+k);
    }
    box += saturate(1.0-box*1e20); // original zero-height *box* sentinel, not per-texel replacement
    HeightPatch r;
    r.height=lerp(lerp(box.x,box.y,f.x),lerp(box.z,box.w,f.x),f.y);
    r.gradient=float2(lerp(box.y-box.x,box.w-box.z,f.y),
            lerp(box.z-box.x,box.w-box.y,f.x))*tileSize;
    return r;
}
struct HeightHit { float2 uv; float depth; float2 gradient; };
// smoothHeight(...).height in one bilinear read: the page stores the wrapped 2x2 box means at integer
// corners, so sampling at grid+0.5 interpolates exactly the four boxes smoothHeight fetches.
float smoothHeightFast(MaterialHeader h, float2 local, float2 tileSize) {
    float2 grid = frac(local) * tileSize;
    return materialHeightBoxTex[NonUniformResourceIndex(h.texturePage)]
            .SampleLevel(h.materialUv.xy + (grid + 0.5) / tileSize * h.materialUv.zw, 0.0).r;
}
HeightHit traceSmoothHeight(MaterialHeader h, float2 start, float2 rayUv, float2 tileSize,
        uint steps, uint refinements) {
    HeightHit result; result.uv=start; result.depth=0; result.gradient=float2(0,0);
    float initialHeight=smoothHeightFast(h,start,tileSize);
    if (initialHeight >= 1.0-1e-6) return result;
    float lo=0.0, hi=1.0, gapLo=initialHeight-1.0, gapHi=1.0;
    // Quadratic spacing spends steps near the top surface, then covers the entire height range.
    for (uint s=1;s<=steps;s++) {
        float t=float(s)/float(steps); t*=t;
        float gap=smoothHeightFast(h,start-rayUv*t,tileSize)-(1.0-t);
        if (gap>=0.0) { hi=t; gapHi=gap; break; }
        lo=t; gapLo=gap;
    }
    for (uint r=0;r<refinements;r++) {
        float mid=(lo+hi)*0.5;
        float gap=smoothHeightFast(h,start-rayUv*mid,tileSize)-(1.0-mid);
        if (gap>=0.0) { hi=mid; gapHi=gap; } else { lo=mid; gapLo=gap; }
    }
    result.depth=lerp(lo,hi,saturate(-gapLo/max(gapHi-gapLo,1e-7)));
    result.uv=start-rayUv*result.depth;
    result.gradient=smoothHeight(h,result.uv,tileSize).gradient;
    return result;
}
float heightVisibility(MaterialHeader h, HeightHit hit, float2 tileSize, float2 lightUv, uint steps) {
    float visible=1.0;
    for(uint i=1;i<=steps;i++) {
        float rise=hit.depth*float(i)/float(steps);
        float rayHeight=1.0-hit.depth+rise;
        float obstacle=smoothHeightFast(h,hit.uv+lightUv*rise,tileSize);
        visible=min(visible,saturate((rayHeight-obstacle)*100.0+0.5));
        if (visible<0.003) break;
    }
    return visible;
}

// Relief self-occlusion for every light that is not the NEE sun. The directional march above needs a light
// direction and so can only serve the one NEE light; torches, lava, glowstone and the sky all arrive as
// emissive hits or bounced rays with no such direction available at the receiving surface. The gradient the
// height trace already produced is the slope of the relief, and a steeper slope is a wall standing in its
// own way, so folding a function of it into surface.ao reaches every one of those paths at once: surface.ao
// multiplies the albedo, and the albedo is carried through the diffuse NEE and the bounce throughput alike.
// No extra texture reads and no extra rays: the gradient is already in hand. The coefficient stays modest
// because this is an isotropic approximation and the sun's own directional shadow is applied separately.
float heightReliefOcclusion(float2 gradient, float strength) {
    float slope = length(gradient);
    return 1.0 / (1.0 + slope * 1.5 * strength);
}
