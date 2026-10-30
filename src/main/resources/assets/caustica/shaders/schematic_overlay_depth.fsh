#version 450
#moj_import <minecraft:dynamictransforms.glsl>
// Litematica boxes, lines and verifier overlays under RT: the composite layer has no world depth
// attachment, so test against the RT guide depth here, exactly like the schematic blocks do.
layout(std140) uniform CausticaSchematic {
    uvec4 CeIndex;
    vec4 CeLight;
    vec4 CeControls;
    vec4 CeRadiance;
    vec4 CeOptions;
};
uniform sampler2D CeDepth;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    float worldDepth=texture(CeDepth,gl_FragCoord.xy/CeControls.zw).a; // first visible surface
    // Same reversed-Z convention and tolerance as litematica_pbr.fsh.
    if(worldDepth>gl_FragCoord.z+max(1e-6,abs(gl_FragCoord.z)*0.006))discard; // wide enough for faces lying on a block surface under the per-frame RT jitter
    vec4 color=vertexColor*ColorModulator;
    if(color.a==0.0)discard;
    fragColor=color;
}
