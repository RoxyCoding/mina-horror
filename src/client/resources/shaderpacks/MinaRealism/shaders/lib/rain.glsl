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

// Inverse of smoothstep(0, 1, t).
float inverseSmoothstep(float y) {
	return 0.5 - sin(asin(1.0 - 2.0 * y) / 3.0);
}

// Sideways wander of a running drop's path: water picks its way around dry
// spots and dirt, so the path bends irregularly rather than swaying evenly.
float runningDropPathX(float y, float baseX, vec4 h) {
	return baseX + sin(y * 7.0 + h.z * 6.28) * 0.010 + sin(y * 17.0 + h.w * 6.28) * 0.005;
}

// Drops that fill up on the lens, break loose and run down in stick-slip
// jerks. The film they leave stays where they went and breaks into beads.
vec3 lensRunningDrops(vec2 p, float aspect, float density) {
	const float COLUMNS = 7.0;
	const int SLIPS = 6;
	// Share of the cycle spent filling up, running, and before fading out.
	const float GROW = 0.2;
	const float RUN = 0.5;
	const float FADE = 0.88;

	float columnWidth = aspect / COLUMNS;
	float column = floor(p.x / columnWidth);
	vec4 columnHash = hash43(vec3(column, 7.7, 1.3));
	float phase = frameTimeCounter * (0.04 + 0.06 * columnHash.y) + columnHash.z;
	float cycle = floor(phase);
	vec4 h = hash43(vec3(column, cycle, 2.9));
	if (h.x > density) return vec3(0.0);
	float local = fract(phase);

	float baseX = (column + 0.5 + (h.y - 0.5) * 0.5) * columnWidth;
	if (abs(p.x - baseX) > 0.06) return vec3(0.0);
	vec4 h2 = hash43(vec3(column, cycle, 4.1));
	float startY = mix(0.35, 1.05, h2.x);
	// Most drops run off the bottom; some stop on a dry patch part way down.
	float travel = h2.y < 0.7 ? startY + 0.1 : startY * mix(0.2, 0.6, h2.z);
	float radius = 0.011 + 0.013 * h.w;

	// Slips of irregular length at irregular times, sticking in between.
	float amount[SLIPS];
	float stick[SLIPS];
	float total = 0.0;
	for (int i = 0; i < SLIPS; i++) {
		vec4 s = hash43(vec3(column * 13.0 + float(i), cycle, 6.7));
		amount[i] = 0.15 + s.x * s.x;
		stick[i] = 0.15 + 0.7 * s.y;
		total += amount[i];
	}

	float runT = clamp((local - GROW) / RUN, 0.0, 1.0);
	int slipIndex = min(int(runT * float(SLIPS)), SLIPS - 1);
	float slipPart = runT * float(SLIPS) - float(slipIndex);
	// Distance from the start in slip units: of the drop, and of this pixel.
	float pixelDist = (startY - p.y) / travel * total;
	float dropDist = 0.0;
	float passedAt = 2.0;
	float before = 0.0;
	for (int i = 0; i < SLIPS; i++) {
		if (i == slipIndex) {
			float slide = clamp((slipPart - stick[i]) / (1.0 - stick[i]), 0.0, 1.0);
			dropDist = before + amount[i] * smoothstep(0.0, 1.0, slide);
		}
		if (pixelDist >= before && pixelDist < before + amount[i]) {
			float slide = inverseSmoothstep((pixelDist - before) / amount[i]);
			passedAt = GROW + (float(i) + mix(stick[i], 1.0, slide)) / float(SLIPS) * RUN;
		}
		before += amount[i];
	}
	if (runT >= 1.0) dropDist = total;
	float dropY = startY - dropDist / total * travel;

	float fade = 1.0 - smoothstep(FADE, 1.0, local);
	// The drop fills up from rain landing on it before it breaks loose, and
	// loses water to its trail on the way down.
	float grow = smoothstep(0.0, GROW, local);
	float r = radius * mix(0.5, 1.0, grow) * mix(1.0, 0.75, runT);

	// Running drops are round at the front and drawn out behind.
	vec2 d = vec2(p.x - runningDropPathX(dropY, baseX, h), p.y - dropY);
	vec2 e = d / vec2(r, d.y < 0.0 ? r : r * 1.5);
	float mask = (1.0 - smoothstep(0.8, 1.0, length(e))) * smoothstep(0.0, 0.03, local) * fade;
	vec2 slope = e * mask;

	// The trail where the drop has been: a thin film that soon breaks up,
	// leaving beads at uneven spacing that last until the lens dries.
	float age = local - passedAt;
	if (p.y > dropY && p.y < startY && age > 0.0) {
		float trailX = p.x - runningDropPathX(p.y, baseX, h);
		float width = radius * 0.4;
		float film = (1.0 - smoothstep(width * 0.5, width, abs(trailX))) * (1.0 - smoothstep(0.0, 0.08, age)) * 0.6;

		const float BEADS = 40.0;
		float cell = floor(p.y * BEADS);
		vec4 b = hash43(vec3(column * 17.0 + cell, cycle, 8.3));
		float bead = 0.0;
		vec2 beadSlope = vec2(0.0);
		if (b.x < 0.4) {
			float beadRadius = width * (0.25 + 0.75 * b.w * b.w);
			vec2 bd = vec2(trailX - (b.z - 0.5) * width, p.y - (cell + 0.15 + 0.7 * b.y) / BEADS) / beadRadius;
			bead = (1.0 - smoothstep(0.7, 1.0, length(bd))) * smoothstep(0.0, 0.03, age);
			beadSlope = bd * bead;
		}
		float trailMask = max(film, bead) * fade;
		mask = max(mask, trailMask);
		slope += (vec2(trailX / width, 0.0) * film + beadSlope) * fade * 0.6;
	}
	return vec3(slope, mask);
}
