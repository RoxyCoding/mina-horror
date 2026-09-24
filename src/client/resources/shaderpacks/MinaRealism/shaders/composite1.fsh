#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/sky.glsl"

// Reflections on water and glass, aerial perspective, underwater absorption
// and the blurred volumetric light.

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D colortex7;
uniform sampler2D depthtex0;

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

// Depth-aware 3x3 blur so light shafts do not bleed across silhouettes.
vec3 blurredScatter(float centerZ) {
	vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
	vec3 sum = vec3(0.0);
	float weightSum = 0.0;
	for (int x = -1; x <= 1; x++) {
		for (int y = -1; y <= 1; y++) {
			vec2 uv = texcoord + vec2(x, y) * texel;
			float z = linearDepth(texture(depthtex0, uv).r);
			float weight = exp(-abs(z - centerZ) / max(centerZ * 0.05, 0.1));
			sum += texture(colortex4, uv).rgb * weight;
			weightSum += weight;
		}
	}
	return sum / weightSum;
}

// Sky seen in a reflection. The sun, moon and stars are left out: on waves they
// only alias into white specks, so sunGlint draws the sun or moon as a smooth highlight.
vec3 reflectedSky(vec3 dir, vec3 sunDir) {
	if (!hasSkylight) return srgbToLinear(fogColor) * 0.5;
	vec3 sky = skyRadiance(dir, sunDir);
#ifdef VOLUMETRIC_CLOUDS
	vec4 clouds = cheapClouds(dir, sunDir);
	sky = sky * clouds.a + clouds.rgb;
#endif
	return sky;
}

// GGX highlight of the sun or moon; rougher far away where waves are sub-pixel.
vec3 sunGlint(vec3 normal, vec3 viewDir, float f0, float roughness, vec3 playerPos) {
	if (!hasSkylight) return vec3(0.0);
	vec3 lightDir = worldLightDir();
	vec3 halfway = normalize(lightDir - viewDir + vec3(0.0, 1e-4, 0.0));
	float NdotL = max(dot(normal, lightDir), 0.0);
	float NdotV = max(dot(normal, -viewDir), 0.05);
	float NdotH = max(dot(normal, halfway), 0.0);
	float a2 = pow(roughness, 4.0);
	float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
	float D = a2 / (PI * d * d);
	float k = roughness * roughness * 0.5;
	float G = NdotL / (NdotL * (1.0 - k) + k) * NdotV / (NdotV * (1.0 - k) + k);
	float F = f0 + (1.0 - f0) * pow(1.0 - max(dot(-viewDir, halfway), 0.0), 5.0);
	return directIlluminance(worldSunDir()) * cloudShadow(playerPos, lightDir) * (D * G * F / (4.0 * NdotV));
}

// Screen-space hits from composite, blurred over neighbouring pixels at a similar
// depth. Neighbours that do not reflect add no confidence, softening puddle rims.
vec4 filteredReflection(float centerZ) {
	vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
	vec4 sum = vec4(0.0);
	float weightSum = 0.0;
	for (int x = -2; x <= 2; x++) {
		for (int y = -2; y <= 2; y++) {
			vec2 uv = texcoord + vec2(x, y) * texel;
			float z = linearDepth(texture(depthtex0, uv).r);
			float weight = exp(-abs(z - centerZ) / max(centerZ * 0.03, 0.05)) * exp(-float(x * x + y * y) * 0.3);
			sum += texture(colortex7, uv) * weight;
			weightSum += weight;
		}
	}
	if (weightSum < 1e-4) return vec4(0.0);
	// rgb is stored premultiplied by the hit confidence in a.
	return vec4(sum.rgb / max(sum.a, 1e-4), sum.a / weightSum);
}

// amount is 1 on water and glass and the puddle coverage on wet ground.
vec3 applyReflection(vec3 color, vec3 viewPos, vec3 playerPos, int material, vec3 sunDir, vec3 normal, float amount) {
	float skyLight = texture(colortex3, texcoord).b;
	vec3 viewDir = normalize(playerPos);
	vec3 reflected = reflect(viewDir, normal);
	// Wave normals can tip a reflection below the horizon; keep it on the sky side.
	if (normal.y > 0.5) reflected.y = abs(reflected.y);

	float f0 = material == MAT_GLASS ? 0.04 : 0.02;
	float cosTheta = clamp(dot(-viewDir, normal), 0.0, 1.0);
	float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);

	float skyVisible = skyLight * skyLight;
	vec3 reflection = reflectedSky(reflected, sunDir) * skyVisible;
#ifdef PSEUDO_RT
	vec4 traced = filteredReflection(-viewPos.z);
	reflection = mix(reflection, traced.rgb, traced.a);
#endif
	float roughness = mix(0.08, 0.25, smoothstep(8.0, 96.0, length(playerPos)));
	return mix(color, reflection, fresnel * amount) + sunGlint(normal, viewDir, f0, roughness, playerPos) * skyVisible * amount;
}

void main() {
	vec3 color = texture(colortex0, texcoord).rgb;
	float depth = texture(depthtex0, texcoord).r;
	vec3 viewPos = screenToView(texcoord, depth);
	vec3 playerPos = viewToPlayer(viewPos);
	float dist = length(playerPos);
	vec3 dir = playerPos / max(dist, 1e-4);
	vec3 sunDir = worldSunDir();

	vec4 surface = texture(colortex3, texcoord);
	int material = decodeMaterial(surface.g);
	// The hand is flagged as prelit; it hides the surface behind it.
	if (depth < 1.0 && isEyeInWater == 0 && !isPrelit(surface.g)) {
		vec3 normal = decodeNormal(texture(colortex2, texcoord).xy);
		float amount = material == MAT_WATER || material == MAT_GLASS ? 1.0
			: gbufferPuddle(texture(colortex1, texcoord), surface, playerPos + cameraPosition, normal);
		if (amount > 0.0) color = applyReflection(color, viewPos, playerPos, material, sunDir, normal, amount);
	}

	if (isEyeInWater == 1) {
		// Absorption and scattering along the view: red goes first, then the murk closes in.
		float pathLength = depth >= 1.0 ? far : dist;
		vec3 transmittance = exp(-(WATER_ABSORPTION + WATER_SCATTERING) * pathLength);
		color = color * transmittance + underwaterMurk(eyeWaterDepth()) * (1.0 - transmittance);
	} else if (isEyeInWater > 1) {
		// Lava or powder snow.
		color = mix(srgbToLinear(fogColor) * 2.0, color, exp(-dist * 2.0));
	} else if (depth < 1.0) {
		vec3 haze = hasSkylight ? skyRadiance(dir, sunDir) : srgbToLinear(fogColor) * 0.5;
		float density = hasSkylight ? 0.0012 * FOG_DENSITY * (1.0 + rainStrength * 5.0) : 0.02 * FOG_DENSITY;
		float fog = 1.0 - exp(-dist * density);
		// Hide the edge of loaded chunks in the sky.
		fog = max(fog, smoothstep(far * 0.75, far, length(playerPos.xz)));
		color = mix(color, haze, fog);

		if (hasSkylight) {
			// Ground mist: cool air pools in low ground from night until shortly after sunrise.
			float mistStrength = 1.0 - smoothstep(0.0, 0.3, sunDir.y);
			if (mistStrength > 0.0) {
				const float MIST_BASE = 63.0;
				const float MIST_HEIGHT = 6.0;
				float y0 = cameraPosition.y - MIST_BASE;
				float y1 = y0 + playerPos.y;
				float e0 = exp(-max(y0, 0.0) / MIST_HEIGHT);
				float e1 = exp(-max(y1, 0.0) / MIST_HEIGHT);
				// Mean density along the ray through an exponential height profile.
				float meanDensity = abs(y1 - y0) > 0.05 ? MIST_HEIGHT * abs(e0 - e1) / abs(y1 - y0) : e0;
				float mist = 1.0 - exp(-0.03 * FOG_DENSITY * mistStrength * meanDensity * dist);
				vec3 mistColor = vec3(luminance(skyIrradiance(vec3(0.0, 1.0, 0.0), sunDir))) / PI * 0.9;
				color = mix(color, mistColor, mist);
			}
		}
	}

	color += blurredScatter(linearDepth(depth));

	float blind = max(blindness, darknessFactor);
	color *= mix(1.0, exp(-dist * 0.4), blind);

	outColor = vec4(sanitizeColor(color), 1.0);
}
