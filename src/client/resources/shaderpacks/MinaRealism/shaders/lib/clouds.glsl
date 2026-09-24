// Volumetric cumulus layer, ray marched through tileable noise (tex/noise.png):
// r = Perlin-Worley shapes, g = smooth fbm, b = cells.

const float CLOUD_BOTTOM = 192.0;
const float CLOUD_TOP = 300.0;
const float CLOUD_EXTINCTION = 0.06; // Per block; real cumulus is about 0.05 per metre.
const vec2 CLOUD_WIND = vec2(6.0, 2.0); // Blocks per second.

float cloudHeightFraction(float y) {
	return clamp((y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM), 0.0, 1.0);
}

// Two slices of the 2D texture blended along y give cheap 3D noise.
float cloudNoise3D(vec3 p) {
	float slice = floor(p.y);
	float f = p.y - slice;
	f = f * f * (3.0 - 2.0 * f);
	vec2 uv = (p.xz + slice * vec2(17.0, 37.0)) / 256.0;
	return mix(texture(noisetex, uv).g, texture(noisetex, uv + vec2(17.0, 37.0) / 256.0).g, f);
}

float cloudCoverageAt(vec2 xz) {
	vec2 drift = CLOUD_WIND * frameTimeCounter;
	vec2 uv = (xz + drift) * 0.00022;
	float shape = texture(noisetex, uv).r * 0.7 + texture(noisetex, uv * 2.7 + 0.31).r * 0.3;
	// shape spans about 0.2..0.71 (2nd to 98th percentile), so coverage maps to the cloudy share of the sky.
	float coverage = clamp(CLOUD_COVERAGE + rainStrength * 0.35, 0.0, 1.0);
	float threshold = 0.713 - 0.513 * coverage;
	return clamp((shape - threshold) / 0.12, 0.0, 1.0);
}

float cloudDensity(vec3 p, bool detailed) {
	float h = cloudHeightFraction(p.y);
	float base = cloudCoverageAt(p.xz);
	// Flat bases and rounded tops; thicker clouds tower higher.
	float profile = smoothstep(0.0, 0.08, h) * (1.0 - smoothstep(0.2 + 0.75 * base, 1.0, h));
	float density = base * profile;
	if (detailed && density > 0.0) {
		vec3 q = (p + vec3(CLOUD_WIND.x, 0.0, CLOUD_WIND.y) * frameTimeCounter * 1.5) * 0.045;
		float detail = cloudNoise3D(q) * 0.65 + cloudNoise3D(q * 2.9) * 0.35;
		density -= (1.0 - detail) * 0.45 * (1.0 - density);
	}
	return clamp(density * 1.6, 0.0, 1.0);
}

// Optical depth from p towards the light through the cloud layer.
float cloudLightDepth(vec3 p, vec3 lightDir) {
	float depth = 0.0;
	float stepLength = 10.0;
	for (int i = 0; i < CLOUD_LIGHT_STEPS; i++) {
		p += lightDir * stepLength;
		depth += cloudDensity(p, i < 2) * stepLength;
		stepLength *= 1.8;
	}
	return depth * CLOUD_EXTINCTION;
}

// Returns in-scattered light (rgb) and transmittance (a) along dir up to maxT.
vec4 marchClouds(vec3 dir, float maxT, float dither, vec3 sunDir) {
	float camY = cameraPosition.y;
	float tBottom = (CLOUD_BOTTOM - camY) / dir.y;
	float tTop = (CLOUD_TOP - camY) / dir.y;
	float tEnter;
	float tExit;
	if (camY < CLOUD_BOTTOM) {
		if (dir.y <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
		tEnter = tBottom;
		tExit = tTop;
	} else if (camY > CLOUD_TOP) {
		if (dir.y >= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
		tEnter = tTop;
		tExit = tBottom;
	} else {
		tEnter = 0.0;
		tExit = dir.y > 0.0 ? tTop : tBottom;
	}
	// Keep grazing rays affordable; distant clouds melt into the haze anyway.
	tExit = min(min(tExit, tEnter + 2500.0), maxT);
	if (tExit <= tEnter || tEnter > 20000.0) return vec4(0.0, 0.0, 0.0, 1.0);

	vec3 lightDir = worldLightDir();
	vec3 light = celestialIlluminance(sunDir);
	vec3 ambient = skyRadiance(vec3(0.0, 1.0, 0.0), sunDir) * PI * 0.6;
	float cosTheta = dot(dir, lightDir);

	float stepLength = (tExit - tEnter) / float(CLOUD_STEPS);
	vec3 origin = cameraPosition + dir * tEnter;
	vec3 scattered = vec3(0.0);
	float transmittance = 1.0;
	for (int i = 0; i < CLOUD_STEPS; i++) {
		vec3 p = origin + dir * ((float(i) + dither) * stepLength);
		float density = cloudDensity(p, true);
		if (density <= 0.002) continue;
		// Rain clouds hold far more water and turn dark grey underneath.
		float sigma = density * CLOUD_EXTINCTION * (1.0 + rainStrength * 1.5);
		float lightDepth = cloudLightDepth(p, lightDir);

		// Multiple scattering approximated by progressively softer octaves (Wrenninge 2013).
		float sunEnergy = 0.0;
		float a = 1.0, b = 1.0, c = 1.0;
		for (int o = 0; o < CLOUD_SCATTER_OCTAVES; o++) {
			float phase = mix(henyeyGreenstein(cosTheta, 0.8 * c), henyeyGreenstein(cosTheta, -0.3 * c), 0.3);
			sunEnergy += a * exp(-lightDepth * b) * phase;
			a *= 0.5; b *= 0.4; c *= 0.5;
		}
		// Dark edges on the side facing away from the light ("powder" effect).
		float powder = 1.0 - exp(-sigma * 40.0);
		float h = cloudHeightFraction(p.y);
		vec3 radiance = light * sunEnergy * mix(1.0, powder, 0.5) * 4.0 + ambient * mix(0.35, 1.0, h);

		float stepTransmittance = exp(-sigma * stepLength);
		scattered += transmittance * radiance * (1.0 - stepTransmittance);
		transmittance *= stepTransmittance;
		if (transmittance < 0.01) break;
	}

	// Aerial perspective: far clouds fade into the sky behind them.
	float haze = 1.0 - exp(-tEnter * 0.00012);
	scattered = mix(scattered, skyRadiance(dir, sunDir) * (1.0 - transmittance), haze);
	return vec4(scattered, transmittance);
}

// One sample at the middle of the layer: used for reflections.
vec4 cheapClouds(vec3 dir, vec3 sunDir) {
	if (dir.y <= 0.02 || cameraPosition.y > CLOUD_BOTTOM) return vec4(0.0, 0.0, 0.0, 1.0);
	float t = ((CLOUD_BOTTOM + CLOUD_TOP) * 0.5 - cameraPosition.y) / dir.y;
	vec3 p = cameraPosition + dir * t;
	float opacity = smoothstep(0.05, 0.5, cloudCoverageAt(p.xz)) * 0.9;
	vec3 color = celestialIlluminance(sunDir) * 0.12 + skyRadiance(vec3(0.0, 1.0, 0.0), sunDir) * PI * 0.5;
	float haze = 1.0 - exp(-t * 0.00012);
	color = mix(color, skyRadiance(dir, sunDir), haze);
	return vec4(color * opacity, 1.0 - opacity);
}

// Fraction of sunlight passing the cloud layer above a point.
float cloudShadow(vec3 playerPos, vec3 lightDir) {
#ifdef VOLUMETRIC_CLOUDS
	vec3 p = playerPos + cameraPosition;
	float midHeight = (CLOUD_BOTTOM + CLOUD_TOP) * 0.5;
	if (p.y > midHeight || lightDir.y < 0.05) return 1.0;
	p += lightDir * ((midHeight - p.y) / lightDir.y);
	return mix(1.0, 0.2, smoothstep(0.05, 0.45, cloudCoverageAt(p.xz)));
#else
	return 1.0;
#endif
}
