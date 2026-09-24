#version 330 compatibility
#define SHADOW_SAMPLING
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/raytrace.glsl"

// Noisy on purpose; composite1 blurs both:
//   colortex4  volumetric light, sunlight scattered by mist and occluded by the shadow map
//   colortex7  screen-space reflections on water and glass (hit colour x confidence, confidence)

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;

in vec2 texcoord;

/* RENDERTARGETS: 4,7 */
layout(location = 0) out vec4 outScatter;
layout(location = 1) out vec4 outReflection;

// Shafts under water: sunlight broken up by the waves and by terrain above,
// fading with depth below the surface.
void underwaterLight() {
	float depth = texture(depthtex0, texcoord).r;
	vec3 playerPos = viewToPlayer(screenToView(texcoord, depth));
	float rayLength = min(length(playerPos), 48.0);
	vec3 dir = normalize(playerPos);
	vec3 lightDir = worldLightDir();
	float phase = henyeyGreenstein(dot(dir, lightDir), 0.6);
	vec3 extinction = WATER_ABSORPTION + WATER_SCATTERING;
	float eyeDepth = eyeWaterDepth();

	float stepLength = rayLength / float(VL_STEPS);
	float dither = interleavedGradientNoise(gl_FragCoord.xy);
	vec3 scattered = vec3(0.0);
	for (int i = 0; i < VL_STEPS; i++) {
		float t = (float(i) + dither) * stepLength;
		vec3 samplePos = dir * t;
		float depthBelow = max(eyeDepth - samplePos.y, 0.0);
		vec3 light = exp(-WATER_DOWNWELLING * depthBelow) * waterCaustics(samplePos + cameraPosition + lightDir * (depthBelow / max(lightDir.y, 0.2)), depthBelow);
		scattered += shadowVisibility(samplePos) * light * exp(-extinction * t) * stepLength;
	}
	outScatter.rgb = directIlluminance(worldSunDir()) * WATER_SCATTERING * phase * scattered * VL_STRENGTH * 0.15;
}

void volumetricLight() {
	outScatter = vec4(0.0, 0.0, 0.0, 1.0);
#ifdef VOLUMETRIC_LIGHT
	if (!hasSkylight || isEyeInWater > 1) return;
	if (isEyeInWater == 1) {
		underwaterLight();
		return;
	}

	float depth = texture(depthtex0, texcoord).r;
	vec3 viewPos = projectAndDivide(gbufferProjectionInverse, vec3(texcoord, depth) * 2.0 - 1.0);
	vec3 playerPos = viewToPlayer(viewPos);
	float rayLength = min(length(playerPos), shadowDistance);
	vec3 dir = normalize(playerPos);

	vec3 lightDir = worldLightDir();
	float phase = mix(1.0 / (4.0 * PI), henyeyGreenstein(dot(dir, lightDir), 0.7), 0.7);
	// Thicker around sunrise and sunset, and in rain.
	float lowSun = 1.0 - smoothstep(0.0, 0.35, abs(lightDir.y));
	float density = 0.0012 * FOG_DENSITY * (1.0 + 2.0 * lowSun + 3.0 * rainStrength);

	float stepLength = rayLength / float(VL_STEPS);
	float dither = interleavedGradientNoise(gl_FragCoord.xy);
	float scattered = 0.0;
	float transmittance = 1.0;
	for (int i = 0; i < VL_STEPS; i++) {
		vec3 samplePos = dir * ((float(i) + dither) * stepLength);
		// Mist settles near sea level.
		float height = samplePos.y + cameraPosition.y;
		float localDensity = density * exp(-max(height - 62.0, 0.0) / 48.0);
		scattered += shadowVisibility(samplePos) * cloudShadow(samplePos, lightDir) * localDensity * transmittance * stepLength;
		transmittance *= exp(-localDensity * stepLength);
	}

	vec3 light = directIlluminance(worldSunDir());
	outScatter.rgb = light * phase * scattered * VL_STRENGTH * smoothstep(0.1, 0.6, eyeSkyExposure());
#endif
}

void screenSpaceReflection() {
	outReflection = vec4(0.0);
#ifdef PSEUDO_RT
	vec4 surface = texture(colortex3, texcoord);
	int material = decodeMaterial(surface.g);
	float depth = texture(depthtex0, texcoord).r;
	// The hand is lit in its gbuffers pass and flagged as such; testing that
	// rather than a near distance keeps puddles right under a low camera.
	if (depth >= 1.0 || isEyeInWater != 0 || isPrelit(surface.g)) return;
	vec3 viewPos = screenToView(texcoord, depth);

	vec3 normal = decodeNormal(texture(colortex2, texcoord).xy);
	vec3 playerPos = viewToPlayer(viewPos);
	if (material != MAT_WATER && material != MAT_GLASS
		&& gbufferPuddle(texture(colortex1, texcoord), surface, playerPos + cameraPosition, normal) <= 0.0) return;
	vec3 reflected = reflect(normalize(playerPos), normal);
	if (normal.y > 0.5) reflected.y = abs(reflected.y);
	float dither = interleavedGradientNoise(gl_FragCoord.xy);
	// Trace against opaque depth so the water surface does not hit itself.
	vec4 hit = traceScreen(depthtex1, viewPos, mat3(gbufferModelView) * reflected, far, SSR_STEPS, dither, 2.0);
	if (hit.w > 0.5) {
		float confidence = screenEdgeFade(hit.xy);
		outReflection = vec4(texture(colortex0, hit.xy).rgb * confidence, confidence);
	}
#endif
}

void main() {
	volumetricLight();
	screenSpaceReflection();
	outScatter.rgb = sanitizeColor(outScatter.rgb);
	outReflection = vec4(sanitizeColor(outReflection.rgb), clamp(outReflection.a, 0.0, 1.0));
}
