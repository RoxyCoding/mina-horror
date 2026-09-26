// Surface shading shared by the deferred pass and forward-lit programs.
// Needs common, shadow (with SHADOW_SAMPLING), atmosphere and clouds.

#include "/lib/flashlight.glsl"

const vec3 TORCH_COLOR = vec3(1.0, 0.42, 0.12); // A wood flame, about 2200 K after the eye's white balance.
const float TORCH_INTENSITY = 5.0;

// Vanilla stores light levels in [1/32, 31/32].
vec2 normalizeLightmap(vec2 lmcoord) {
	return clamp((lmcoord - 1.0 / 32.0) * (16.0 / 15.0), 0.0, 1.0);
}

// Vanilla block light drops one level per block, which gives back the distance
// to the light; real point lights then fall off with its square.
vec3 blockLightIrradiance(float blockLight, vec3 worldPos) {
	if (blockLight <= 0.0) return vec3(0.0);
	float lightDistance = (1.0 - blockLight) * 15.0;
	float falloff = 1.0 / (lightDistance * lightDistance + 1.0);
	// Fade out where vanilla light ends instead of stopping at a hard edge.
	falloff *= smoothstep(0.0, 0.15, blockLight);
	// Flames flicker; nearby areas flicker together, distant ones independently.
	vec2 cell = worldPos.xz * (0.137 / 6.0);
	float flicker = texture(noisetex, vec2(frameTimeCounter * 0.6, 0.31) + cell).g
		+ texture(noisetex, vec2(frameTimeCounter * 2.3, 0.73) + cell).g * 0.5;
	flicker = 1.0 + (flicker - 0.75) * 0.25;
	return TORCH_COLOR * falloff * flicker * TORCH_INTENSITY * TORCH_BRIGHTNESS;
}

vec3 ambientIrradiance(vec3 normal, vec3 sunDir, float skyLight) {
	if (!hasSkylight) {
		// Nether and End: light comes from the glowing haze.
		return max(srgbToLinear(fogColor) * 2.5, vec3(0.03, 0.025, 0.04)) * AMBIENT_BRIGHTNESS;
	}
	return skyIrradiance(normal, sunDir) * skyLight * skyLight;
}

vec3 emissiveRadiance(vec3 albedo) {
	// Only the bright parts of a texture (flames, lava, lamps) glow.
	float mask = smoothstep(0.45, 0.9, max(albedo.r, max(albedo.g, albedo.b)));
	return albedo * mask * 4.0 * EMISSIVE_BRIGHTNESS; // Flames are far brighter than what they light.
}

float surfaceRoughness(int material, float wet) {
	float roughness = 0.8;
	if (material == MAT_FOLIAGE) roughness = 0.45;
	else if (material == MAT_ENTITY) roughness = 0.6;
	return mix(roughness, 0.12, wet);
}

// GGX highlight of the sun or moon for a dielectric (F0 = 0.04).
vec3 sunSpecular(vec3 normal, vec3 viewDir, vec3 lightDir, float roughness) {
	vec3 halfway = normalize(lightDir - viewDir + vec3(0.0, 1e-4, 0.0));
	float NdotL = max(dot(normal, lightDir), 0.0);
	float NdotV = max(dot(normal, -viewDir), 0.05);
	float NdotH = max(dot(normal, halfway), 0.0);
	float VdotH = max(dot(-viewDir, halfway), 0.0);
	float a = roughness * roughness;
	float a2 = a * a;
	float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
	float D = a2 / (PI * d * d);
	float k = a * 0.5;
	float G = NdotL / (NdotL * (1.0 - k) + k) * NdotV / (NdotV * (1.0 - k) + k);
	float F = 0.04 + 0.96 * pow(1.0 - VdotH, 5.0);
	return vec3(D * G * F / (4.0 * NdotV));
}

// Outgoing radiance of a surface.
//   indirect.rgb  bounce light found by screen-space rays (irradiance)
//   indirect.a    fraction of the sky the rays could see (occlusion)
//   contact       screen-space contact shadow, 1 when unused
vec3 shadeSurface(vec3 albedo, vec3 playerPos, vec3 normal, vec2 light, float ao, int material,
		bool useShadowMap, float dither, vec4 indirect, float contact) {
	vec3 sunDir = worldSunDir();
	vec3 lightDir = worldLightDir();
	vec3 viewDir = normalize(playerPos);
	float skyLight = light.y;
	float wet = surfaceWetness(normal, skyLight, material, playerPos + cameraPosition);
	albedo *= mix(1.0, 0.6, wet); // Wet surfaces darken.

	vec3 direct = vec3(0.0);
	vec3 specular = vec3(0.0);
	float waterDepth = 0.0;
	if (hasSkylight) {
		float NdotL = dot(normal, lightDir);
		float diffuse = max(NdotL, 0.0);
		if (material == MAT_FOLIAGE) diffuse = 0.25 + 0.5 * abs(NdotL);
		else if (material == MAT_FLAT) diffuse = 0.5;

		if (diffuse > 0.0) {
			// Skylight is the fallback where the shadow map ends and keeps light out of caves.
			float fallback = smoothstep(0.8, 0.97, skyLight);
			vec3 visibility = vec3(fallback);
			if (useShadowMap) {
				vec3 offsetDir = material == MAT_FOLIAGE || material == MAT_FLAT ? lightDir : normal;
				visibility = sampleShadow(playerPos, offsetDir, dither, fallback, waterDepth);
			}
			if (waterDepth > 0.0) {
				// Sunlight fades with depth and is focused into caustics by the waves.
				visibility *= exp(-WATER_DOWNWELLING * waterDepth) * waterCaustics(playerPos + cameraPosition + lightDir * waterDepth, waterDepth);
			}
			visibility *= smoothstep(0.02, 0.2, skyLight) * contact * cloudShadow(playerPos, lightDir);
			vec3 sunLight = directIlluminance(sunDir) * visibility;
			direct = sunLight * diffuse;
			if (material != MAT_FLAT) specular = sunLight * sunSpecular(normal, viewDir, lightDir, surfaceRoughness(material, wet));

			if (material == MAT_FOLIAGE) {
				// Light glowing through thin leaves when looking towards the sun.
				float towardsLight = max(dot(viewDir, lightDir), 0.0);
				direct += sunLight * albedo * (pow(towardsLight, 6.0) * 1.5 + 0.15);
			}
		}
	}

	float occlusion = ao * indirect.a;
	vec3 ambient = ambientIrradiance(normal, sunDir, skyLight) * occlusion;
	// Under water, sky light is filtered by the same water column as sunlight.
	if (waterDepth > 0.0) ambient *= exp(-WATER_DOWNWELLING * waterDepth);
	vec3 bounce = indirect.rgb * ao;
	vec3 blockLight = blockLightIrradiance(light.x, playerPos + cameraPosition) * mix(occlusion, 1.0, 0.3);

	vec3 flashlight = vec3(0.0);
	for (int hand = 0; hand < 2; hand++) {
		if (!flashlightInHand(hand)) continue;
		vec3 toLamp;
		vec3 beam = flashlightLight(playerPos, hand, toLamp) * flashlightVisibility;
		float NdotL = dot(normal, toLamp);
		float diffuse = max(NdotL, 0.0);
		if (material == MAT_FOLIAGE) diffuse = 0.25 + 0.5 * abs(NdotL);
		else if (material == MAT_FLAT) diffuse = 0.5;
		flashlight += beam * diffuse;
		if (material != MAT_FLAT && NdotL > 0.0) specular += beam * sunSpecular(normal, viewDir, toLamp, surfaceRoughness(material, wet));
	}
	vec3 minimum = vec3(0.003 * NIGHT_BRIGHTNESS) * occlusion + vec3(nightVision * 0.6);

	vec3 color = albedo / PI * (direct + ambient + bounce + blockLight + flashlight + minimum) + specular;
	if (material == MAT_EMISSIVE) color += emissiveRadiance(albedo);
	return color;
}
