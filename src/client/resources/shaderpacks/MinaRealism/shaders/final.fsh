#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

// Mip levels of the scene feed the glare around bright lights.
const bool colortex0MipmapEnabled = true;

uniform sampler2D colortex0;
uniform sampler2D colortex5;

in vec2 texcoord;

layout(location = 0) out vec4 fragColor;

// Narkowicz's fit of the ACES filmic curve.
vec3 acesFilm(vec3 x) {
	return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Light scattered inside the eye and lens: every bright source gets a soft halo
// that widens with distance. Built from progressively blurrier mip levels.
vec3 glare() {
	vec3 sum = vec3(0.0);
	float weightSum = 0.0;
	for (int i = 1; i <= 7; i++) {
		float lod = float(i);
		vec2 texel = exp2(lod) / vec2(viewWidth, viewHeight);
		// Four offset taps turn the blocky mip into a smooth tent filter.
		vec3 level = textureLod(colortex0, texcoord + texel * vec2(-0.5, -0.5), lod).rgb
			+ textureLod(colortex0, texcoord + texel * vec2(0.5, -0.5), lod).rgb
			+ textureLod(colortex0, texcoord + texel * vec2(-0.5, 0.5), lod).rgb
			+ textureLod(colortex0, texcoord + texel * vec2(0.5, 0.5), lod).rgb;
		float weight = 1.0 / lod;
		sum += level * 0.25 * weight;
		weightSum += weight;
	}
	return sum / weightSum;
}

// A real lens focuses colours slightly differently: towards the edges of the
// picture red and blue fringes separate a little from green.
vec3 sceneColor() {
#ifdef CHROMATIC_ABERRATION
	vec2 fromCenter = texcoord - 0.5;
	vec2 shift = fromCenter * dot(fromCenter, fromCenter) * 0.006;
	return vec3(texture(colortex0, texcoord - shift).r, texture(colortex0, texcoord).g, texture(colortex0, texcoord + shift).b);
#else
	return texture(colortex0, texcoord).rgb;
#endif
}

// Less light reaches the edges of the picture: an oblique bundle of rays
// meets the sensor spread out and at a slant (the cos^4 law), toned down the
// way camera lenses are designed to.
float lensVignette() {
	vec2 ndc = texcoord * 2.0 - 1.0;
	vec2 slope = ndc / vec2(gbufferProjection[0][0], gbufferProjection[1][1]);
	float cosTheta = inversesqrt(1.0 + dot(slope, slope));
	float cos2 = cosTheta * cosTheta;
	return mix(1.0, cos2 * cos2, 0.35);
}

void main() {
	vec3 color = sceneColor();
#ifdef LENS_VIGNETTE
	color *= lensVignette();
#endif
#ifdef BLOOM
	// Energy conserving: a small share of each pixel's light is spread around it.
	color = mix(color, glare(), 0.04 * BLOOM_STRENGTH);
#endif
	// In dim light the rods take over: colour fades and shifts towards blue.
	float rods = dot(color, vec3(0.05, 0.45, 0.5));
	float scotopic = 1.0 - smoothstep(0.0005, 0.02, luminance(color));
	color = mix(color, rods * vec3(0.6, 0.8, 1.1), scotopic * 0.75);
#ifdef AUTO_EXPOSURE
	float exposure = texelFetch(colortex5, ivec2(0), 0).r;
#else
	float exposure = 0.35;
#endif
	color *= exposure * exp2(EXPOSURE_BIAS);
	color = linearToSrgb(acesFilm(color));
#ifdef SENSOR_NOISE
	// Seeing in the dark means amplifying a weak signal, and the noise of the
	// sensor with it: grain rises with the exposure and shows most in the shadows.
	float gain = smoothstep(0.4, 6.0, exposure);
	if (gain > 0.0) {
		vec4 random = hash43(vec3(gl_FragCoord.xy, float(frameCounter % 1024)));
		float grain = random.x + random.y - 1.0;
		vec3 chroma = (random.zwx - 0.5) * 0.3;
		color += (grain + chroma) * gain * 0.05 * (1.0 - 0.7 * luminance(color));
	}
#endif
	// Breaks up banding in dark gradients.
	color += (interleavedGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
	fragColor = vec4(color, 1.0);
}
