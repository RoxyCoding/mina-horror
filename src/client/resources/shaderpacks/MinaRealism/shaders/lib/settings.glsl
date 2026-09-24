// 0 Low, 1 Medium, 2 High. The profiles in shaders.properties also set shadow resolution.
#define QUALITY 1 // [0 1 2]

// Screen-space ray tracing: indirect bounce light and occlusion, reflections, contact shadows.
#define PSEUDO_RT

// Surfaces
#define TEXTURE_BUMPS
#define BUMP_STRENGTH 1.0 // [0.5 0.75 1.0 1.5 2.0 3.0]

// Lighting
#define SHADOW_SOFTNESS 1.0 // [0.5 0.75 1.0 1.5 2.0 3.0]
#define SUN_BRIGHTNESS 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]
#define AMBIENT_BRIGHTNESS 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]
#define TORCH_BRIGHTNESS 1.0 // [0.5 0.75 1.0 1.5 2.0 3.0]
#define EMISSIVE_BRIGHTNESS 1.0 // [0.0 0.5 1.0 1.5 2.0 3.0]
#define NIGHT_BRIGHTNESS 1.0 // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]

// Sky
#define SKY_BRIGHTNESS 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]
#define STAR_BRIGHTNESS 1.0 // [0.0 0.5 1.0 2.0 4.0]
#define MOON_PHASE_BRIGHTNESS
#define VOLUMETRIC_CLOUDS
#define CLOUD_COVERAGE 0.45 // [0.2 0.3 0.4 0.45 0.5 0.6 0.7 0.8]

// Wind
#define WAVING_PLANTS
#define WIND_STRENGTH 1.0 // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]

// Water
#define WATER_REFRACTION

// Rain
#define RAIN_AMOUNT 1.0 // [0.5 0.75 1.0 1.5 2.0]
#define LENS_DROPS

// Fog
#define VOLUMETRIC_LIGHT
#define VL_STRENGTH 1.0 // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]
#define FOG_DENSITY 1.0 // [0.0 0.5 1.0 1.5 2.0 3.0]

// Camera
#define TAA
#define LENS_VIGNETTE
#define CHROMATIC_ABERRATION
#define SENSOR_NOISE
#define AUTO_EXPOSURE
#define BLOOM
#define BLOOM_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0 3.0]
#define EXPOSURE_BIAS 0.0 // [-2.0 -1.5 -1.0 -0.5 0.0 0.5 1.0 1.5 2.0]

#if QUALITY == 0
	const int SHADOW_SAMPLES = 6;
	const int VL_STEPS = 6;
	const int CLOUD_STEPS = 10;
	const int CLOUD_LIGHT_STEPS = 2;
	const int GI_RAYS = 1;
	const int GI_STEPS = 8;
	const float GI_DISTANCE = 3.0;
	const int SSR_STEPS = 12;
	const int DENOISE_RADIUS = 1;
	const int CLOUD_SCATTER_OCTAVES = 1;
#elif QUALITY == 1
	const int SHADOW_SAMPLES = 12;
	const int VL_STEPS = 10;
	const int CLOUD_STEPS = 18;
	const int CLOUD_LIGHT_STEPS = 4;
	const int GI_RAYS = 2;
	const int GI_STEPS = 12;
	const float GI_DISTANCE = 5.0;
	const int SSR_STEPS = 24;
	const int DENOISE_RADIUS = 2;
	const int CLOUD_SCATTER_OCTAVES = 2;
#else
	const int SHADOW_SAMPLES = 20;
	const int VL_STEPS = 16;
	const int CLOUD_STEPS = 28;
	const int CLOUD_LIGHT_STEPS = 6;
	const int GI_RAYS = 4;
	const int GI_STEPS = 16;
	const float GI_DISTANCE = 8.0;
	const int SSR_STEPS = 40;
	const int DENOISE_RADIUS = 2;
	const int CLOUD_SCATTER_OCTAVES = 3;
#endif
