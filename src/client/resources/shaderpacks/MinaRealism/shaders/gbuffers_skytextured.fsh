#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

uniform sampler2D gtexture;
uniform int renderStage;

in vec2 texcoord;
in vec4 vertexColor;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
	// The procedural sun and moon in deferred1 replace the vanilla sprites.
	if (renderStage == MC_RENDER_STAGE_SUN || renderStage == MC_RENDER_STAGE_MOON) discard;
	// The End sky and resource pack skyboxes are kept.
	vec4 color = texture(gtexture, texcoord) * vertexColor;
	outColor = vec4(srgbToLinear(color.rgb), color.a);
}
