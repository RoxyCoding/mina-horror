#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"

in vec4 mc_Entity;

out vec2 texcoord;
out vec4 vertexColor;
flat out int blockId;

void main() {
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	vertexColor = gl_Color;
	blockId = int(mc_Entity.x + 0.5);
	vec4 clipPos = ftransform();
	clipPos.xyz = distortShadow(clipPos.xyz);
	gl_Position = clipPos;
}
