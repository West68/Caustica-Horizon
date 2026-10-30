module dh_geometry;
import world_common;
// Six scalar floats are deliberate: float3 would pad maxX to offset 16 under std430.
public struct DhPackedQuad {
    public float minX; public float minY; public float minZ;
    public float maxX; public float maxY; public float maxZ;
    public uint color; public uint meta;
};
public Prim dhPrimitive(DhPackedQuad q){
    Prim p=(Prim)0;
    uint d=(q.meta>>16)&7u;
    p.normal=float4(d==4u?-1.0:d==5u?1.0:0.0,d==0u?-1.0:d==1u?1.0:0.0,d==2u?-1.0:d==3u?1.0:0.0,0);
    p.tint=float4(float3((q.color>>16)&255u,(q.color>>8)&255u,q.color&255u)/255.0,0);
    // Bits 23-26: block light (0-15). Bits 21-22: DhLodCapture.materialClass (1 light source -> 16384, 2 metal -> 32768).
    uint materialClass=(q.meta>>21)&3u;
    p.flags=2048u|((q.meta&(1u<<19))!=0u?4096u:0u)|((q.meta&(1u<<20))!=0u?8192u:0u)
           |(materialClass==1u?16384u:0u)|(materialClass==2u?32768u:0u)
           |(((q.meta>>23)&15u)<<16); // bits 16-19: DH block light level of the face
    p.aux0=d;p.aux1=q.meta&65535u;
    return p;
}
