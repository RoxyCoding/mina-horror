const int shadowMapResolution = 2048; // [1024 2048 3072 4096 6144 8192]
const float shadowDistance = 128.0; // [64.0 96.0 128.0 160.0 192.0 256.0]
const float shadowDistanceRenderMul = 1.0;
const bool shadowHardwareFiltering = true;
const float sunPathRotation = -25.0; // [-45.0 -40.0 -35.0 -30.0 -25.0 -20.0 -15.0 -10.0 -5.0 0.0]
const vec4 shadowcolor0ClearColor = vec4(1.0, 1.0, 1.0, 0.0);
const vec4 shadowcolor1ClearColor = vec4(1.0, 1.0, 1.0, 1.0); // Iris parses all four components.

// Spends more shadow texels near the player than far away.
const float SHADOW_DISTORTION = 0.85;
const float SHADOW_DEPTH_SCALE = 0.2;

float shadowDistortFactor(vec2 clipXY) {
	return length(clipXY) * SHADOW_DISTORTION + (1.0 - SHADOW_DISTORTION);
}

vec3 distortShadow(vec3 clipPos) {
	return vec3(clipPos.xy / shadowDistortFactor(clipPos.xy), clipPos.z * SHADOW_DEPTH_SCALE);
}

vec3 shadowClipPos(vec3 playerPos) {
	return (shadowProjection * (shadowModelView * vec4(playerPos, 1.0))).xyz;
}

#ifdef SHADOW_SAMPLING
uniform sampler2DShadow shadowtex0; // Everything, including water and glass.
uniform sampler2DShadow shadowtex1; // Opaque casters only.
uniform sampler2D shadowcolor0;     // Colour of the nearest translucent caster.
uniform sampler2D shadowcolor1;     // Shadow-map depth of the water surface, 1 where there is none.

vec2 vogelDisk(int index, int count, float rotation) {
	float radius = sqrt((float(index) + 0.5) / float(count));
	float theta = float(index) * 2.39996323 + rotation;
	return radius * vec2(cos(theta), sin(theta));
}

// Blocks of water the light crosses before reaching a shadow-map position.
float waterPathLength(vec3 coord) {
	float surface = texture(shadowcolor1, coord.xy).r;
	if (surface >= coord.z) return 0.0;
	// Undo the [0,1] mapping, the depth squeeze and the orthographic projection.
	return (coord.z - surface) * 2.0 / SHADOW_DEPTH_SCALE / abs(shadowProjection[2][2]);
}

// Sunlight visibility, tinted where it passes through stained glass.
// offsetDir pushes the lookup towards the light to avoid self-shadowing.
// waterDepth receives the blocks of water above the point along the light.
vec3 sampleShadow(vec3 playerPos, vec3 offsetDir, float dither, float fallback, out float waterDepth) {
	waterDepth = 0.0;
	float texelWorld = 2.0 * shadowDistance / float(shadowMapResolution);
	float distortion = shadowDistortFactor(shadowClipPos(playerPos).xy);
	vec3 clipPos = shadowClipPos(playerPos + offsetDir * (texelWorld * distortion * 2.0 + 0.01));

	float fade = smoothstep(0.85, 1.0, max(abs(clipPos.x), abs(clipPos.y)));
	if (fade >= 1.0 || abs(clipPos.z) >= 1.0 / SHADOW_DEPTH_SCALE) return vec3(fallback);

	vec3 coord = distortShadow(clipPos) * 0.5 + 0.5;
	waterDepth = waterPathLength(coord) * (1.0 - fade);
	coord.z -= 0.00004;

	float radius = SHADOW_SOFTNESS * 1.6 / float(shadowMapResolution);
	float rotation = dither * 6.2831853;
	float opaque = 0.0;
	for (int i = 0; i < SHADOW_SAMPLES; i++) {
		opaque += texture(shadowtex1, vec3(coord.xy + vogelDisk(i, SHADOW_SAMPLES, rotation) * radius, coord.z));
	}
	opaque /= float(SHADOW_SAMPLES);

	vec3 light = vec3(opaque);
	if (opaque > 0.0) {
		float clear = texture(shadowtex0, coord);
		vec4 caster = texture(shadowcolor0, coord.xy);
		vec3 tint = mix(vec3(1.0), caster.rgb, caster.a);
		light *= mix(tint, vec3(1.0), clear);
	}
	return mix(light, vec3(fallback), fade);
}

// One hard tap, for ray marching through fog.
float shadowVisibility(vec3 playerPos) {
	vec3 clipPos = shadowClipPos(playerPos);
	if (max(abs(clipPos.x), abs(clipPos.y)) >= 1.0) return 1.0;
	vec3 coord = distortShadow(clipPos) * 0.5 + 0.5;
	return texture(shadowtex1, coord);
}
#endif
