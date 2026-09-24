// Single scattering in an Earth-like atmosphere, solved analytically so it is
// cheap enough to evaluate per pixel for both the sky and ambient light.
// Optical depths are at the zenith (scattering coefficient x scale height).
const vec3 RAYLEIGH_DEPTH = vec3(0.0464, 0.1080, 0.2648); // 5.8/13.5/33.1e-6 per m, 8 km
const float MIE_DEPTH = 0.0210;                           // 21e-6 per m, 1 km
const float MIE_EXTINCTION = 1.11;
const vec3 OZONE_DEPTH = vec3(0.0098, 0.0282, 0.0013);    // Chappuis band keeps twilight zenith blue
const float MIE_G = 0.76;

// Radiometric scale shared by the sky and surface lighting. Real moonlight is
// about 1/400000 of sunlight; this keeps nights dark but readable once the eye adapts.
const float SUN_ILLUMINANCE = 8.0;
const float MOON_ILLUMINANCE = 0.05 * NIGHT_BRIGHTNESS;
const vec3 MOON_TINT = vec3(0.72, 0.85, 1.0);
const float SKY_MULTIPLE_SCATTERING = 3.0;
const vec3 NIGHT_SKY_RADIANCE = vec3(0.00015, 0.00022, 0.00045) * NIGHT_BRIGHTNESS; // Airglow and starlight.
// The real sun: 0.53 degrees across, bright enough that its disc carries
// all of SUN_ILLUMINANCE. The screen buffers cap it, which still leaves it
// blinding next to the sky.
const float SUN_ANGULAR_RADIUS = 0.00465 * SUN_MOON_SIZE;
// Limb darkening, 1 - u (1 - mu): towards its edge we see cooler, higher gas,
// dimmer and redder (Neckel and Labs).
const vec3 SUN_LIMB_DARKENING = vec3(0.56, 0.65, 0.78);

float airMass(float mu) {
	// Kasten & Young (1989); only defined down to the horizon.
	mu = clamp(mu, 0.0, 1.0);
	float zenithDegrees = degrees(acos(mu));
	return 1.0 / (mu + 0.50572 * pow(96.07995 - zenithDegrees, -1.6364));
}

float hazeAmount() {
	return 1.0 + rainStrength * 6.0;
}

vec3 atmosphereExtinction(float haze) {
	return RAYLEIGH_DEPTH + MIE_DEPTH * MIE_EXTINCTION * haze + OZONE_DEPTH;
}

vec3 atmosphereTransmittance(float mu, float haze) {
	return exp(-atmosphereExtinction(haze) * airMass(mu));
}

float rayleighPhase(float cosTheta) {
	return 3.0 / (16.0 * PI) * (1.0 + cosTheta * cosTheta);
}

float henyeyGreenstein(float cosTheta, float g) {
	float g2 = g * g;
	return (1.0 - g2) / (4.0 * PI * pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1e-4), 1.5));
}

vec3 inScatter(vec3 dir, vec3 lightDir, vec3 illuminance, float haze) {
	float mu = max(dir.y, 0.0);
	float cosTheta = dot(dir, lightDir);
	vec3 extinction = atmosphereExtinction(haze);
	vec3 scattering = RAYLEIGH_DEPTH * rayleighPhase(cosTheta) + MIE_DEPTH * haze * henyeyGreenstein(cosTheta, MIE_G);
	vec3 viewOpacity = 1.0 - exp(-extinction * airMass(mu));
	// Air high above sees a much shorter sunlight path than air at the horizon,
	// but ozone sits high up and filters both.
	float lightMass = airMass(lightDir.y);
	vec3 scatterDepth = (RAYLEIGH_DEPTH + MIE_DEPTH * MIE_EXTINCTION * haze) * mix(1.0, 0.15, sqrt(mu));
	vec3 lightTransmittance = exp(-(scatterDepth + OZONE_DEPTH) * lightMass);
	return illuminance * scattering / extinction * viewOpacity * lightTransmittance;
}

float twilightFade(float elevation) {
	return smoothstep(-0.15, 0.02, elevation);
}

float moonBrightness() {
#ifdef MOON_PHASE_BRIGHTNESS
	// moonPhase 0 is full, 4 is new.
	return mix(0.12, 1.0, abs(float(moonPhase) - 4.0) / 4.0);
#else
	return 1.0;
#endif
}

// Sun or moon light above the weather, before clouds dim it.
vec3 celestialIlluminance(vec3 sunDir) {
	float haze = hazeAmount();
	vec3 sun = SUN_ILLUMINANCE * atmosphereTransmittance(sunDir.y, haze) * smoothstep(-0.02, 0.06, sunDir.y);
	vec3 moon = MOON_ILLUMINANCE * moonBrightness() * MOON_TINT * atmosphereTransmittance(-sunDir.y, haze) * smoothstep(-0.02, 0.06, -sunDir.y);
	return (sun + moon) * SUN_BRIGHTNESS;
}

// Direct light arriving at the ground. Overcast skies block most of it.
vec3 directIlluminance(vec3 sunDir) {
	return celestialIlluminance(sunDir) * (1.0 - 0.92 * rainStrength);
}

vec3 skyRadiance(vec3 dir, vec3 sunDir) {
	float haze = hazeAmount();
	vec3 d = normalize(vec3(dir.x, max(dir.y, 0.0) + 1e-3, dir.z));
	vec3 sunLight = vec3(SUN_ILLUMINANCE * twilightFade(sunDir.y));
	vec3 moonLight = MOON_ILLUMINANCE * moonBrightness() * MOON_TINT * twilightFade(-sunDir.y);
	vec3 sky = inScatter(d, sunDir, sunLight, haze) + inScatter(d, -sunDir, moonLight, haze);
	sky = sky * SKY_MULTIPLE_SCATTERING + NIGHT_SKY_RADIANCE;
	// Overcast skies lose almost all colour and dim.
	sky = mix(sky, vec3(luminance(sky) * 0.75), rainStrength * 0.85);
	// Below the horizon the ground and low haze hide the sky.
	sky *= mix(0.35, 1.0, smoothstep(-0.25, 0.0, dir.y));
	return sky * SKY_BRIGHTNESS;
}

// Where dir falls on a disc in the sky centred on center, in units of the
// disc's radius, with up and right given for the disc's own orientation.
// Near the horizon refraction lifts the lower edge more than the upper one,
// flattening the disc by up to a fifth.
vec2 skyDiscCoord(vec3 dir, vec3 center, float radius, vec3 right, vec3 up) {
	vec3 offset = dir - center;
	vec3 vertical = normalize(vec3(0.0, 1.0, 0.0) - center * center.y + vec3(0.0, 1e-5, 0.0));
	float flattening = mix(1.0, 0.82, exp(-max(center.y, 0.0) / 0.03));
	offset += vertical * dot(offset, vertical) * (1.0 / flattening - 1.0);
	return vec2(dot(offset, right), dot(offset, up)) / radius;
}

// Light from the sun or moon disc seen through the whole air column. Besides
// the clear-air extinction of the sky model, the haze and dust of the lowest
// kilometre dim it: the setting sun turns orange and can be looked at.
vec3 discTransmittance(float elevation) {
	return atmosphereTransmittance(elevation, hazeAmount()) * exp(-0.12 * airMass(elevation));
}

// Screen pixels are this many disc radii across: soft edges without aliasing.
float skyDiscPixel(float radius) {
	return 2.0 / (gbufferProjection[1][1] * viewHeight * radius);
}

vec3 sunDisk(vec3 dir, vec3 sunDir) {
	// Also rules out the antisolar point, which projects to the centre too.
	// The distance, unlike a dot product this close to 1, survives rounding.
	if (length(dir - sunDir) > SUN_ANGULAR_RADIUS * 1.5) return vec3(0.0);
	vec3 up = normalize(vec3(0.0, 1.0, 0.0) - sunDir * sunDir.y + vec3(0.0, 1e-5, 0.0));
	vec3 right = cross(sunDir, up);
	float r = length(skyDiscCoord(dir, sunDir, SUN_ANGULAR_RADIUS, right, up));
	float pixel = skyDiscPixel(SUN_ANGULAR_RADIUS);
	float coverage = 1.0 - smoothstep(1.0 - pixel, 1.0 + pixel, r);
	if (coverage <= 0.0) return vec3(0.0);
	float mu = sqrt(max(1.0 - r * r, 0.0));
	vec3 limb = (1.0 - SUN_LIMB_DARKENING * (1.0 - mu)) / (1.0 - SUN_LIMB_DARKENING / 3.0);
	float radiance = SUN_ILLUMINANCE / (PI * SUN_ANGULAR_RADIUS * SUN_ANGULAR_RADIUS);
	// The lower edge sits in denser air and turns redder at sunset.
	vec3 transmittance = discTransmittance(dir.y);
	return radiance * limb * transmittance * coverage * (1.0 - rainStrength) * smoothstep(-0.002, 0.002, dir.y);
}

// Irradiance on a surface from the visible part of the sky, plus light
// bounced off the ground for surfaces that face down.
vec3 skyIrradiance(vec3 normal, vec3 sunDir) {
	float horizontalLength = length(normal.xz);
	vec2 azimuth = horizontalLength > 1e-3 ? normal.xz / horizontalLength : vec2(0.0, 1.0);
	vec3 slanted = normalize(vec3(azimuth.x, 0.6, azimuth.y));
	vec3 sky = skyRadiance(slanted, sunDir) * 0.6 + skyRadiance(vec3(0.0, 1.0, 0.0), sunDir) * 0.4;
	// Light bounced between terrain and clouds is much less blue than the sky itself.
	sky = mix(sky, vec3(luminance(sky)), 0.5);
	float skyView = 0.5 + 0.5 * normal.y;
	vec3 groundAlbedo = vec3(0.16, 0.17, 0.12);
	vec3 ground = groundAlbedo / PI * (directIlluminance(sunDir) * max(worldLightDir().y, 0.0) + PI * sky);
	return PI * (sky * skyView + ground * (1.0 - skyView)) * AMBIENT_BRIGHTNESS;
}

// Radiance of open water seen from inside it: daylight filtered down to the
// given depth and scattered by suspended particles. fogColor is the biome's
// water fog colour while the camera is under water.
vec3 underwaterMurk(float depth) {
	vec3 sunDir = worldSunDir();
	vec3 downwelling = hasSkylight
		? directIlluminance(sunDir) * max(worldLightDir().y, 0.0) + skyIrradiance(vec3(0.0, 1.0, 0.0), sunDir)
		: vec3(0.05);
	downwelling *= exp(-WATER_DOWNWELLING * depth);
	vec3 tint = srgbToLinear(fogColor);
	tint /= max(max(tint.r, max(tint.g, tint.b)), 1e-3);
	// The physics already turns the murk blue-green; the biome colour only nudges it.
	tint = mix(vec3(1.0), tint, 0.4);
	vec3 scatteringAlbedo = WATER_SCATTERING / (WATER_ABSORPTION + WATER_SCATTERING);
	// Particles scatter mostly forwards, so little of it comes back sideways.
	return tint * scatteringAlbedo * downwelling / (4.0 * PI) * 0.15;
}
