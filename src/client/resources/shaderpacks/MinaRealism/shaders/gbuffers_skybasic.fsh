#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

in vec4 vertexColor;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
	// The sky, stars, sun and moon are all drawn per pixel in deferred1.
	if (hasSkylight) discard;
	outColor = vec4(srgbToLinear(vertexColor.rgb), vertexColor.a);
}
