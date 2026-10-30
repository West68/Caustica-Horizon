// Coverage rejection and dielectric shadow transmission shared by both DH representations.
void dhVisibility(inout float4 shadowVis,EntityGeom g,Prim epr){
            WorldPush w=ConstPtr<WorldPush>(pc.worldPushAddr)[0];
            float3 hit=WorldRayOrigin()+WorldRayDirection()*RayTCurrent();
            // Hand off only to sections actually published by the fine RT renderer. A camera-radius
            // guess either punches holes in unloaded water or leaves two water interfaces overlapping.
            uint64_t coverage=uint64_t(asuint(g.rigidDisp.x))|(uint64_t(asuint(g.rigidDisp.y))<<32);
            if(coverage!=0){
                ConstPtr<int> h=ConstPtr<int>(coverage);
                float3 inside=hit-epr.normal.xyz*.002;
                int3 cell=int3(floor((inside-float3(h[0],h[1],h[2]))/16.0));
                if(all(cell>=0)&&cell.x<h[3]&&cell.y<h[4]&&cell.z<h[5]){
                    uint word=uint((cell.x+cell.z*h[3])*h[6]+(cell.y>>5));
                    if((ConstPtr<uint>(coverage+32)[word]&(1u<<uint(cell.y&31)))!=0u){IgnoreHit();return;}
                }
            }
            bool shadow=(RayFlags()&RAY_FLAG_SKIP_CLOSEST_HIT_SHADER)!=0u;
            if(shadow && (w.flags&8192u)!=0u && g.dispAddr!=0) {
                Ptr<uint,Access.ReadWrite,AddressSpace.Device,Std430DataLayout> counts=Ptr<uint,Access.ReadWrite,AddressSpace.Device,Std430DataLayout>(g.dispAddr);
                InterlockedAdd(counts[3],1u);
            }
            if(shadow&&(epr.flags&(4096u|8192u))!=0u) {
                shadowVis.rgb*=lerp(float3(1),epr.tint.rgb,(epr.flags&4096u)!=0u?.5:.2);
                if((epr.flags&4096u)!=0u)shadowVis.a=shadowVis.a<0?RayTCurrent():min(shadowVis.a,RayTCurrent());
                IgnoreHit();
            }

}
