#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"
#include "/lib/wind.glsl"

uniform mat4 shadowModelViewInverse;

in vec4 mc_Entity;
in vec2 mc_midTexCoord;

out vec2 texcoord;
out vec4 vertexColor;
flat out int blockId;

void main() {
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	vertexColor = gl_Color;
	blockId = int(mc_Entity.x + 0.5);
	vec4 clipPos = ftransform();
#ifdef WAVING_PLANTS
	if (isFoliageId(blockId)) {
		vec3 shadowView = (gl_ModelViewMatrix * gl_Vertex).xyz;
		vec3 playerPos = mat3(shadowModelViewInverse) * shadowView + shadowModelViewInverse[3].xyz;
		playerPos += foliageWind(blockId, playerPos + cameraPosition, texcoord.y < mc_midTexCoord.y);
		clipPos = shadowProjection * (shadowModelView * vec4(playerPos, 1.0));
	}
#endif
	clipPos.xyz = distortShadow(clipPos.xyz);
	gl_Position = clipPos;
}
