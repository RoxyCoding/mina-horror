#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

uniform sampler2D gtexture;
// Iris passes vanilla's cutoff for the current render type: 0.5 for mipmapped
// cutouts, where distant grass and leaves would otherwise fill their whole quad.
uniform float alphaTestRef;

in vec2 texcoord;
in vec4 vertexColor;
flat in int blockId;

/* RENDERTARGETS: 0,1 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outDepths; // r: water surface, g: nearest caster; 1 where there is none.

void main() {
	// Vertex alpha holds ambient occlusion for terrain (separateAo), so only the texture decides coverage.
	vec4 tex = texture(gtexture, texcoord);
	if (tex.a < max(alphaTestRef, isFoliageId(blockId) ? 0.5 : 0.1)) discard;
	if (blockId == ID_WATER) {
		// Water does not tint like glass; its depth is recorded and absorption computed from it.
		// The casters below are hidden from the shadow softening; they then
		// get the sharpest penumbra.
		outColor = vec4(1.0, 1.0, 1.0, 0.0);
		outDepths = vec4(gl_FragCoord.z, 1.0, 0.0, 1.0);
		return;
	}
	outColor = vec4(srgbToLinear(tex.rgb * vertexColor.rgb), tex.a);
	outDepths = vec4(1.0, gl_FragCoord.z, 0.0, 1.0);
}
