// Shared vertex stage for every forward-lit gbuffers program.
// Programs select behaviour with GB_* defines before including this file.

#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#ifdef GB_CHUNK
#include "/lib/wind.glsl"
#endif

out vec2 texcoord;
out vec2 lmcoord;
out vec4 vertexColor;
out vec3 viewPos;
out vec3 viewNormal;
flat out int blockId;
#ifdef GB_CHUNK
// The quad's rectangle in the texture atlas (min xy, max zw). Constant over
// the quad: every corner is the same distance from the middle.
out vec4 tileRect;
#endif

#ifdef GB_CHUNK
in vec4 mc_Entity;
in vec2 mc_midTexCoord;
#endif

void main() {
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	vertexColor = gl_Color;
	viewPos = (gl_ModelViewMatrix * gl_Vertex).xyz;
	// Left unnormalized: particles and lines may have no normal at all.
	viewNormal = gl_NormalMatrix * gl_Normal;
#ifdef GB_CHUNK
	blockId = int(mc_Entity.x + 0.5);
	vec2 halfSize = abs(texcoord - mc_midTexCoord);
	tileRect = vec4(mc_midTexCoord - halfSize, mc_midTexCoord + halfSize);
#else
	blockId = 0;
#endif
	gl_Position = ftransform();
#if defined GB_CHUNK && defined WAVING_PLANTS
	// Other geometry keeps ftransform, so overlays drawn on it cannot z-fight.
	if (isFoliageId(blockId)) {
		vec3 playerPos = mat3(gbufferModelViewInverse) * viewPos + gbufferModelViewInverse[3].xyz;
		playerPos += foliageWind(blockId, playerPos + cameraPosition, texcoord.y < mc_midTexCoord.y);
		viewPos = mat3(gbufferModelView) * playerPos + gbufferModelView[3].xyz;
		gl_Position = gl_ProjectionMatrix * vec4(viewPos, 1.0);
	}
#endif
#ifdef TAA
	gl_Position.xy += taaJitter() * 2.0 / vec2(viewWidth, viewHeight) * gl_Position.w;
#endif
}
