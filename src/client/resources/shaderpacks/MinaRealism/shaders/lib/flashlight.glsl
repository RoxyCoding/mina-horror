// The player's flashlight (mina-horror:flashlight, switched on): a cool-white LED spot thrown
// from the hand towards what the player looks at. Iris reports held items by their item model;
// the mod swaps in mina-horror:flashlight_on while it is on (item.properties).
// Needs common and settings.
#ifndef FLASHLIGHT_GLSL
#define FLASHLIGHT_GLSL

uniform int heldItemId;
uniform int heldItemId2;
uniform vec3 eyePosition;
uniform vec3 playerLookVector;

const int ITEM_FLASHLIGHT_ON = 1001;
// Cool white next to the torches; auto exposure keeps it reading as a daylight LED.
const vec3 FLASHLIGHT_COLOR = vec3(0.86, 0.93, 1.0);
// The yellowish corona LEDs leave at the edge of the spill.
const vec3 FLASHLIGHT_CORONA = vec3(1.0, 0.9, 0.72);
// Candela of the hotspot in the pack's units: about a torch at arm's length, 8 blocks away.
const float FLASHLIGHT_INTENSITY = 120.0;
// Blocks ahead where the beam crosses the line of sight.
const float FLASHLIGHT_AIM = 12.0;

// Beam shadowing at the surface being shaded; deferred1 traces it on screen before lighting.
float flashlightVisibility = 1.0;

// hand 0 is the main hand (held on the right), 1 the off hand.
bool flashlightInHand(int hand) {
	return (hand == 0 ? heldItemId : heldItemId2) == ITEM_FLASHLIGHT_ON;
}

bool anyFlashlight() {
	return flashlightInHand(0) || flashlightInHand(1);
}

// Lens position, relative to the camera, of the flashlight in the given hand.
vec3 flashlightOrigin(int hand) {
	vec3 look = normalize(playerLookVector);
	vec3 right = normalize(cross(look, vec3(0.0, 1.0, 0.0)) + vec3(1e-4, 0.0, 0.0));
	vec3 up = cross(right, look);
	float side = hand == 0 ? 1.0 : -1.0;
	return eyePosition - cameraPosition + right * (0.3 * side) - up * 0.25 + look * 0.4;
}

// Light reaching pos (relative to the camera) from the flashlight in one hand, on a surface
// facing the lamp; toLight gets the direction towards the lamp. The profile is that of a deep
// smooth reflector: a tight hotspot, a faint ring at its rim and the wide spill of light that
// leaves the LED without touching the reflector.
vec3 flashlightLight(vec3 pos, int hand, out vec3 toLight) {
	vec3 origin = flashlightOrigin(hand);
	vec3 aim = eyePosition - cameraPosition + normalize(playerLookVector) * FLASHLIGHT_AIM;
	vec3 axis = normalize(aim - origin);
	vec3 offset = pos - origin;
	float dist2 = max(dot(offset, offset), 1e-6);
	vec3 dir = offset * inversesqrt(dist2);
	toLight = -dir;

	float angle = acos(clamp(dot(dir, axis), -1.0, 1.0));
	float hotspot = exp(-angle * angle / (0.07 * 0.07));
	float ring = 0.04 * exp(-pow((angle - 0.15) / 0.03, 2.0));
	float spill = 0.06 * smoothstep(0.45, 0.3, angle);
	vec3 color = mix(FLASHLIGHT_COLOR, FLASHLIGHT_CORONA, smoothstep(0.12, 0.4, angle) * 0.6);
	return color * (hotspot + ring + spill) * FLASHLIGHT_INTENSITY * FLASHLIGHT_BRIGHTNESS / (dist2 + 0.25);
}

#endif
