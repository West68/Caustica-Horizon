// A glass texture authors one slab tint. Two geometric boundaries must not apply it twice.
// Stained glass is a coloured filter: its hue passes almost whole while other wavelengths are absorbed.
// Lerping the raw linear texel by alpha left red glass at about 1.5:1 red-to-cyan, so sunlight and lamps
// behind a pane barely changed colour. Keep the hue at full chroma and let darkness come from the texel's
// brightness (black glass still blocks, clear glass stays neutral); alpha sets how much of it applies.
public float3 glassSlabTint(float3 linearColor,float alpha) {
    float3 c=saturate(linearColor);
    float peak=max(max(c.r,c.g),max(c.b,1.0e-4));
    float3 filter=c/peak*pow(peak,0.35);
    return lerp(float3(1.0),filter,sqrt(saturate(alpha)));
}
public float3 glassBoundaryTransmission(float3 slabTint,float transmission) {
    return sqrt(saturate(slabTint)*saturate(transmission));
}
// Straight shadow traversal cannot generate focusing caustics. Use the same color filtering
// and normal-incidence Fresnel loss instead of unrelated fixed 0.15 extinction per glass face.
public float3 glassShadowBoundary(float3 slabTint,float ior,float transmission) {
    float index=max(ior,1.0e-3);
    float f0=(index-1.0)/(index+1.0);f0*=f0;
    return glassBoundaryTransmission(slabTint,transmission)*(1.0-f0);
}
