#version 330 compatibility
#define SHADOW_SAMPLING
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/sky.glsl"
#include "/lib/raytrace.glsl"
#include "/lib/lighting.glsl"

// Pass 2 of the deferred stage: lights the G-buffer, fills the sky with the
// procedural atmosphere, sun, moon and stars, and lays the clouds over both.
// The result is also kept in colortex9, the scene without water, for
// composite1 to refract.

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex6;
uniform sampler2D colortex5;
uniform sampler2D colortex7;
uniform sampler2D depthtex0;

in vec2 texcoord;

/* RENDERTARGETS: 0,9 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outOpaque;

// Edge-aware blur of the traced indirect light. Samples are also weighted down
// by how bright they look on screen, so rare very bright ray hits cannot turn
// into speckles.
vec4 filteredIndirect(float centerDepth, vec3 centerNormal) {
	vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
	float exposure = max(texelFetch(colortex5, ivec2(0), 0).r, 0.05);
	vec4 sum = vec4(0.0);
	float weightSum = 0.0;
	for (int x = -DENOISE_RADIUS; x <= DENOISE_RADIUS; x++) {
		for (int y = -DENOISE_RADIUS; y <= DENOISE_RADIUS; y++) {
			vec2 uv = texcoord + vec2(x, y) * texel;
			if (texture(colortex1, uv).a < 0.5) continue;
			float depth = linearDepth(texture(depthtex0, uv).r);
			vec3 normal = decodeNormal(texture(colortex2, uv).xy);
			float weight = exp(-abs(depth - centerDepth) / (centerDepth * 0.03 + 0.05));
			weight *= pow(max(dot(normal, centerNormal), 0.0), 8.0);
			weight *= exp(-float(x * x + y * y) * 0.25);
			vec4 indirect = texture(colortex7, uv);
			weight /= 1.0 + luminance(indirect.rgb) * exposure * 4.0;
			sum += indirect * weight;
			weightSum += weight;
		}
	}
	return weightSum > 1e-4 ? sum / weightSum : texture(colortex7, texcoord);
}

// Clouds are soft; a small blur hides the march noise. Sky and ground are kept apart.
vec4 filteredClouds(bool isSky) {
	vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
	vec4 sum = vec4(0.0);
	float weightSum = 0.0;
	for (int x = -1; x <= 1; x++) {
		for (int y = -1; y <= 1; y++) {
			vec2 uv = texcoord + vec2(x, y) * texel;
			if ((texture(depthtex0, uv).r >= 1.0) != isSky) continue;
			sum += texture(colortex6, uv);
			weightSum += 1.0;
		}
	}
	return weightSum > 0.0 ? sum / weightSum : texture(colortex6, texcoord);
}

// Short ray towards the light for small occluders the shadow map misses.
float contactShadow(vec3 viewPos, vec3 normal, float dither) {
#ifdef PSEUDO_RT
	float viewDistance = -viewPos.z;
	if (!hasSkylight || viewDistance > 48.0) return 1.0;
	vec3 lightView = normalize(shadowLightPosition);
	vec3 origin = viewPos + mat3(gbufferModelView) * normal * (0.01 + viewDistance * 0.002);
	vec4 hit = traceScreen(depthtex0, origin, lightView, 0.6, 8, dither, 0.1);
	return hit.w > 0.5 ? mix(0.0, 1.0, smoothstep(24.0, 48.0, viewDistance)) : 1.0;
#else
	return 1.0;
#endif
}

void main() {
	float depth = texture(depthtex0, texcoord).r;
	vec4 base = texture(colortex0, texcoord);
	vec3 color = base.rgb;
	vec3 viewPos = screenToView(texcoord, depth);
	vec3 playerPos = viewToPlayer(viewPos);
	vec3 sunDir = worldSunDir();
	bool isSky = depth >= 1.0;

	if (isSky) {
		vec3 dir = normalize(playerPos);
		// base holds the End sky or skybox textures when there is no daylight sky.
		color += hasSkylight ? fullSky(dir, sunDir) : srgbToLinear(fogColor) * 0.5;
	} else if (texture(colortex1, texcoord).a > 0.5 && !isPrelit(texture(colortex3, texcoord).g)) {
		vec3 albedo = srgbToLinear(texture(colortex1, texcoord).rgb);
		vec4 data = texture(colortex2, texcoord);
		vec4 surface = texture(colortex3, texcoord);
		vec3 normal = decodeNormal(data.xy);
		int material = decodeMaterial(surface.g);
		float ao = surface.r;
		float dither = interleavedGradientNoise(gl_FragCoord.xy);

		vec4 indirect = vec4(0.0, 0.0, 0.0, 1.0);
		float contact = 1.0;
#ifdef PSEUDO_RT
		indirect = filteredIndirect(linearDepth(depth), normal);
		ao = sqrt(ao); // Traced occlusion already darkens corners.
		if (material == MAT_DEFAULT || material == MAT_ENTITY) contact = contactShadow(viewPos, normal, dither);
#endif
		// base holds additive overlays drawn on top: spider eyes, enchantment glint.
		color = shadeSurface(albedo, playerPos, normal, vec2(data.z, surface.b), ao, material, true, dither, indirect, contact) + base.rgb;
	}

#ifdef VOLUMETRIC_CLOUDS
	vec4 clouds = filteredClouds(isSky);
	color = color * clouds.a + clouds.rgb;
#endif

	outColor = vec4(sanitizeColor(color), 1.0);
	outOpaque = outColor;
}
