// Clouds at real scale: a cumulus layer ray marched through 3D noise, and a
// thin sheet of cirrus far above it.
//
// Shapes follow the approach of Schneider's Nubis clouds: a weather map says
// how much of the sky is clouded and how tall the clouds grow, Perlin-Worley
// noise gives the lumpy cloud bodies, and finer Worley noise erodes their
// edges: wispy at the base, cauliflower billows higher up.
// Light is sunlight scattered many times inside the cloud, approximated with
// Wrenninge's octaves, plus the sky above and the ground below.

// Heights in blocks, taken as metres. Fair-weather cumulus has its flat base
// at about a kilometre; towering ones reach well past two.
const float CLOUD_BOTTOM = 1100.0;
const float CLOUD_TOP = 2700.0;
const float CIRRUS_HEIGHT = 7000.0;
const float CLOUD_EXTINCTION = 0.12; // Per block at full density; real cumulus is 0.02-0.1 per metre.
const vec2 CLOUD_WIND = vec2(9.0, 3.0); // Blocks per second.
// Blocks covered by one repeat of the noise volumes.
const float CLOUD_SHAPE_SCALE = 5200.0;
const float CLOUD_DETAIL_SCALE = 420.0;

// tex/cloud_noise.png, made by tools/generate_cloud_noise.py: two 3D noise
// volumes stored as grids of bordered slices.
uniform sampler2D cloudnoise;
const vec2 CLOUD_NOISE_ATLAS = vec2(800.0, 528.0);

// Trilinear lookup in one of the volumes.
//   n       voxels along each edge
//   origin  top left of the volume in the atlas, in texels
vec4 sampleNoiseVolume(vec3 p, float n, vec2 origin) {
	const float COLUMNS = 8.0;
	p = fract(p) * n;
	float slice = p.y - 0.5;
	float s0 = floor(slice);
	float f = slice - s0;
	float i0 = mod(s0, n);
	float i1 = mod(s0 + 1.0, n);
	// One texel of border around each slice.
	vec2 inTile = p.xz + 1.0;
	vec2 tile0 = vec2(mod(i0, COLUMNS), floor(i0 / COLUMNS)) * (n + 2.0);
	vec2 tile1 = vec2(mod(i1, COLUMNS), floor(i1 / COLUMNS)) * (n + 2.0);
	vec4 a = textureLod(cloudnoise, (origin + tile0 + inTile) / CLOUD_NOISE_ATLAS, 0.0);
	vec4 b = textureLod(cloudnoise, (origin + tile1 + inTile) / CLOUD_NOISE_ATLAS, 0.0);
	return mix(a, b, f);
}

float remap(float x, float a, float b, float c, float d) {
	return c + (x - a) / (b - a) * (d - c);
}

float cloudHeightFraction(float y) {
	return clamp((y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM), 0.0, 1.0);
}

vec2 cloudDrift() {
	return CLOUD_WIND * frameTimeCounter;
}

float cloudCoverageSetting() {
	return clamp(CLOUD_COVERAGE + rainStrength * 0.42 + thunderStrength * 0.1, 0.0, 1.0);
}

// x: how much of the sky here is clouded, y: how tall the clouds grow (0 flat,
// 1 towering). Both drift with the wind and change over tens of kilometres.
vec2 cloudWeather(vec2 xz) {
	vec2 uv = (xz + cloudDrift()) * 0.000045;
	float patches = texture(noisetex, uv).g * 0.65 + texture(noisetex, uv * 2.3 + 0.37).g * 0.35;
	// Clouds come in fields and streets with clear sky between them.
	float coverage = clamp(cloudCoverageSetting() + (patches - 0.5) * 1.4, 0.0, 1.0);
	float growth = smoothstep(0.3, 0.75, texture(noisetex, uv * 0.6 + vec2(0.61, 0.19)).r);
	// Rain comes from a thick grey deck; storms build the tallest towers.
	growth = mix(growth, 1.0, max(rainStrength * 0.7, thunderStrength));
	return vec2(coverage, growth);
}

// Where p is in the drifting noise. Wind is stronger higher up, so the
// tops lean downwind.
vec3 cloudShapePos(vec3 p, float h) {
	p.xz += cloudDrift() + normalize(CLOUD_WIND) * (h * 300.0);
	return p;
}

// How dense a cloud can be at a height: a flat base that forms where rising
// air reaches its dew point, and a rounded top that is higher on taller clouds.
float cloudHeightProfile(float h, float growth) {
	float top = mix(0.3, 1.0, growth);
	float base = smoothstep(0.0, 0.06, h);
	float crown = 1.0 - smoothstep(top * 0.45, top, h);
	return base * crown;
}

// Density of the large cloud bodies, before the fine erosion (which only
// ever removes density). shape receives the noise for erodeCloud.
float cloudBodyDensity(vec3 p, vec2 weather, out vec4 shape) {
	shape = vec4(0.0);
	float coverage = weather.x;
	if (coverage < 0.01) return 0.0;
	float h = cloudHeightFraction(p.y);
	float profile = cloudHeightProfile(h, weather.y);
	if (profile <= 0.0) return 0.0;
	shape = sampleNoiseVolume(cloudShapePos(p, h) / CLOUD_SHAPE_SCALE, 64.0, vec2(0.0));
	float lowFbm = dot(shape.gba, vec3(0.75, 0.2, 0.05));
	float body = remap(shape.r, lowFbm - 1.0, 1.0, 0.0, 1.0) * profile;
	// Coverage decides how much of each body rises above the threshold.
	return clamp(remap(body, 1.0 - coverage, 1.0, 0.0, 1.0), 0.0, 1.0) * coverage;
}

// Carves the fine structure out of a body density.
float erodeCloud(vec3 p, float density, vec4 shape) {
	float h = cloudHeightFraction(p.y);
	// Turbulence curls the fine structure as it is carried along.
	vec3 q = cloudShapePos(p, h);
	q.xz += (shape.gb - 0.5) * 120.0;
	vec3 detail = sampleNoiseVolume(q / CLOUD_DETAIL_SCALE + vec3(0.0, frameTimeCounter * 0.0015, 0.0), 32.0, vec2(528.0, 0.0)).rgb;
	float highFbm = dot(detail, vec3(0.625, 0.25, 0.125));
	// Ragged wisps at the base, rounded billows above.
	float erosion = mix(highFbm, 1.0 - highFbm, clamp(h * 8.0, 0.0, 1.0));
	return clamp(remap(density, erosion * 0.7, 1.0, 0.0, 1.0), 0.0, 1.0);
}

// Optical depth from p towards the light, through the cloud and past it.
// The weather changes over kilometres, so the value at p serves the whole ray.
float cloudLightDepth(vec3 p, vec3 lightDir, vec2 weather, float dither) {
	float depth = 0.0;
	float stepLength = 35.0;
	float travelled = 0.0;
	for (int i = 0; i < CLOUD_LIGHT_STEPS; i++) {
		vec3 q = p + lightDir * (travelled + stepLength * (0.5 + 0.5 * dither));
		vec4 shape;
		float density = cloudBodyDensity(q, weather, shape);
		// Only the nearest steps see the fine structure.
		if (i == 0 && density > 0.0) density = erodeCloud(q, density, shape);
		depth += density * stepLength;
		travelled += stepLength;
		stepLength *= 2.0;
	}
	return depth * CLOUD_EXTINCTION;
}

// Two lobes: a strong forward peak (the bright rim around a cloud in front of
// the sun) and a weak backward one (the glow of the side facing the sun).
float cloudPhase(float cosTheta, float spread) {
	return mix(henyeyGreenstein(cosTheta, 0.8 * spread), henyeyGreenstein(cosTheta, -0.25 * spread), 0.25);
}

// Thin ice-crystal streaks high above everything else. Returns in-scattered
// light (rgb) and transmittance (a).
vec4 cirrusLayer(vec3 dir, vec3 sunDir) {
#ifdef CIRRUS_CLOUDS
	if (dir.y <= 0.01) return vec4(0.0, 0.0, 0.0, 1.0);
	float t = (CIRRUS_HEIGHT - cameraPosition.y) / dir.y;
	vec2 p = cameraPosition.xz + dir.xz * t + cloudDrift() * 2.5;
	// Fall streaks are drawn out along the wind at that height.
	vec2 along = normalize(vec2(0.95, 0.3));
	vec2 uv = vec2(dot(p, along) * 0.000012, dot(p, vec2(-along.y, along.x)) * 0.00006);
	vec2 warp = vec2(texture(noisetex, uv * 0.7).g, texture(noisetex, uv * 0.7 + 0.5).g) - 0.5;
	uv += warp * 0.25;
	float streaks = texture(noisetex, uv).g * 0.55 + texture(noisetex, uv * vec2(2.1, 3.7) + 0.3).g * 0.3
		+ texture(noisetex, uv * vec2(4.3, 9.1) + 0.7).g * 0.15;
	float patches = texture(noisetex, p * 0.0000045 + 0.21).g;
	float amount = CIRRUS_AMOUNT * smoothstep(0.35, 0.65, patches) * (1.0 - rainStrength);
	float density = smoothstep(0.52, 0.8, streaks) * amount;
	if (density <= 0.001) return vec4(0.0, 0.0, 0.0, 1.0);

	float opticalDepth = density * 0.35 / max(dir.y, 0.05) * 0.2;
	float transmittance = exp(-opticalDepth);
	float cosTheta = dot(dir, worldLightDir());
	// Ice crystals scatter strongly forwards; halos are left out.
	float phase = mix(henyeyGreenstein(cosTheta, 0.75), henyeyGreenstein(cosTheta, -0.2), 0.3);
	vec3 light = celestialIlluminance(sunDir) * phase * 1.5 + skyRadiance(vec3(0.0, 1.0, 0.0), sunDir) * 0.8;
	vec3 scattered = light * (1.0 - transmittance);
	// Far streaks near the horizon sink into the haze.
	float haze = 1.0 - exp(-t * 0.00003);
	scattered = mix(scattered, skyRadiance(dir, sunDir) * (1.0 - transmittance), haze);
	return vec4(scattered, transmittance);
#else
	return vec4(0.0, 0.0, 0.0, 1.0);
#endif
}

// Returns in-scattered light (rgb) and transmittance (a) along dir up to maxT.
vec4 marchClouds(vec3 dir, float maxT, float dither, vec3 sunDir) {
	vec4 cirrus = maxT > 1e5 ? cirrusLayer(dir, sunDir) : vec4(0.0, 0.0, 0.0, 1.0);

	float camY = cameraPosition.y;
	float tBottom = (CLOUD_BOTTOM - camY) / dir.y;
	float tTop = (CLOUD_TOP - camY) / dir.y;
	float tEnter;
	float tExit;
	if (camY < CLOUD_BOTTOM) {
		if (dir.y <= 0.0) return cirrus;
		tEnter = tBottom;
		tExit = tTop;
	} else if (camY > CLOUD_TOP) {
		if (dir.y >= 0.0) return cirrus;
		tEnter = tTop;
		tExit = tBottom;
	} else {
		tEnter = 0.0;
		tExit = dir.y > 0.0 ? tTop : tBottom;
	}
	// Grazing rays cross the layer for tens of kilometres; beyond that the
	// clouds are lost in the haze.
	tExit = min(min(tExit, tEnter + 9000.0), maxT);
	if (tExit <= tEnter || tEnter > 60000.0) return cirrus;

	vec3 lightDir = worldLightDir();
	vec3 light = celestialIlluminance(sunDir);
	// Sky light on the tops, and light from the ground on the bases.
	vec3 skyAmbient = skyRadiance(vec3(0.0, 1.0, 0.0), sunDir) * PI;
	vec3 groundAmbient = (directIlluminance(sunDir) * max(lightDir.y, 0.0) + skyAmbient * 0.5) * vec3(0.16, 0.17, 0.12) * 0.5;
	float cosTheta = dot(dir, lightDir);
	float rain = 1.0 + rainStrength * 2.0;

	vec3 scattered = vec3(0.0);
	float transmittance = 1.0;
	// Fine steps inside clouds, a few times longer through clear air; both
	// grow with distance, where a pixel covers more of the cloud.
	float t = tEnter;
	bool started = false;
	for (int i = 0; i < CLOUD_STEPS; i++) {
		float fineStep = 40.0 + (t - tEnter) * 0.006;
		if (!started) {
			t += fineStep * dither;
			started = true;
		}
		if (t >= tExit) break;
		vec3 p = cameraPosition + dir * t;
		vec2 weather = cloudWeather(p.xz);
		vec4 shape;
		float density = cloudBodyDensity(p, weather, shape);
		// Clear air: nothing the fine erosion could add, so stride on.
		if (density <= 0.0) {
			t += fineStep * 3.0;
			continue;
		}
		float stepLength = min(fineStep, tExit - t);
		t += stepLength;
		density = erodeCloud(p, density, shape);
		if (density <= 0.001) continue;
		float sigma = density * CLOUD_EXTINCTION * rain;
		float lightDepth = cloudLightDepth(p, lightDir, weather, dither) * rain;

		// Light scattered many times inside the cloud still gets out: each
		// octave is dimmer but goes deeper and spreads wider.
		float sunEnergy = 0.0;
		float contribution = 1.0;
		float reach = 1.0;
		float spread = 1.0;
		for (int o = 0; o < CLOUD_SCATTER_OCTAVES; o++) {
			sunEnergy += contribution * exp(-lightDepth * reach) * cloudPhase(cosTheta, spread);
			contribution *= 0.7;
			reach *= 0.3;
			spread *= 0.6;
		}
		// Light is scattered out of the first metres of a cloud before it can
		// build up: edges facing away from the sun look darker ("powder").
		float powder = 1.0 - exp(-sigma * 60.0 - lightDepth * 2.0);
		sunEnergy *= mix(1.0, powder, 0.6 * (0.5 - 0.5 * cosTheta));

		float h = cloudHeightFraction(p.y);
		// Ambient light has to diffuse in from the outside of the cloud.
		float ambientReach = exp(-sigma * 25.0) * 0.6 + 0.4;
		vec3 ambient = mix(groundAmbient, skyAmbient, smoothstep(0.0, 0.6, h)) * ambientReach;
		vec3 radiance = light * sunEnergy * 5.0 + ambient * 0.5;

		float stepTransmittance = exp(-sigma * stepLength);
		scattered += transmittance * radiance * (1.0 - stepTransmittance);
		transmittance *= stepTransmittance;
		if (transmittance < 0.01) break;
	}

	// Aerial perspective: far clouds fade into the sky behind them.
	float haze = 1.0 - exp(-tEnter * 0.00005);
	scattered = mix(scattered, skyRadiance(dir, sunDir) * (1.0 - transmittance), haze);
	// The cirrus lies behind the cumulus.
	return vec4(scattered + cirrus.rgb * transmittance, transmittance * cirrus.a);
}

// Rough cloud cover at the middle of the layer, from the weather map alone.
float cloudCoverageAt(vec2 xz) {
	vec2 weather = cloudWeather(xz);
	return smoothstep(0.25, 0.75, weather.x);
}

// One sample at the middle of the layer: used for reflections.
vec4 cheapClouds(vec3 dir, vec3 sunDir) {
	if (dir.y <= 0.02 || cameraPosition.y > CLOUD_BOTTOM) return vec4(0.0, 0.0, 0.0, 1.0);
	float t = ((CLOUD_BOTTOM + CLOUD_TOP) * 0.5 - cameraPosition.y) / dir.y;
	vec3 p = cameraPosition + dir * t;
	float opacity = smoothstep(0.05, 0.5, cloudCoverageAt(p.xz)) * 0.9;
	vec3 color = celestialIlluminance(sunDir) * 0.12 + skyRadiance(vec3(0.0, 1.0, 0.0), sunDir) * PI * 0.5;
	float haze = 1.0 - exp(-t * 0.00005);
	color = mix(color, skyRadiance(dir, sunDir), haze);
	return vec4(color * opacity, 1.0 - opacity);
}

// Fraction of sunlight passing the cloud layer above a point.
float cloudShadow(vec3 playerPos, vec3 lightDir) {
#ifdef VOLUMETRIC_CLOUDS
	vec3 p = playerPos + cameraPosition;
	float midHeight = CLOUD_BOTTOM + (CLOUD_TOP - CLOUD_BOTTOM) * 0.2;
	if (p.y > midHeight || lightDir.y < 0.05) return 1.0;
	p += lightDir * ((midHeight - p.y) / lightDir.y);
	// The large cloud bodies low in the layer, without the fine erosion.
	vec4 shape;
	float cover = cloudBodyDensity(vec3(p.x, midHeight, p.z), cloudWeather(p.xz), shape);
	return mix(1.0, 0.15, smoothstep(0.0, 0.15, cover));
#else
	return 1.0;
#endif
}
