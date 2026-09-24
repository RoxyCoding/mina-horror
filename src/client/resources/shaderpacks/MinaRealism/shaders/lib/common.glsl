// Include order for every program: settings, common, shadow, atmosphere,
// clouds, sky, raytrace, lighting. Each library is included at most once.

const float PI = 3.14159265359;

// Block ids from block.properties.
const int ID_WATER = 10;
const int ID_LEAVES = 20;        // Leaves and plants take 20-23 (see block.properties).
const int ID_ROOTED_PLANT = 21;
const int ID_TALL_PLANT_TOP = 22;
const int ID_STIFF_PLANT = 23;
const int ID_EMISSIVE = 30;
const int ID_GLASS = 40;
const int ID_REAL_GRASS_BLOCK = 50;  // 50-58: photographic ground (lib/realtex.glsl).
const int ID_REAL_DIRT = 51;
const int ID_REAL_STONE = 52;
const int ID_REAL_SAND = 53;
const int ID_REAL_GRAVEL = 54;
const int ID_REAL_SNOW = 55;
const int ID_REAL_DEEPSLATE = 56;
const int ID_REAL_MUD = 57;
const int ID_REAL_SNOWY_GRASS_BLOCK = 58;

// Surface models, stored in colortex3.g for the deferred and composite passes.
// G-buffer layout (alpha is always coverage, so translucent fragments blend
// instead of wiping out what lies behind them):
//   colortex1  albedo (sRGB)
//   colortex2  normal (octahedral xy), block light
//   colortex3  vanilla AO, material (+64 when already lit), sky light
const int MAT_DEFAULT = 0;
const int MAT_FOLIAGE = 1;
const int MAT_FLAT = 2;     // Particles, rain and lines: no reliable normal.
const int MAT_EMISSIVE = 3;
const int MAT_WATER = 4;
const int MAT_GLASS = 5;
const int MAT_ENTITY = 6;

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform vec3 sunPosition;
uniform vec3 shadowLightPosition;
uniform vec3 cameraPosition;
uniform vec3 fogColor;
uniform float rainStrength;
uniform float thunderStrength;
uniform float wetness;
uniform float frameTime;
uniform float frameTimeCounter;
uniform int frameCounter;
uniform float viewWidth;
uniform float viewHeight;
uniform float near;
uniform float far;
uniform float nightVision;
uniform float blindness;
uniform float darknessFactor;
uniform int isEyeInWater;
uniform int moonPhase;
uniform ivec2 eyeBrightnessSmooth;
uniform bool hasSkylight;

// Tileable noise (tex/noise.png): r = Perlin-Worley, g = smooth fbm, b = cells.
uniform sampler2D noisetex;

bool isFoliageId(int id) {
	return id >= ID_LEAVES && id <= ID_STIFF_PLANT;
}

float luminance(vec3 color) {
	return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec3 srgbToLinear(vec3 color) {
	return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)), step(0.04045, color));
}

vec3 linearToSrgb(vec3 color) {
	return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, color));
}

// Spreads neighbouring pixels evenly over [0,1); a 3x3 blur averages it out.
// With TAA the pattern also moves every frame, so the history averages it
// over time as well.
float interleavedGradientNoise(vec2 pixel) {
#ifdef TAA
	pixel += 5.588238 * float(frameCounter % 64);
#endif
	return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// This frame's sub-pixel offset for TAA, in pixels: a Halton (2, 3) sequence
// over 8 frames covers the pixel evenly.
vec2 taaJitter() {
	const vec2 OFFSETS[8] = vec2[](
		vec2(0.5, 0.333333), vec2(0.25, 0.666667), vec2(0.75, 0.111111), vec2(0.125, 0.444444),
		vec2(0.625, 0.777778), vec2(0.375, 0.222222), vec2(0.875, 0.555556), vec2(0.0625, 0.888889));
	return OFFSETS[frameCounter % 8] - 0.5;
}

float hash13(vec3 p) {
	p = fract(p * 0.1031);
	p += dot(p, p.zyx + 31.32);
	return fract((p.x + p.y) * p.z);
}

vec4 hash43(vec3 p) {
	vec4 q = fract(vec4(p.xyzx) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
	q += dot(q, q.wzxy + 33.33);
	return fract((q.xxyz + q.yzzw) * q.zywx);
}

// The scene buffers are 16-bit floats, which overflow to infinity above 65504.
// One NaN or infinite pixel spreads through the mip chain used for glare and
// exposure and shows up as a large black square, so every write of light
// goes through this.
vec3 sanitizeColor(vec3 color) {
	if (any(isnan(color)) || any(isinf(color))) return vec3(0.0);
	return clamp(color, 0.0, 60000.0);
}

vec3 projectAndDivide(mat4 matrix, vec3 position) {
	vec4 homogeneous = matrix * vec4(position, 1.0);
	return homogeneous.xyz / homogeneous.w;
}

vec3 screenToView(vec2 uv, float depth) {
	return projectAndDivide(gbufferProjectionInverse, vec3(uv, depth) * 2.0 - 1.0);
}

vec3 viewToScreen(vec3 viewPos) {
	return projectAndDivide(gbufferProjection, viewPos) * 0.5 + 0.5;
}

// Distance along the view axis for a depth buffer value.
float linearDepth(float depth) {
	vec2 zw = gbufferProjectionInverse[2].zw * (depth * 2.0 - 1.0) + gbufferProjectionInverse[3].zw;
	return -zw.x / zw.y;
}

vec3 viewToPlayer(vec3 viewPos) {
	return mat3(gbufferModelViewInverse) * viewPos + gbufferModelViewInverse[3].xyz;
}

vec3 worldSunDir() {
	return normalize(mat3(gbufferModelViewInverse) * sunPosition);
}

vec3 worldLightDir() {
	return normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
}

// Sky light reaching the camera, 0 in caves and 1 outdoors.
float eyeSkyExposure() {
	return clamp(float(eyeBrightnessSmooth.y) / 240.0, 0.0, 1.0);
}

// Octahedral normal encoding into [0,1]^2.
vec2 encodeNormal(vec3 n) {
	n /= abs(n.x) + abs(n.y) + abs(n.z);
	vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
	return e * 0.5 + 0.5;
}

vec3 decodeNormal(vec2 e) {
	e = e * 2.0 - 1.0;
	vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
	float t = max(-n.z, 0.0);
	n.xy += vec2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
	return normalize(n);
}

const int MATERIAL_PRELIT = 64;

float encodeMaterial(int material, bool prelit) {
	return float(material + (prelit ? MATERIAL_PRELIT : 0)) / 255.0;
}

int decodeMaterial(float value) {
	return int(value * 255.0 + 0.5) % MATERIAL_PRELIT;
}

// Surfaces lit in their own gbuffers program, which deferred1 must leave alone.
bool isPrelit(float value) {
	return int(value * 255.0 + 0.5) >= MATERIAL_PRELIT;
}

// Cosine-weighted direction around n.
vec3 cosineHemisphere(vec3 n, vec2 xi) {
	float phi = 6.2831853 * xi.x;
	float r = sqrt(xi.y);
	vec3 tangent = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
	vec3 bitangent = cross(n, tangent);
	return normalize(tangent * (r * cos(phi)) + bitangent * (r * sin(phi)) + n * sqrt(max(1.0 - xi.y, 0.0)));
}

// Water optics per block. Pure water absorbs red within a few blocks; suspended
// particles scatter all colours and make lakes look turbid rather than glassy.
const vec3 WATER_ABSORPTION = vec3(0.35, 0.07, 0.04);
const float WATER_SCATTERING = 0.14;
// Sunlight going down loses a little more than pure absorption to back-scattering.
const vec3 WATER_DOWNWELLING = WATER_ABSORPTION + 0.03;

// Light focused by surface waves onto things under water. Averages to 1.
// surfacePos is where the light entered the water, so walls are not streaked.
float waterCaustics(vec3 surfacePos, float depth) {
	vec2 p = surfacePos.xz * 0.11;
	float t = frameTimeCounter;
	float a = texture(noisetex, p + t * vec2(0.021, 0.013)).g;
	float b = texture(noisetex, p * 1.37 + 0.43 + t * vec2(-0.017, 0.019)).g;
	float lines = pow(clamp(1.0 - abs(a - b) * 5.0, 0.0, 1.0), 8.0);
	float caustic = 0.6 + lines * 5.85; // lines averages 0.068.
	// Just below the surface the pattern has not focused yet; deep down it blurs out.
	return mix(1.0, caustic, smoothstep(0.3, 2.0, depth) * exp(-depth * 0.12));
}

// Blocks between the eye and the water surface above it, estimated from how
// much sky light reaches the eye (water dims it by one level per block).
float eyeWaterDepth() {
	return max(240.0 - float(eyeBrightnessSmooth.y), 0.0) / 16.0;
}

// Standing water on flat ground open to the sky. Puddles grow as rain goes on
// (wetness rises slowly) and cover about a fifth of the ground at most.
float puddleAmount(vec3 worldPos, vec3 normal, float skyLight) {
	// Bumpy tops tilt a little (see TEXTURE_BUMPS); walls and undersides never hold water.
	if (wetness <= 0.001 || normal.y < 0.8) return 0.0;
	float n = texture(noisetex, worldPos.xz * 0.012).g * 0.6 + texture(noisetex, worldPos.xz * 0.05 + 0.3).g * 0.4;
	float threshold = mix(0.75, 0.6, wetness);
	return smoothstep(threshold, threshold + 0.05, n) * smoothstep(0.85, 0.97, skyLight) * wetness;
}

// Rain soaks surfaces that face the open sky.
// Tops soak fully, walls catch driven rain, undersides stay dry; puddles are
// a film of water on top.
float surfaceWetness(vec3 normal, float skyLight, int material, vec3 worldPos) {
	if (material == MAT_FLAT || material == MAT_EMISSIVE) return 0.0;
	float exposure = normal.y < -0.3 ? 0.0 : mix(0.4, 1.0, clamp(normal.y * 1.5, 0.0, 1.0));
	float wet = wetness * smoothstep(0.85, 0.97, skyLight) * exposure;
	if (material == MAT_DEFAULT) wet = max(wet, puddleAmount(worldPos, normal, skyLight));
	return wet;
}

// Slope of rings spreading from raindrops hitting still water.
vec2 rainRippleSlope(vec2 p) {
	if (rainStrength <= 0.001) return vec2(0.0);
	p *= 1.5; // About two drops per block at a time.
	vec2 cell = floor(p);
	vec2 slope = vec2(0.0);
	for (int x = -1; x <= 1; x++) {
		for (int y = -1; y <= 1; y++) {
			vec2 c = cell + vec2(x, y);
			vec4 h = hash43(vec3(c, 11.0));
			float age = fract(frameTimeCounter * 1.3 + h.z);
			vec2 d = p - (c + h.xy);
			float r = max(length(d), 1e-3);
			float ring = r - age;
			// A short wave train on the expanding ring, fading as it spreads.
			float wave = cos(ring * 30.0) * exp(-ring * ring * 60.0) * (1.0 - age) * (1.0 - age);
			slope += d / r * wave;
		}
	}
	return slope * 0.3 * rainStrength;
}

// Puddle on a lit G-buffer pixel (colortex1 and colortex3 values). Replaces the
// normal with the rippled water surface where there is one.
float gbufferPuddle(vec4 albedoData, vec4 surfaceData, vec3 worldPos, inout vec3 normal) {
	if (albedoData.a < 0.5 || isPrelit(surfaceData.g) || decodeMaterial(surfaceData.g) != MAT_DEFAULT) return 0.0;
	float puddle = puddleAmount(worldPos, normal, surfaceData.b);
	if (puddle > 0.0) {
		vec2 slope = rainRippleSlope(worldPos.xz);
		normal = normalize(vec3(-slope.x, 1.0, -slope.y));
	}
	return puddle;
}
