#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/flashlight.glsl"

// Eye adaptation: meters the scene and eases the exposure towards it.
// The result is kept across frames in colortex5: r exposure, g whether a flashlight was on.

const bool colortex0MipmapEnabled = true;

uniform sampler2D colortex0;
uniform sampler2D colortex5;

in vec2 texcoord;

/* RENDERTARGETS: 5 */
layout(location = 0) out vec4 outExposure;

const float MIDDLE_GREY = 0.18;
const float MIN_EXPOSURE = 0.05;
const float MAX_EXPOSURE = 6.0; // About 3 stops over daylight: nights stay dark like a real dark-adapted eye.

void main() {
	float lod = max(log2(max(viewWidth, viewHeight)) - 4.0, 0.0);
	float logSum = 0.0;
	float weightSum = 0.0;
	for (int x = 0; x < 7; x++) {
		for (int y = 0; y < 5; y++) {
			vec2 uv = (vec2(x, y) + 0.5) / vec2(7.0, 5.0);
			vec2 fromCenter = uv - 0.5;
			float weight = exp(-dot(fromCenter, fromCenter) * 4.0);
			float lum = luminance(textureLod(colortex0, uv, lod).rgb);
			logSum += log(max(lum, 1e-5)) * weight;
			weightSum += weight;
		}
	}
	float average = exp(logSum / weightSum);
	float target = clamp(MIDDLE_GREY / average, MIN_EXPOSURE, MAX_EXPOSURE);

	vec2 previousState = texelFetch(colortex5, ivec2(0), 0).rg;
	float previous = previousState.r;
	bool flashlight = anyFlashlight();
	// Switching a flashlight on in the dark would otherwise blow the scene out for a moment while the
	// night-adapted exposure catches up; meter the lit scene at once instead. Switching it off keeps
	// the slow adaptation to darkness.
	bool switchedOn = flashlight && previousState.g < 0.5;
	float exposure = target;
	if (previous > 0.0 && previous < 1e4 && !switchedOn) {
		// Eyes adjust to bright light faster than to darkness.
		float speed = target < previous ? 3.0 : 1.2;
		exposure = exp(mix(log(previous), log(target), 1.0 - exp(-frameTime * speed)));
	}
	outExposure = vec4(exposure, flashlight ? 1.0 : 0.0, 0.0, 1.0);
}
