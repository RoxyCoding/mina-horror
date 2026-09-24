// Shared vertex stage for every forward-lit gbuffers program.
// Programs select behaviour with GB_* defines before including this file.

out vec2 texcoord;
out vec2 lmcoord;
out vec4 vertexColor;
out vec3 viewPos;
out vec3 viewNormal;
flat out int blockId;

#ifdef GB_CHUNK
in vec4 mc_Entity;
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
#else
	blockId = 0;
#endif
	gl_Position = ftransform();
}
