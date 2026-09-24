// Temporal antialiasing. Every frame is drawn a fraction of a pixel off (see
// taaJitter), and the history of earlier frames, moved to where each point
// now is on screen, is blended with the new frame. Edges and thin plants
// resolve smoothly and the dither noise of clouds, fog and traced light
// averages out.
// Needs common. The including pass declares colortex0 (this frame),
// colortex10 (history) and depthtex0.

uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferPreviousProjection;
uniform vec3 previousCameraPosition;

// Invertible squeeze of HDR light, so a few very bright pixels cannot
// dominate the blend and leave trails.
vec3 taaCompress(vec3 color) {
	return color / (1.0 + max(color.r, max(color.g, color.b)));
}

vec3 taaExpand(vec3 color) {
	return color / max(1.0 - max(color.r, max(color.g, color.b)), 1e-3);
}

// Brightness and two colour differences: the neighbourhood box fits the
// colours of a scene more tightly in this space than in RGB.
vec3 rgbToYCoCg(vec3 c) {
	return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));
}

vec3 yCoCgToRgb(vec3 c) {
	return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Where a point on screen was in the previous frame.
vec2 previousScreenPos(vec2 uv, float depth) {
	vec3 playerPos = viewToPlayer(screenToView(uv, depth));
	vec4 clip;
	if (depth >= 1.0) {
		// The sky is infinitely far away: only turning the camera moves it.
		clip = gbufferPreviousProjection * vec4(mat3(gbufferPreviousModelView) * playerPos, 1.0);
	} else {
		vec3 previousPlayerPos = playerPos + cameraPosition - previousCameraPosition;
		clip = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(previousPlayerPos, 1.0));
	}
	return clip.xy / clip.w * 0.5 + 0.5;
}

// Catmull-Rom filtered history in five bilinear taps: keeps the history sharp
// where a plain bilinear lookup would blur it a little more every frame.
vec3 sampleHistory(vec2 uv) {
	vec2 size = vec2(viewWidth, viewHeight);
	vec2 position = uv * size;
	vec2 center = floor(position - 0.5) + 0.5;
	vec2 f = position - center;
	vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	vec2 w3 = f * f * (-0.5 + 0.5 * f);
	vec2 w12 = w1 + w2;
	vec2 uv0 = (center - 1.0) / size;
	vec2 uv3 = (center + 2.0) / size;
	vec2 uv12 = (center + w2 / w12) / size;
	vec3 sum = textureLod(colortex10, vec2(uv12.x, uv0.y), 0.0).rgb * (w12.x * w0.y)
		+ textureLod(colortex10, vec2(uv0.x, uv12.y), 0.0).rgb * (w0.x * w12.y)
		+ textureLod(colortex10, uv12, 0.0).rgb * (w12.x * w12.y)
		+ textureLod(colortex10, vec2(uv3.x, uv12.y), 0.0).rgb * (w3.x * w12.y)
		+ textureLod(colortex10, vec2(uv12.x, uv3.y), 0.0).rgb * (w12.x * w3.y);
	float weight = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	return sanitizeColor(sum / weight);
}

// Pulls a history colour that this pixel's neighbourhood could not produce
// (something moved or was uncovered) back to the edge of the box.
vec3 clipToBox(vec3 history, vec3 boxMin, vec3 boxMax) {
	vec3 center = 0.5 * (boxMax + boxMin);
	vec3 extent = 0.5 * (boxMax - boxMin) + 1e-5;
	vec3 offset = history - center;
	vec3 reach = abs(offset / extent);
	float outside = max(reach.x, max(reach.y, reach.z));
	return outside > 1.0 ? center + offset / outside : history;
}

// exposure: the eye's current exposure, so light is squeezed the way it
// will finally be seen.
vec3 temporalAntialias(float exposure) {
	vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
	vec3 current = vec3(0.0);
	vec3 sum = vec3(0.0);
	vec3 sumSquares = vec3(0.0);
	vec3 low = vec3(1e6);
	vec3 high = vec3(-1e6);
	float closestDepth = 2.0;
	vec2 closestUV = texcoord;
	for (int x = -1; x <= 1; x++) {
		for (int y = -1; y <= 1; y++) {
			vec2 uv = texcoord + vec2(x, y) * texel;
			vec3 c = rgbToYCoCg(taaCompress(textureLod(colortex0, uv, 0.0).rgb * exposure));
			if (x == 0 && y == 0) current = c;
			sum += c;
			sumSquares += c * c;
			low = min(low, c);
			high = max(high, c);
			// Motion is taken from the nearest surface around, so edges of
			// things in front move with them rather than with the background.
			float depth = texture(depthtex0, uv).r;
			if (depth < closestDepth) {
				closestDepth = depth;
				closestUV = uv;
			}
		}
	}

	// The hand moves with the camera and stays where it was on screen. Iris
	// draws it with its depth squeezed below 0.56, which nothing else reaches.
	vec2 velocity = closestDepth < 0.56 ? vec2(0.0) : previousScreenPos(closestUV, closestDepth) - closestUV;
	vec2 previousUV = texcoord + velocity;
	if (previousUV != clamp(previousUV, 0.0, 1.0)) return taaExpand(yCoCgToRgb(current)) / exposure;

	vec3 mean = sum / 9.0;
	vec3 spread = sqrt(max(sumSquares / 9.0 - mean * mean, 0.0));
	vec3 history = rgbToYCoCg(taaCompress(sampleHistory(previousUV) * exposure));
	history = clipToBox(history, max(low, mean - spread * 1.25), min(high, mean + spread * 1.25));

	// Resampled history blurs, so fast motion leans on the new frame more.
	float pixelsMoved = length(velocity / texel);
	float blend = mix(0.08, 0.3, clamp(pixelsMoved / 16.0, 0.0, 1.0));
	return taaExpand(yCoCgToRgb(mix(history, current, blend))) / exposure;
}
