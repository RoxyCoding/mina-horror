// Wind in leaves and plants, for the terrain and shadow vertex stages so that
// shadows sway with the plants that cast them.
// Needs settings and common.
//
// The motion is a smooth function of world position and time: vertices that
// neighbouring leaves share move together, so no gaps open between them.

// Towards +x and a little +z.
const vec2 WIND_DIRECTION = vec2(0.83, 0.55);

// How hard the wind blows at a point: a breeze that picks up in rain and
// storms, with gusts rolling across the land downwind.
float windGust(vec2 worldXZ) {
	vec2 uv = (worldXZ - WIND_DIRECTION * frameTimeCounter * 5.0) * 0.0035;
	float gust = smoothstep(0.25, 0.85, textureLod(noisetex, uv, 0.0).g);
	float weather = 0.7 + 0.9 * rainStrength + 0.9 * thunderStrength;
	return WIND_STRENGTH * weather * (0.35 + 0.65 * gust);
}

// Displacement in blocks.
//   bend     how far the point leans downwind at full wind
//   flutter  how far it trembles in every direction
vec3 windOffset(vec3 worldPos, float bend, float flutter) {
	float t = frameTimeCounter;
	float gust = windGust(worldPos.xz);
	// Leaning downwind and swinging back as waves of wind pass over.
	float swing = 0.55 + 0.45 * sin(dot(worldPos.xz, WIND_DIRECTION) * 0.45 - t * 1.9);
	vec3 offset = vec3(WIND_DIRECTION.x, 0.0, WIND_DIRECTION.y) * (gust * swing * bend);
	// Blades and leaves tremble on their own, out of step with each other.
	offset += sin(vec3(5.3, 4.1, 6.7) * t + worldPos.zxy * vec3(1.7, 2.3, 1.9) + worldPos.yzx * 1.1) * (gust * flutter);
	// A bent stem does not stretch: its tip dips as it leans.
	offset.y -= 0.6 * dot(offset.xz, offset.xz);
	return offset;
}

// topVertex: the vertex is on the upper edge of its texture, which for
// plants is the end away from the roots.
vec3 foliageWind(int id, vec3 worldPos, bool topVertex) {
	if (id == ID_LEAVES) return windOffset(worldPos, 0.04, 0.025);
	if (id == ID_ROOTED_PLANT) return topVertex ? windOffset(worldPos, 0.14, 0.03) : vec3(0.0);
	// The lower edge moves with the top of the lower half it stands on.
	if (id == ID_TALL_PLANT_TOP) return windOffset(worldPos, topVertex ? 0.28 : 0.14, 0.03);
	if (id == ID_STIFF_PLANT) return windOffset(worldPos, 0.015, 0.008);
	return vec3(0.0);
}
