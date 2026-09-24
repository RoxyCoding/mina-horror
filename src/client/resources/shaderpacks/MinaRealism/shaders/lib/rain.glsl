// Falling rain streaks and water drops on the camera lens.
// Needs settings and common.

// One layer of motion-blurred raindrops.
//   coord.x  across the streak columns (one unit per column)
//   coord.y  along the fall (one unit per drop cycle)
//   pixelWidth  size of a pixel in column units, from fwidth
// Returns the streak's coverage of the pixel.
float rainStreak(vec2 coord, float pixelWidth, float seed) {
	float lane = floor(coord.x);
	vec4 laneHash = hash43(vec3(lane, seed, 3.1));
	float v = coord.y * (0.8 + laneHash.y * 0.8) + laneHash.z;
	vec4 dropHash = hash43(vec3(lane, floor(v), seed + 5.3));
	float density = clamp((0.3 + 0.45 * rainStrength) * RAIN_AMOUNT, 0.0, 0.95);
	if (dropHash.x > density) return 0.0;

	// Long thin streak with soft ends.
	float along = fract(v);
	float streakLength = 0.2 + 0.35 * dropHash.y;
	float body = smoothstep(0.0, 0.06, along) * (1.0 - smoothstep(streakLength * 0.6, streakLength, along));

	// Drops are far thinner than a pixel away from the camera: fade by coverage
	// instead of letting the streak flicker in and out.
	float center = 0.25 + 0.5 * dropHash.z;
	const float HALF_WIDTH = 0.04;
	float across = clamp((HALF_WIDTH + pixelWidth * 0.5 - abs(fract(coord.x) - center)) / pixelWidth, 0.0, 1.0);
	across *= min(1.0, 2.0 * HALF_WIDTH / pixelWidth);
	return body * across * (0.5 + 0.5 * dropHash.w);
}

// Drops resting on the lens, landing and evaporating over time.
// Returns the water surface slope in xy (for refraction) and coverage in z.
vec3 lensRestingDrops(vec2 p, float scale, float seed, float density) {
	p *= scale;
	vec2 id = floor(p);
	vec2 f = fract(p) - 0.5;
	vec4 h = hash43(vec3(id, seed));
	if (h.x > density) return vec3(0.0);
	float life = fract(frameTimeCounter * 0.04 + h.w);
	float presence = smoothstep(0.0, 0.03, life) * (1.0 - smoothstep(0.6, 1.0, life));
	float radius = mix(0.08, 0.3, h.z * h.z) * presence;
	if (radius < 0.01) return vec3(0.0);
	vec2 d = f - (h.xy - 0.5) * 0.6;
	float r = length(d) / radius;
	float mask = 1.0 - smoothstep(0.8, 1.0, r);
	return vec3(d / radius * mask, mask);
}

// Drops that grow heavy and run down the lens in stick-slip jerks, leaving a
// trail that breaks into droplets.
vec3 lensRunningDrops(vec2 p, float aspect, float density) {
	const float COLUMNS = 5.0;
	float column = floor(p.x / aspect * COLUMNS);
	vec4 columnHash = hash43(vec3(column, 7.7, 1.3));
	float phase = frameTimeCounter * (0.05 + 0.08 * columnHash.y) + columnHash.z;
	vec4 h = hash43(vec3(column, floor(phase), 2.9));
	if (h.x > density) return vec3(0.0);
	float local = fract(phase);
	// Sticking and slipping: progress comes in bursts.
	local += 0.04 * sin(local * 50.0 + h.z * 6.28);
	float dropY = 1.25 - local * 1.5;

	// The path wanders sideways as the drop picks its way down.
	float columnWidth = aspect / COLUMNS;
	float pathX = (column + 0.5 + (h.y - 0.5) * 0.6) * columnWidth + sin(p.y * 9.0 + h.z * 6.28) * 0.012;
	float radius = 0.012 + 0.014 * h.w;
	vec2 d = vec2(p.x - pathX, p.y - dropY);
	vec2 e = d / vec2(radius, radius * 1.35);
	float r = length(e);
	float mask = 1.0 - smoothstep(0.8, 1.0, r);
	vec2 slope = e * mask;

	float above = p.y - dropY;
	const float TRAIL = 0.3;
	if (above > 0.0 && above < TRAIL) {
		float left = 1.0 - above / TRAIL;
		float width = radius * 0.35 * left;
		float trail = 1.0 - smoothstep(width * 0.5, width, abs(d.x));
		float bead = abs(fract(p.y * 45.0 + h.z) - 0.5);
		float droplets = 1.0 - smoothstep(0.1, 0.25, bead);
		float trailMask = trail * mix(0.3, 1.0, droplets) * left;
		mask = max(mask, trailMask * 0.7);
		slope += vec2(d.x / max(width, 1e-4), 0.0) * trailMask * 0.5;
	}
	return vec3(slope, mask);
}
