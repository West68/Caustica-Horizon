import world_common;
import dh_geometry;
[[vk::push_constant]] WorldPushConstants pc;
[[vk::binding(0,1)]] Sampler2D entityAlbedoTex[];
void payloadSetPacked(inout Payload payload,uint material,float roughness,float metalness,
        float emission,float sss,float ior,float transmission,uint emissionSource){
    payload.flags=material|(emissionSource<<PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal=packHalf2(float2(roughness,metalness));
    payload.emissionSss=packHalf2(float2(emission,sss));
    payload.iorTransmission=packHalf2(float2(ior,transmission));
}
#include "dh_shading.slang"
[shader("closesthit")]
void main(inout Payload payload,in float2 attributes){
    EntityGeom g=ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID()&IDX_MASK];
    shadeDh(payload,g,dhPrimitive(ConstPtr<DhPackedQuad>(g.primAddr)[PrimitiveIndex()]));
}
