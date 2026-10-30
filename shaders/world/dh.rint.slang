import world_common;
import dh_geometry;
[[vk::push_constant]] WorldPushConstants pc;
[shader("intersection")]
void main(){
    EntityGeom g=ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID()&IDX_MASK];
    DhPackedQuad q=ConstPtr<DhPackedQuad>(g.primAddr)[PrimitiveIndex()];
    float3 lo=float3(q.minX,q.minY,q.minZ),hi=float3(q.maxX,q.maxY,q.maxZ);
    uint d=(q.meta>>16)&7u;uint axis=d<2u?1u:d<4u?2u:0u;
    float3 origin=ObjectRayOrigin(),direction=ObjectRayDirection();
    if(direction[axis]==0.0)return;
    // Boxes bound rectangles, not volumes. Intersect the exact original DH face once.
    float plane=(lo[axis]+hi[axis])*.5;
    float t=(plane-origin[axis])/direction[axis];
    if(t<RayTMin()||t>RayTCurrent())return;
    float3 hit=origin+direction*t;
    uint a=(axis+1u)%3u,b=(axis+2u)%3u;
    if(hit[a]<lo[a]||hit[a]>hi[a]||hit[b]<lo[b]||hit[b]>hi[b])return;
    ReportHit(t,0u,float2(0));
}
