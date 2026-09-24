// Procedural sun, moon and stars replacing the vanilla sprites.
// Needs shadow.glsl (sunPathRotation) and atmosphere.glsl.

// The real moon: 0.52 degrees across. Its surface is about 600000 times
// dimmer than the sun's, as in the real sky: pale against a daytime sky,
// bright but still showing its maria at night.
const float MOON_ANGULAR_RADIUS = 0.00452;
const float MOON_DISK_RADIANCE = 0.15;
// Warm grey regolith; the atmosphere tints it further near the horizon.
const vec3 MOON_ALBEDO_TINT = vec3(1.0, 0.96, 0.9);

// Frame that turns with the sky: the sun path lies in the plane normal to the axis.
mat3 celestialFrame(vec3 sunDir) {
	float tilt = radians(sunPathRotation);
	vec3 axis = vec3(0.0, sin(tilt), cos(tilt));
	return mat3(sunDir, cross(axis, sunDir), axis);
}

// A point on the near side of the moon from selenographic longitude and
// latitude in degrees: x east (towards Mare Crisium), y north, z towards Earth.
vec3 selenographic(float longitude, float latitude) {
	float lon = radians(longitude);
	float lat = radians(latitude);
	return vec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon));
}

// How much of a mare covers n. Lava flooded the basins unevenly, so the
// shores wander in and out.
float mare(vec3 n, float ragged, float longitude, float latitude, float radiusDegrees) {
	float angle = acos(clamp(dot(n, selenographic(longitude, latitude)), -1.0, 1.0));
	float radius = radians(radiusDegrees);
	return 1.0 - smoothstep(radius * 0.7, radius * 1.3, angle + (ragged - 0.5) * radius * 1.1);
}

// A young crater: a bright spot with streaks of ejecta thrown out radially.
float rayedCrater(vec3 n, float longitude, float latitude, float craterDegrees, float reachDegrees, float seed) {
	vec3 c = selenographic(longitude, latitude);
	float angle = acos(clamp(dot(n, c), -1.0, 1.0));
	float spot = exp(-pow(angle / radians(craterDegrees), 2.0));
	float reach = radians(reachDegrees);
	if (angle > reach) return spot;
	vec3 t1 = normalize(cross(c, vec3(0.0, 1.0, 0.0)));
	vec3 t2 = cross(c, t1);
	float azimuth = atan(dot(n, t2), dot(n, t1)) / 6.2831853;
	float streak = smoothstep(0.5, 0.8, texture(noisetex, vec2(azimuth, seed)).r)
		* mix(0.6, 1.0, texture(noisetex, vec2(azimuth * 3.0, seed + angle * 2.0)).g);
	float fade = 1.0 - angle / reach;
	return spot + streak * fade * sqrt(fade) * smoothstep(0.0, radians(craterDegrees) * 1.5, angle);
}

// Relative albedo of the near side: dark basalt maria, bright highlands and
// the rayed craters, laid out as on the real moon.
float moonAlbedo(vec3 n) {
	vec2 uv = n.xy * 0.45 + n.z * 0.13;
	float ragged = texture(noisetex, uv + vec2(0.31, 0.57)).g * 0.6 + texture(noisetex, uv * 3.3 + 0.19).g * 0.4;
	// Each mare with its own depth of colour: the iron and titanium-rich
	// Tranquillitatis is darkest, Serenitatis and Imbrium a little lighter.
	float dark = 0.0;
	dark = max(dark, mare(n, ragged, -57.0, 22.0, 22.0) * 0.5);  // Oceanus Procellarum, a vast plain along the west
	dark = max(dark, mare(n, ragged, -48.0, 2.0, 17.0) * 0.5);
	dark = max(dark, mare(n, ragged, -65.0, 8.0, 14.0) * 0.5);
	dark = max(dark, mare(n, ragged, -38.0, 38.0, 12.0) * 0.48);
	dark = max(dark, mare(n, ragged, -16.0, 33.0, 17.0) * 0.5);   // Mare Imbrium
	dark = max(dark, mare(n, ragged, 17.5, 28.0, 11.0) * 0.44);   // Mare Serenitatis
	dark = max(dark, mare(n, ragged, 31.0, 8.5, 12.0) * 0.58);    // Mare Tranquillitatis
	dark = max(dark, mare(n, ragged, 59.0, 17.0, 8.5) * 0.55);    // Mare Crisium
	dark = max(dark, mare(n, ragged, 51.0, -8.0, 11.0) * 0.48);   // Mare Fecunditatis
	dark = max(dark, mare(n, ragged, 35.0, -15.0, 5.5) * 0.48);   // Mare Nectaris
	dark = max(dark, mare(n, ragged, -17.0, -21.0, 11.0) * 0.46); // Mare Nubium
	dark = max(dark, mare(n, ragged, -23.0, -10.0, 6.0) * 0.46);  // Mare Cognitum
	dark = max(dark, mare(n, ragged, -39.0, -24.0, 6.5) * 0.5);   // Mare Humorum
	dark = max(dark, mare(n, ragged, -31.0, 7.0, 7.5) * 0.46);    // Mare Insularum
	dark = max(dark, mare(n, ragged, 4.0, 13.0, 4.0) * 0.46);     // Mare Vaporum
	dark = max(dark, mare(n, ragged, -25.0, 56.0, 6.0) * 0.42);   // Mare Frigoris, a long belt in the north
	dark = max(dark, mare(n, ragged, 0.0, 56.0, 6.5) * 0.42);
	dark = max(dark, mare(n, ragged, 25.0, 57.0, 5.5) * 0.42);

	// Highlands saturated with craters of every size, brightest in the south.
	float craters = texture(noisetex, uv * 1.7 + vec2(0.13, 0.71)).b * 0.5 + texture(noisetex, uv * 4.1 + 0.4).b * 0.3
		+ texture(noisetex, uv * 9.0 + 0.7).b * 0.2;
	float albedo = (1.0 - dark) * mix(0.8, 1.1, craters) * (1.0 + 0.08 * smoothstep(0.2, -0.6, n.y));
	// Mottling within the maria, from lava flows of different ages.
	albedo *= mix(1.0, mix(0.85, 1.15, ragged), dark * 2.0);

	float rays = rayedCrater(n, -11.4, -43.3, 1.5, 55.0, 0.13) * 0.8  // Tycho
		+ rayedCrater(n, -20.0, 9.6, 1.6, 20.0, 0.37) * 0.5            // Copernicus
		+ rayedCrater(n, -38.0, 8.1, 0.9, 12.0, 0.61) * 0.4            // Kepler
		+ rayedCrater(n, -47.4, 23.7, 0.9, 6.0, 0.83) * 0.9            // Aristarchus, the brightest spot
		+ rayedCrater(n, 47.0, 16.1, 0.8, 9.0, 0.29) * 0.35;           // Proclus
	return albedo + rays * 0.6;
}

vec3 moonDisk(vec3 dir, vec3 sunDir) {
	vec3 moonDir = -sunDir;
	// The distance, unlike a dot product this close to 1, survives rounding.
	if (length(dir - moonDir) > MOON_ANGULAR_RADIUS * SUN_MOON_SIZE * 1.3) return vec3(0.0);
	// The moon keeps its north towards the celestial pole as it crosses the sky.
	float tilt = radians(sunPathRotation);
	vec3 pole = -vec3(0.0, sin(tilt), cos(tilt));
	vec3 up = normalize(pole - moonDir * dot(pole, moonDir));
	vec3 right = cross(moonDir, up);
	float radius = MOON_ANGULAR_RADIUS * SUN_MOON_SIZE;
	vec2 q = skyDiscCoord(dir, moonDir, radius, right, up);
	float r = length(q);
	float pixel = skyDiscPixel(radius);
	float coverage = 1.0 - smoothstep(1.0 - pixel, 1.0 + pixel, r);
	if (coverage <= 0.0) return vec3(0.0);
	q /= max(r, 1.0);
	vec3 n = vec3(q, sqrt(max(1.0 - dot(q, q), 0.0)));

	// Phase 0 is full, 4 is new. Waning moons (1-3) are lit from the east,
	// the left as seen from the north; waxing ones (5-7) from the west.
	float phaseAngle = float(moonPhase) * (PI / 4.0);
	vec3 toSun = vec3(-sin(phaseAngle), 0.0, cos(phaseAngle));
	float mu0 = dot(n, toSun);
	// Lommel-Seeliger: the regolith looks evenly bright right up to the limb,
	// and the full moon brightens sharply as shadows vanish (opposition surge).
	float lit = mu0 > 0.0 ? 2.0 * mu0 / (mu0 + n.z) : 0.0;
	float sunMoonAngle = min(phaseAngle, 2.0 * PI - phaseAngle);
	lit *= smoothstep(-0.01, 0.03, mu0) * (1.0 + 0.35 * exp(-sunMoonAngle / 0.12));
	// Sunlight reflected by the Earth: the dark part glows faintly, most
	// around new moon when the Earth, seen from the moon, is nearly full.
	float earthPhase = (1.0 - cos(phaseAngle)) * 0.5;
	float earthshine = 0.012 * earthPhase * earthPhase;

	vec3 transmittance = discTransmittance(dir.y);
	return MOON_DISK_RADIANCE * MOON_ALBEDO_TINT * moonAlbedo(n) * (lit + earthshine) * coverage * transmittance
		* (1.0 - rainStrength) * smoothstep(-0.002, 0.002, dir.y);
}

float nightAmount(vec3 sunDir) {
	return 1.0 - smoothstep(-0.2, -0.02, sunDir.y);
}

// Galactic plane in the celestial frame, and the direction of the bright bulge.
const vec3 GALACTIC_NORTH = vec3(0.46, 0.53, 0.711);
const vec3 GALACTIC_CENTER = vec3(0.887, -0.275, -0.370);

// Faint band of unresolved stars crossed by dark dust lanes.
float milkyWay(vec3 celestial, out float core) {
	float latitude = dot(celestial, GALACTIC_NORTH);
	float band = exp(-latitude * latitude / 0.018);
	core = pow(max(dot(celestial, GALACTIC_CENTER), 0.0), 3.0);
	if (band < 0.01) return 0.0;
	vec2 uv = vec2(dot(celestial, GALACTIC_CENTER), dot(celestial, cross(GALACTIC_NORTH, GALACTIC_CENTER)));
	float clouds = texture(noisetex, uv * 0.9 + 0.5).r;
	float dust = smoothstep(0.45, 0.7, texture(noisetex, uv * 1.7 + 0.21).g) * exp(-latitude * latitude / 0.004);
	return band * (0.35 + 0.65 * clouds) * (1.0 - dust * 0.8) * (1.0 + 2.0 * core);
}

vec3 starField(vec3 dir, vec3 sunDir) {
	float night = nightAmount(sunDir);
	if (night <= 0.0 || dir.y <= 0.0) return vec3(0.0);
	vec3 celestial = dir * celestialFrame(sunDir);
	float core;
	float galaxy = milkyWay(celestial, core);

	vec3 p = celestial * 220.0;
	vec3 cell = floor(p);
	vec4 h = hash43(cell);
	vec3 stars = vec3(0.0);
	// Stars crowd together along the Milky Way.
	if (h.x > 0.965 - 0.03 * min(galaxy, 1.0)) {
		vec3 center = cell + 0.5 + (h.yzw - 0.5) * 0.6;
		float d = length(p - center);
		float magnitude = pow(hash13(cell + 17.0), 6.0);
		float star = (1.0 - smoothstep(0.0, 0.3, d)) * (0.05 + magnitude * 2.0);
		// Scintillation: turbulent air makes stars twinkle, most strongly near the horizon.
		star *= 1.0 + 0.4 * (1.0 - dir.y) * sin(frameTimeCounter * (6.0 + 10.0 * h.z) + h.w * 60.0);
		// Colour from blue-white hot stars to orange cool ones.
		stars = mix(vec3(1.0, 0.72, 0.45), vec3(0.75, 0.85, 1.0), h.y) * star * 0.02;
	}
	vec3 band = mix(vec3(0.75, 0.8, 1.0), vec3(1.0, 0.88, 0.72), core) * galaxy * 0.0012;

	vec3 extinction = atmosphereTransmittance(dir.y, hazeAmount());
	return (stars + band) * night * extinction * (1.0 - rainStrength) * STAR_BRIGHTNESS * smoothstep(0.0, 0.05, dir.y);
}

// Faint green glow of the upper atmosphere (oxygen at 557.7 nm), strongest near the horizon.
vec3 airglow(vec3 dir, vec3 sunDir) {
	float horizon = pow(1.0 - clamp(dir.y, 0.0, 1.0), 4.0);
	return vec3(0.35, 1.0, 0.3) * 0.00012 * horizon * nightAmount(sunDir) * (1.0 - rainStrength) * NIGHT_BRIGHTNESS;
}

// Everything visible in an empty sky direction.
vec3 fullSky(vec3 dir, vec3 sunDir) {
	return skyRadiance(dir, sunDir) + sunDisk(dir, sunDir) + moonDisk(dir, sunDir) + starField(dir, sunDir) + airglow(dir, sunDir);
}
