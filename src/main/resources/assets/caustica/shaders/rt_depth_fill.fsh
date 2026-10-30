#version 450
// Writes the RT primary depth into the transparent overlay's depth buffer, so any raster draw from a
// mod that RT does not capture is occluded by the traced world with ordinary depth testing.
// Reversed Z: the 0.6% bias moves the surface slightly away, so decals drawn on a block face survive.
uniform sampler2D CeDepth;
in vec2 texCoord;
void main() {
    gl_FragDepth = max(texture(CeDepth, texCoord).a, 0.0) * 0.994; // alpha: first visible surface, glass included
}
