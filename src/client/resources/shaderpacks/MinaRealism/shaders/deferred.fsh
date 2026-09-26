#version 330 compatibility
#define SHADOW_SAMPLING
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/raytrace.glsl"
#include "/lib/lighting.glsl"

// Pass 1 of the deferred stage, before water and particles are drawn:
//   colortex6  volumetric clouds (in-scattered light, transmittance)
//   colortex7  screen-space traced indirect light (bounce irradiance, sky visibility)
// Both are noisy on purpose; deferred1 filters them.

/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA8;
const int colortex2Format = RGBA16;
const int colortex3Format = RGBA8;
const int colortex4Format = RGBA16F;
const int colortex5Format = RG32F;
const int colortex6Format = RGBA16F;
const int colortex7Format = RGBA16F;
const int colortex8Format = RGBA16F;
const int colortex9Format = RGBA16F;
const int colortex10Format = RGBA16F;
const int shadowcolor1Format = RG32F;
*/
const vec4 colortex0ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex6ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 colortex7ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 colortex8ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const bool colortex5Clear = false;
const bool colortex9Clear = false; // deferred1 writes every pixel.
const bool colortex10Clear = false; // TAA history, kept across frames.

uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D depthtex0;

in vec2 texcoord;

/* RENDERTARGETS: 6,7 */
layout(location = 0) out vec4 outClouds;
layout(location = 1) out vec4 outIndirect;

// Light leaving a surface found by a ray: sun, sky and torches, one bounce.
vec3 hitRadiance(vec2 uv, vec3 hitPlayer, vec3 rayDir, vec3 sunDir, vec3 lightDir) {
	vec3 albedo = srgbToLinear(texture(colortex1, uv).rgb);
	vec4 data = texture(colortex2, uv);
	float skyLight = texture(colortex3, uv).b;
	vec3 normal = decodeNormal(data.xy);
	// Rays arriving at the back of a surface see nothing lit.
	if (dot(normal, rayDir) > 0.0) return vec3(0.0);
	vec3 irradiance = blockLightIrradiance(data.z, hitPlayer + cameraPosition) + ambientIrradiance(normal, sunDir, skyLight) * 0.5;
	float NdotL = dot(normal, lightDir);
	if (hasSkylight && NdotL > 0.0) {
		float visibility = shadowVisibility(hitPlayer + normal * 0.1) * smoothstep(0.02, 0.2, skyLight);
		irradiance += directIlluminance(sunDir) * NdotL * visibility * cloudShadow(hitPlayer, lightDir);
	}
	// Glowing blocks are left out: their light already reaches the pixel through
	// vanilla block light, and single hits on them are what makes bright specks.
	return albedo / PI * irradiance;
}

vec4 traceIndirect(vec3 viewPos, vec3 normal, float dither) {
	vec3 sunDir = worldSunDir();
	vec3 lightDir = worldLightDir();
	vec3 viewNormal = mat3(gbufferModelView) * normal;
	vec3 origin = viewPos + viewNormal * (0.02 + length(viewPos) * 0.002);
	vec2 noise = vec2(dither, hash13(vec3(gl_FragCoord.xy, 7.0)));
	vec3 bounce = vec3(0.0);
	float visibility = 0.0;
	for (int i = 0; i < GI_RAYS; i++) {
		// R2 low-discrepancy offsets between rays of the same pixel.
		vec2 xi = fract(noise + vec2(0.7548777, 0.5698403) * float(i));
		// Rays skimming along the surface mostly hit the same surface one pixel away.
		xi.y *= 0.92;
		vec3 rayDir = cosineHemisphere(normal, xi);
		vec4 hit = traceScreen(depthtex0, origin, mat3(gbufferModelView) * rayDir, GI_DISTANCE, GI_STEPS, fract(dither + 0.618 * float(i)), 0.5);
		if (hit.w > 0.5 && texture(colortex1, hit.xy).a > 0.5) {
			vec3 hitView = screenToView(hit.xy, hit.z);
			// Close hits block the sky completely, distant ones only partly.
			visibility += clamp(distance(hitView, viewPos) / GI_DISTANCE, 0.0, 1.0);
			bounce += hitRadiance(hit.xy, viewToPlayer(hitView), rayDir, sunDir, lightDir);
		} else {
			visibility += 1.0;
		}
	}
	// Cosine-weighted estimate of irradiance: E = pi * mean(L).
	return vec4(bounce * (PI / float(GI_RAYS)), visibility / float(GI_RAYS));
}

void main() {
	float depth = texture(depthtex0, texcoord).r;
	vec3 viewPos = screenToView(texcoord, depth);
	vec3 playerPos = viewToPlayer(viewPos);
	float dither = interleavedGradientNoise(gl_FragCoord.xy);

	outClouds = vec4(0.0, 0.0, 0.0, 1.0);
#ifdef VOLUMETRIC_CLOUDS
	if (hasSkylight) {
		float dist = length(playerPos);
		outClouds = marchClouds(playerPos / dist, depth >= 1.0 ? 1e6 : dist, dither, worldSunDir());
		outClouds = vec4(sanitizeColor(outClouds.rgb), clamp(outClouds.a, 0.0, 1.0));
	}
#endif

	outIndirect = vec4(0.0, 0.0, 0.0, 1.0);
#ifdef PSEUDO_RT
	if (depth < 1.0 && texture(colortex1, texcoord).a > 0.5) {
		vec3 normal = decodeNormal(texture(colortex2, texcoord).xy);
		outIndirect = traceIndirect(viewPos, normal, dither);
		outIndirect = vec4(sanitizeColor(outIndirect.rgb), clamp(outIndirect.a, 0.0, 1.0));
	}
#endif
}
