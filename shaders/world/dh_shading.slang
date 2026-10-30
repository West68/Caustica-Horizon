// Shared DH material evaluation for exact rectangles and legacy triangles.
void shadeDh(inout Payload payload,EntityGeom g,Prim pr){
            if ((payload.flags & PAYLOAD_REFLECTION_GUIDE) != 0u) {
                payload.hitT=RayTCurrent();payload.motionPrev=float3(0);
                payload.flags=(pr.flags&4096u)!=0u?MATERIAL_WATER:(pr.flags&8192u)!=0u?MATERIAL_GLASS:MATERIAL_OPAQUE;
                return;
            }
            bool dhPrimary=(payload.flags&PAYLOAD_CAMERA_MISS)!=0u;
            // True hardware LOD hit: same path integrator and dielectric transport as near terrain.
            float3 n=normalize(mul((float3x3)ObjectToWorld3x4(),pr.normal.xyz));
            bool entering=dot(WorldRayDirection(),n)<0;
            if(!entering)n=-n;
            bool water=(pr.flags&4096u)!=0u,glass=(pr.flags&8192u)!=0u;
            payload.normal=n;payload.hitT=RayTCurrent();payload.motionPrev=float3(0);
            float3 dhColor=pr.tint.rgb;
            // DH 3.3 BlockTextureRegistry encodes RGB ratios around 128, alpha is ratio coverage.
            // Restore the real face texture instead of replacing the seabed with a flat color.
            if(!water && pr.aux1!=0u && g.reserved.x!=0u) {
                Sampler2D atlas=entityAlbedoTex[NonUniformResourceIndex(g.reserved.x)];
                float3 p=frac(ObjectRayOrigin()+ObjectRayDirection()*RayTCurrent());
                float2 faceUv=pr.aux0==0u?float2(p.x,1-p.z):pr.aux0==1u?p.xz:
                    pr.aux0==2u?float2(1-p.x,1-p.y):pr.aux0==3u?float2(p.x,1-p.y):
                    pr.aux0==4u?float2(p.z,1-p.y):float2(1-p.z,1-p.y);
                float2 cone=unpackHalf2(payload.rayCone);
                float hitWidth=max(cone.x+cone.y*max(RayTCurrent(),0.0),1.0e-5);
                float lod=clamp(log2(max(hitWidth*16.0,1.0)),0.0,4.0);
                uint atlasW,atlasH;atlas.GetDimensions(atlasW,atlasH);
                // Stay inside the tile at the selected mip; no adjacent-block bleed.
                float edge=0.5*exp2(lod);
                float2 tile=float2(pr.aux1%256u,pr.aux1/256u)*16.0;
                float2 uv=(tile+clamp(faceUv*16.0,float2(edge),float2(16.0-edge)))/float2(atlasW,atlasH);
                float4 ratio=atlas.SampleLevel(uv,lod);
                dhColor=lerp(dhColor,saturate(dhColor*ratio.rgb*2.0),ratio.a);
            }
            payload.albedo=water?dhColor:srgbToLinear(dhColor);
            payload.f0=glass?n:water?float3(.02):float3(.04);
            MaterialHeader waterHeader=ConstPtr<MaterialHeader>(pc.materialTableAddr)[g.reserved.y];
            WorldPush w=ConstPtr<WorldPush>(pc.worldPushAddr)[0];
            // DH material class (DhLodCapture): lamps and lava glow like their near counterparts. Emission is
            // gathered only by paths that hit it, as for near block lights, so it adds no light-sampling rays.
            bool emissive=(pr.flags&16384u)!=0u,metal=(pr.flags&32768u)!=0u;
            // Resource-pack material of the block this face's tile came from (DhLodMaterials): roughness, metalness
            // and light emission. Unknown tiles keep the DH material-class fallback.
            uint tileMaterial=(g.uvAddr!=0 && pr.aux1!=0u && !water && !glass)?ConstPtr<uint>(g.uvAddr)[pr.aux1&65535u]:0u;
            bool known=(tileMaterial&0x80000000u)!=0u;
            // A whole LOD face averages many texels: a floor keeps the shading stable instead of sparkling.
            float roughness=water?waterHeader.params.x:glass?.08:known?max(float(tileMaterial&255u)/255.0,.25):metal?.45:.9;
            float metalness=known?float((tileMaterial>>8)&255u)/255.0:0.0;
            float lightEmission=known?float((tileMaterial>>16)&15u)/15.0:0.0;
            // DH stores how strongly block light reaches each face; the vanilla DH renderer lights LODs with it.
            // Far streets and windows next to lamps keep that glow (vanilla lightmap curve), at a fraction of a
            // real emitter. Like any emitter it is gathered only by rays that hit it: no light-sampling rays.
            float faceLight=float((pr.flags>>16)&15u)/15.0;
            // Not in the End: DH fills its sky-less islands with full block light, which turned every far end
            // stone face into a white emitter. Real End lamps still glow through lightEmission above.
            float glow=!water && !glass && w.environment.x!=2.0 ? .15*faceLight/(4.0-3.0*faceLight) : 0.0;
            // Whether a face emits comes only from DH's per-block class (DH tests the block's own light level).
            // Tiles are shared by look-alike blocks, so the tile table may only set HOW bright an emitter is:
            // trusting it alone let a lamp's tile light up plain End stone.
            float emission=max(emissive?(lightEmission>0.0?lightEmission:1.0):0.0,glow);
            // Overworld rain darkens and smooths far tops like near terrain, without its puddle field.
            bool top=n.y>.5;
            if(!water && !glass && top && w.environment.x==0.0) {
                float wet=saturate(w.environment.y)*smoothstep(.1,.9,n.y);
                roughness=max(.02,roughness*(1.0-.8*wet));
                payload.albedo*=1.0-wet*.25;
            }
            payloadSetPacked(payload,water?MATERIAL_WATER:glass?MATERIAL_GLASS:MATERIAL_OPAQUE,
                roughness,metalness,emission,0,
                water?waterHeader.params.z:glass?1.5:1.0,water?waterHeader.params.w:glass?1.0:0.0,
                emission>0.0?EMISSION_SOURCE_UNIFORM:EMISSION_SOURCE_NONE);
            if((water||glass)&&entering)payload.flags|=PAYLOAD_WATER_ENTERING;
            if(glass)payload.flags|=PAYLOAD_STATIC_GLASS;
            if(water&&top)payload.flags|=PAYLOAD_RAIN_EXPOSED; // far open water also gets rain ripples
            // Marks a far LOD hit for the raygen (rayCone is read back only with PAYLOAD_HAS_RELIEF).
            payload.rayCone=DH_HIT_MARK;
            // Explicit test-only closest-hit marker. Never enabled by normal settings.
            if((w.flags&8192u)!=0u){
                if(g.dispAddr!=0) {
                    Ptr<uint,Access.ReadWrite,AddressSpace.Device,Std430DataLayout> counts=Ptr<uint,Access.ReadWrite,AddressSpace.Device,Std430DataLayout>(g.dispAddr);
                    InterlockedAdd(counts[0],1u);
                    if(dhPrimary)InterlockedAdd(counts[1],1u);
                    if(water)InterlockedAdd(counts[2],1u);
                }
            }

}
