#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/rain.glsl"

// Rain as the eye sees it, drawn over the finished scene:
//   near drops from the vanilla weather quads (colortex8) bend the light behind them,
//   layers of distant rain fill the space beyond the vanilla rain radius,
//   and drops on the lens show small blurred, upside-down images.

const bool colortex0MipmapEnabled = true;

uniform sampler2D colortex0;
uniform sampler2D colortex8;
uniform sampler2D depthtex0;
uniform int biome_precipitation; // 0 none, 1 rain, 2 snow
uniform float lensRainExposure;   // shaders.properties: 1 under open sky, eased in and out

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

// Light a raindrop picks up: the sky around it, and a glint towards the sun or moon.
vec3 rainDropLight(vec3 dir) {
	vec3 sunDir = worldSunDir();
	vec3 surroundings = skyIrradiance(vec3(0.0, 1.0, 0.0), sunDir) / PI * eyeSkyExposure();
	vec3 glow = directIlluminance(sunDir) * henyeyGreenstein(dot(dir, worldLightDir()), 0.8) * 0.05;
	return surroundings * 0.4 + glow;
}

// Cylinders of rain around the camera, beyond the vanilla weather radius and
// hidden behind anything closer. Returns premultiplied light and coverage.
vec4 distantRain(vec3 dir, float sceneDistance) {
	const float RADII[3] = float[](12.0, 20.0, 34.0);
	float horizontal = max(length(dir.xz), 1e-3);
	float azimuth = atan(dir.z, dir.x);
	float slope = dir.y / horizontal;
	float coverage = 0.0;
	for (int i = 0; i < 3; i++) {
		float radius = RADII[i];
		float height = slope * radius + cameraPosition.y;
		// Columns every 0.35 blocks, drops falling 9 blocks a second, slanted by wind.
		vec2 coord = vec2(azimuth * radius / 0.35, (height + frameTimeCounter * 9.0) / 3.0);
		coord.x += coord.y * 0.15;
		float pixelWidth = max(fwidth(coord.x), 1e-4);
		float visible = smoothstep(radius - 1.0, radius + 1.0, sceneDistance);
		float layer = rainStreak(coord, pixelWidth, 10.0 + float(i)) * 0.35 * visible;
		coverage = 1.0 - (1.0 - coverage) * (1.0 - layer);
	}
	// The cylinder projection breaks down looking straight up or down.
	coverage *= smoothstep(0.25, 0.45, horizontal);
	return vec4(rainDropLight(dir) * coverage, coverage);
}

void main() {
	// colortex0 has mipmaps here; sample level 0 explicitly, also inside branches.
	vec3 color = textureLod(colortex0, texcoord, 0.0).rgb;
	vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
	bool raining = hasSkylight && biome_precipitation == 1 && isEyeInWater == 0;

	// Near drops: each streak is a thin lens that shifts the scene behind it.
	vec4 nearRain = texture(colortex8, texcoord);
	if (nearRain.a > 0.001) {
		vec2 gradient = vec2(
			texture(colortex8, texcoord + vec2(texel.x, 0.0)).a - texture(colortex8, texcoord - vec2(texel.x, 0.0)).a,
			texture(colortex8, texcoord + vec2(0.0, texel.y)).a - texture(colortex8, texcoord - vec2(0.0, texel.y)).a);
		vec3 bent = textureLod(colortex0, texcoord - gradient * texel * 4.0, 0.0).rgb;
		color = mix(color, bent, min(nearRain.a * 1.5, 1.0)) + nearRain.rgb;
	}

	if (raining && rainStrength > 0.01) {
		float depth = texture(depthtex0, texcoord).r;
		vec3 playerPos = viewToPlayer(screenToView(texcoord, depth));
		float sceneDistance = depth >= 1.0 ? 1e6 : length(playerPos.xz);
		vec4 farRain = distantRain(normalize(playerPos), sceneDistance);
		float strength = rainStrength * smoothstep(0.3, 0.9, eyeSkyExposure());
		color = color * (1.0 - farRain.a * 0.3 * strength) + farRain.rgb * strength;
	}

#ifdef LENS_DROPS
	// Rain only reaches the lens under open sky; under a roof or a tree the
	// drops dry off over several seconds.
	float lensWet = raining ? wetness * lensRainExposure : 0.0;
	if (lensWet > 0.01) {
		float aspect = viewWidth / viewHeight;
		vec2 p = vec2(texcoord.x * aspect, texcoord.y);
		vec3 large = lensRestingDrops(p, 12.0, 1.0, 0.3 * lensWet);
		vec3 small = lensRestingDrops(p, 28.0, 2.0, 0.45 * lensWet);
		vec3 running = lensRunningDrops(p, aspect, 0.7 * lensWet);
		float coverage = max(max(large.z, small.z), running.z);
		if (coverage > 0.001) {
			vec2 slope = large.xy + small.xy + running.xy;
			// A drop is a tiny wide-angle lens: it shows a small upside-down image
			// of the scene, out of focus because the lens is so close.
			vec2 offset = -slope * vec2(1.0 / aspect, 1.0) * 0.06;
			vec3 through = textureLod(colortex0, clamp(texcoord + offset, 0.0, 1.0), 2.5).rgb;
			// The rim bends light away from the eye and looks darker.
			float rim = smoothstep(0.6, 1.0, length(slope));
			color = mix(color, through * (1.0 - 0.35 * rim), coverage);
		}
	}
#endif

	outColor = vec4(sanitizeColor(color), 1.0);
}
