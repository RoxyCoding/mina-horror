// Screen-space ray marching against a depth buffer: the "pseudo ray tracing"
// behind indirect light, reflections and contact shadows.

// Marches from viewPos along viewDir for up to maxDistance blocks.
// Returns the hit screen position (uv, depth) in xyz and 1 in w on a hit.
// Stepping is linear in screen space, where depth interpolates exactly.
vec4 traceScreen(sampler2D depthTex, vec3 viewPos, vec3 viewDir, float maxDistance, int steps, float dither, float thickness) {
	// Stop the ray just before it would cross the camera plane.
	float rayLength = maxDistance;
	if (viewDir.z > 0.0) rayLength = min(rayLength, (-near * 1.01 - viewPos.z) / viewDir.z);
	if (rayLength <= 0.0) return vec4(0.0);

	vec3 start = viewToScreen(viewPos);
	vec3 end = viewToScreen(viewPos + viewDir * rayLength);
	vec3 delta = (end - start) / float(steps);
	vec3 pos = start + delta * dither;
	vec3 previous = start;

	for (int i = 0; i < steps; i++) {
		if (pos.x < 0.0 || pos.x > 1.0 || pos.y < 0.0 || pos.y > 1.0) return vec4(0.0);
		float sceneDepth = texture(depthTex, pos.xy).r;
		if (pos.z > sceneDepth && sceneDepth < 1.0) {
			float behind = linearDepth(pos.z) - linearDepth(sceneDepth);
			if (behind < thickness * max(1.0, linearDepth(sceneDepth) * 0.05)) {
				// Refine between the last miss and this hit.
				vec3 lo = previous;
				vec3 hi = pos;
				for (int j = 0; j < 4; j++) {
					vec3 mid = (lo + hi) * 0.5;
					if (mid.z > texture(depthTex, mid.xy).r) hi = mid; else lo = mid;
				}
				return vec4(hi, 1.0);
			}
		}
		previous = pos;
		pos += delta;
	}
	return vec4(0.0);
}

// Fades out hits near the screen border, where the depth buffer runs out.
float screenEdgeFade(vec2 uv) {
	vec2 edge = min(uv, 1.0 - uv);
	return smoothstep(0.0, 0.06, min(edge.x, edge.y));
}
