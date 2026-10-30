// Affine composition H0(H1(...x)): reflection order follows the traced mirror prefix.
// Rows avoid matrix-layout ambiguity; vectors omit translation (normals and object motion).
public struct MirrorGuideTransform {
    public float3 r0; public float3 r1; public float3 r2; public float3 offset;
};
public MirrorGuideTransform mirrorGuideIdentity() {
    MirrorGuideTransform t;
    t.r0=float3(1,0,0);t.r1=float3(0,1,0);t.r2=float3(0,0,1);t.offset=float3(0);
    return t;
}
public float3 mirrorGuideVector(MirrorGuideTransform t,float3 v) {
    return float3(dot(t.r0,v),dot(t.r1,v),dot(t.r2,v));
}
public float3 mirrorGuidePoint(MirrorGuideTransform t,float3 p) {
    return mirrorGuideVector(t,p)+t.offset;
}
public void mirrorGuideAppend(inout MirrorGuideTransform t,float3 point,float3 normal) {
    float3 cn=mirrorGuideVector(t,normal);
    t.offset+=2.0*dot(point,normal)*cn;
    t.r0-=2.0*cn.x*normal;
    t.r1-=2.0*cn.y*normal;
    t.r2-=2.0*cn.z*normal;
}

// Local virtual-image approximation for a static planar dielectric. Snell preserves tangential
// position; scaling normal depth by eta*cosI/cosT maps the refracted ray to its incident image ray.
public bool mirrorGuideAppendTransmission(inout MirrorGuideTransform t,float3 point,
        float3 normal,float eta,float cosI,float cosT) {
    float scale=eta*cosI/max(cosT,1.0e-8);
    if(!isfinite(scale) || scale<0.02 || scale>50.0)return false;
    float3 cn=mirrorGuideVector(t,normal);
    t.offset+=(1.0-scale)*dot(point,normal)*cn;
    t.r0+=(scale-1.0)*cn.x*normal;
    t.r1+=(scale-1.0)*cn.y*normal;
    t.r2+=(scale-1.0)*cn.z*normal;
    return true;
}
// Refraction is non-orthogonal: normals need inverse transpose, while displacement uses M.
// For a pure mirror prefix this reduces to the previous orthogonal normal transform.
public float3 mirrorGuideNormal(MirrorGuideTransform t,float3 n) {
    float3 c0=cross(t.r1,t.r2),c1=cross(t.r2,t.r0),c2=cross(t.r0,t.r1);
    float determinant=dot(t.r0,c0);
    if(abs(determinant)<1.0e-8)return normalize(n);
    return normalize(float3(dot(c0,n),dot(c1,n),dot(c2,n))/determinant);
}
