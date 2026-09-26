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
// From the mod (FlashlightBeam): where each hand's lens was last drawn and where it points,
// in view space; w is 0 when unknown (no mod, hand hidden), then the lamp is guessed below.
uniform vec4 minaFlashlightPos0;
uniform vec4 minaFlashlightPos1;
uniform vec4 minaFlashlightDir0;
uniform vec4 minaFlashlightDir1;

const int ITEM_FLASHLIGHT_ON = 1001;
// Cool white next to the torches; auto exposure keeps it reading as a daylight LED.
const vec3 FLASHLIGHT_COLOR = vec3(0.86, 0.93, 1.0);
// The yellowish corona LEDs leave at the edge of the spill.
const vec3 FLASHLIGHT_CORONA = vec3(1.0, 0.9, 0.72);
// Candela at the beam centre in the pack's units: a torch about 4 blocks away, 8 blocks out.
const float FLASHLIGHT_INTENSITY = 30.0;
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
	vec4 lens = hand == 0 ? minaFlashlightPos0 : minaFlashlightPos1;
	if (lens.w > 0.5) return viewToPlayer(lens.xyz);
	// Guess: held in front of the chest on that hand's side.
	vec3 look = normalize(playerLookVector);
	vec3 right = normalize(cross(look, vec3(0.0, 1.0, 0.0)) + vec3(1e-4, 0.0, 0.0));
	vec3 up = cross(right, look);
	float side = hand == 0 ? 1.0 : -1.0;
	return eyePosition - cameraPosition + right * (0.3 * side) - up * 0.25 + look * 0.4;
}

// Light reaching pos (relative to the camera) from the flashlight in one hand, on a surface
// facing the lamp; toLight gets the direction towards the lamp. The beam is an even flood with no
// bright hotspot, fading out over its outer edge.
vec3 flashlightLight(vec3 pos, int hand, out vec3 toLight) {
	vec3 origin = flashlightOrigin(hand);
	vec4 pointing = hand == 0 ? minaFlashlightDir0 : minaFlashlightDir1;
	vec3 aim = eyePosition - cameraPosition + normalize(playerLookVector) * FLASHLIGHT_AIM;
	vec3 axis = pointing.w > 0.5 ? normalize(mat3(gbufferModelViewInverse) * pointing.xyz) : normalize(aim - origin);
	vec3 offset = pos - origin;
	float dist2 = max(dot(offset, offset), 1e-6);
	vec3 dir = offset * inversesqrt(dist2);
	toLight = -dir;

	float angle = acos(clamp(dot(dir, axis), -1.0, 1.0));
	float beam = smoothstep(0.45, 0.08, angle);
	vec3 color = mix(FLASHLIGHT_COLOR, FLASHLIGHT_CORONA, smoothstep(0.12, 0.4, angle) * 0.6);
	return color * beam * FLASHLIGHT_INTENSITY * FLASHLIGHT_BRIGHTNESS / (dist2 + 0.25);
}

#endif
