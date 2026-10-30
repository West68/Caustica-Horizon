import world_common;
import dh_geometry;
[[vk::push_constant]] WorldPushConstants pc;
#include "dh_visibility.slang"
[shader("anyhit")]
void main(inout float4 shadowVis,in float2 attributes){
    EntityGeom g=ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID()&IDX_MASK];
    dhVisibility(shadowVis,g,dhPrimitive(ConstPtr<DhPackedQuad>(g.primAddr)[PrimitiveIndex()]));
}
