// ITRP sharp mode: intersect piecewise-constant height texels with a bounded 2D DDA.
// It deliberately does not reuse the bilinear/smoothed height field of smooth mode.
struct SharpHeightHit { float2 uv; float depth; float2 sideNormal; };
float sharpHeight(MaterialHeader h, float2 tileSize, int2 cell) {
    float height=heightTexel(h,tileSize,cell);
    return height+saturate(1.0-height*1e20); // ITRP zero-height sentinel
}
SharpHeightHit traceSharpHeight(MaterialHeader h,float2 start,float2 rayUv,float2 tileSize,uint steps) {
    SharpHeightHit hit;hit.uv=start;hit.depth=0;hit.sideNormal=float2(0);
    float2 grid=frac(start)*tileSize;
    int2 cell=int2(floor(grid));
    float height=sharpHeight(h,tileSize,cell);
    if(height>=1.0-1e-6)return hit;
    float2 velocity=-rayUv*tileSize;
    int2 direction=int2(velocity.x<0?-1:1,velocity.y<0?-1:1);
    float2 delta=float2(abs(velocity.x)>1e-12?abs(1.0/velocity.x):1e30,
                       abs(velocity.y)>1e-12?abs(1.0/velocity.y):1e30);
    float2 boundary=float2(direction.x>0?float(cell.x+1)-grid.x:grid.x-float(cell.x),
                           direction.y>0?float(cell.y+1)-grid.y:grid.y-float(cell.y))*delta;
    float entered=0;
    for(uint i=0;i<steps;i++) {
        float next=min(1.0,min(boundary.x,boundary.y));
        float top=1.0-height;
        if(top>=entered-1e-6 && top<=next) {hit.depth=max(entered,top);break;}
        if(next>=1.0) {hit.depth=1.0;break;}
        bool crossX=boundary.x<=boundary.y;
        bool crossY=boundary.y<=boundary.x;
        if(crossX){cell.x+=direction.x;boundary.x+=delta.x;}
        if(crossY){cell.y+=direction.y;boundary.y+=delta.y;}
        entered=next;hit.depth=entered;
        height=sharpHeight(h,tileSize,cell);
        if(height>1.0-entered) {
            hit.sideNormal=float2(crossX?-float(direction.x):0,crossY?-float(direction.y):0);
            break;
        }
    }
    int2 size=int2(tileSize);cell=((cell%size)+size)%size;
    hit.uv=(float2(cell)+0.5)/tileSize; // original sharp mode resolves the selected texel centre
    return hit;
}

// Both parallax modes shadow the height field they actually intersect. Sharp mode must not
// shadow a smoothed field: narrow steps and walls belong to the piecewise-constant texels.
float reliefHeightVisibility(MaterialHeader h, HeightHit hit, float2 size, float2 lightUv,
        uint steps, uint mode) {
    // Four fixed samples skipped narrow height ridges on high-resolution packs. Follow the
    // projected texel span with a bounded texture-only search; never add hardware rays.
    float2 travel=abs(lightUv)*hit.depth*size;
    steps=max(steps,uint(min(16.0,ceil(max(travel.x,travel.y)))));
    if (mode != 1u) return heightVisibility(h, hit, size, lightUv, steps);
    float visible=1.0;
    for (uint i=1u; i<=steps; i++) {
        float rise=hit.depth*float(i)/float(steps);
        float obstacle=sharpHeight(h,size,int2(floor(frac(hit.uv+lightUv*rise)*size)));
        visible=min(visible,saturate((1.0-hit.depth+rise-obstacle)*100.0+0.5));
        if (visible<0.003) break;
    }
    return visible;
}
