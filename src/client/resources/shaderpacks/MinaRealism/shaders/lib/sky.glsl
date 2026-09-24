// Procedural sun, moon and stars replacing the vanilla sprites.
// Needs shadow.glsl (sunPathRotation) and atmosphere.glsl.

const float MOON_ANGULAR_RADIUS = 0.011;
const float MOON_DISK_RADIANCE = 1.2;

// Frame that turns with the sky: the sun path lies in the plane normal to the axis.
mat3 celestialFrame(vec3 sunDir) {
	float tilt = radians(sunPathRotation);
	vec3 axis = vec3(0.0, sin(tilt), cos(tilt));
	return mat3(sunDir, cross(axis, sunDir), axis);
}

vec3 moonDisk(vec3 dir, vec3 sunDir) {
	vec3 moonDir = -sunDir;
	float cosAngle = dot(dir, moonDir);
	if (cosAngle < cos(MOON_ANGULAR_RADIUS)) return vec3(0.0);
	vec3 right = normalize(cross(moonDir, vec3(0.0, 0.0, 1.0)));
	vec3 up = cross(right, moonDir);
	vec2 q = vec2(dot(dir, right), dot(dir, up)) / sin(MOON_ANGULAR_RADIUS);
	float r2 = dot(q, q);
	if (r2 >= 1.0) return vec3(0.0);
	vec3 n = vec3(q, sqrt(1.0 - r2));

	// Phase 0 is full (lit from behind the viewer), 4 is new (lit from behind the moon).
	float phaseAngle = float(moonPhase) * (PI / 4.0);
	vec3 toSun = vec3(sin(phaseAngle), 0.0, cos(phaseAngle));
	float mu0 = dot(n, toSun);
	// Lommel-Seeliger: the regolith looks evenly bright right up to the limb.
	float lit = mu0 > 0.0 ? 2.0 * mu0 / (mu0 + n.z) : 0.0;
	lit *= smoothstep(-0.02, 0.06, mu0);

	// Dark maria, bright highlands and a few craters.
	float maria = smoothstep(0.42, 0.62, texture(noisetex, q * 0.22 + vec2(0.31, 0.57)).g);
	float craters = texture(noisetex, q * 0.6 + vec2(0.13, 0.71)).b;
	float albedo = mix(1.0, 0.55, maria) * mix(0.85, 1.05, craters);

	float earthshine = 0.015;
	float edge = 1.0 - smoothstep(0.9, 1.0, sqrt(r2));
	vec3 transmittance = atmosphereTransmittance(moonDir.y, hazeAmount());
	return MOON_DISK_RADIANCE * NIGHT_BRIGHTNESS * MOON_TINT * albedo * (lit + earthshine) * edge * transmittance
		* (1.0 - rainStrength) * smoothstep(-0.01, 0.01, dir.y);
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
