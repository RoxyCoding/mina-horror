// Shared fragment stage for the gbuffers programs. The entry file declares
// RENDERTARGETS and picks one mode:
//   GB_GBUFFER     opaque geometry: albedo, normal, light and material for deferred lighting
//                  (targets 0,1,2,3; colortex0 gets black so eye/glint overlays can add to it)
//   GB_WATER       translucent terrain, lit here; normal and material go to 2,3 for reflections
//   GB_EMISSIVE    unlit, the value is the brightness
//   GB_RAW         block cracks multiplied into the albedo buffer
//   (none)         forward-lit particles, hand, rain and lines
// Modifiers: GB_CHUNK (block ids, AO in vertex alpha), GB_SHADOWS, GB_FLAT,
// GB_ENTITY (hurt flash), GB_NO_TEXTURE, GB_PRELIT (G-buffer mode, but lit here),
// GB_WEATHER (rain streaks and snowflakes).

#define SHADOW_SAMPLING
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/shadow.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/lighting.glsl"
#include "/lib/rain.glsl"

uniform sampler2D gtexture;
uniform sampler2D depthtex1;
uniform vec4 entityColor;
// Iris passes vanilla's cutoff for the current render type: 0.5 for mipmapped
// cutouts, where distant grass and leaves would otherwise fill their whole quad.
uniform float alphaTestRef;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 vertexColor;
in vec3 viewPos;
in vec3 viewNormal;
flat in int blockId;
#ifdef GB_CHUNK
in vec4 tileRect;
#endif

layout(location = 0) out vec4 out0;
#if defined GB_GBUFFER || defined GB_WATER
layout(location = 1) out vec4 out1;
layout(location = 2) out vec4 out2;
#endif
#ifdef GB_GBUFFER
layout(location = 3) out vec4 out3;
#endif

#ifdef GB_WATER
// Sum of travelling waves plus drifting ripples from the noise texture.
vec3 waterWaveNormal(vec3 worldPos, float footprint, float roughness) {
	vec2 p = worldPos.xz;
	float t = frameTimeCounter;
	// footprint: blocks covered by one pixel. Waves shorter than a few pixels only
	// alias into sparkling noise, so they fade out with distance and at grazing angles.
	vec2 gradient = vec2(0.0);
	const vec4 wavelengths = vec4(7.1, 4.3, 2.3, 1.3);
	const vec4 angles = vec4(0.3, 1.9, 4.1, 2.7);
	for (int i = 0; i < 4; i++) {
		vec2 dir = vec2(cos(angles[i]), sin(angles[i]));
		float k = 6.2831853 / wavelengths[i];
		float phase = dot(dir, p) * k + t * sqrt(9.81 * k);
		float fade = smoothstep(footprint * 3.0, footprint * 10.0, wavelengths[i]);
		gradient += dir * (wavelengths[i] * 0.012 * k * cos(phase) * fade);
	}
	// Ripples from the smooth noise channel. Central differences over several texels
	// keep the 8-bit steps of the texture out of the normal.
	float rippleFade = 1.0 - smoothstep(0.04, 0.2, footprint);
	if (rippleFade > 0.0) {
		const float e = 3.0 / 256.0;
		const vec2 scales = vec2(0.07, 0.13);
		vec2 uvA = p * scales.x + t * vec2(0.013, 0.008);
		vec2 uvB = p * scales.y - t * vec2(0.011, 0.016);
		vec2 slopeA = vec2(texture(noisetex, uvA + vec2(e, 0.0)).g - texture(noisetex, uvA - vec2(e, 0.0)).g,
			texture(noisetex, uvA + vec2(0.0, e)).g - texture(noisetex, uvA - vec2(0.0, e)).g) / (2.0 * e) * scales.x;
		vec2 slopeB = vec2(texture(noisetex, uvB + vec2(e, 0.0)).g - texture(noisetex, uvB - vec2(e, 0.0)).g,
			texture(noisetex, uvB + vec2(0.0, e)).g - texture(noisetex, uvB - vec2(0.0, e)).g) / (2.0 * e) * scales.y;
		gradient += (slopeA * 0.25 + slopeB * 0.12) * rippleFade;
		// Rings from raindrops.
		gradient += rainRippleSlope(p) * rippleFade;
	}
	return normalize(vec3(-gradient.x * roughness, 1.0, -gradient.y * roughness));
}

// Light scattered inside the water body, with alpha from how much of the
// bottom the water hides. Suspended particles keep even shallow water from
// looking like glass; red is absorbed within a few blocks.
vec4 shadeWaterVolume(vec3 tint, vec3 playerPos, vec2 light, float dither) {
	vec2 uv = gl_FragCoord.xy / vec2(viewWidth, viewHeight);
	float bottomDepth = texture(depthtex1, uv).r;
	float thickness = 64.0;
	if (bottomDepth < 1.0) {
		float alongView = max(linearDepth(bottomDepth) - linearDepth(gl_FragCoord.z), 0.0);
		thickness = alongView * length(viewPos) / max(-viewPos.z, 1e-3);
	}
	vec3 transmittance = exp(-(WATER_ABSORPTION + WATER_SCATTERING) * thickness);
	float alpha = clamp(1.0 - dot(transmittance, vec3(0.25, 0.45, 0.3)), 0.12, 0.98);

	vec3 sunDir = worldSunDir();
	vec3 lightDir = worldLightDir();
	vec3 inLight = ambientIrradiance(vec3(0.0, 1.0, 0.0), sunDir, light.y) + blockLightIrradiance(light.x, playerPos + cameraPosition);
	if (hasSkylight) {
		float waterAbove;
		vec3 visibility = sampleShadow(playerPos, lightDir, dither, smoothstep(0.8, 0.97, light.y), waterAbove);
		inLight += directIlluminance(sunDir) * visibility * cloudShadow(playerPos, lightDir) * 0.35;
	}
	// The scattering happens throughout the column, where the light is dimmer.
	inLight *= exp(-WATER_DOWNWELLING * min(thickness, 8.0) * 0.5);
	// Deep water shows the colour of what it scattered, bluer where red is gone.
	vec3 waterAlbedo = tint * mix(vec3(0.02, 0.07, 0.09), vec3(0.15), transmittance);
	return vec4(waterAlbedo / PI * inLight, alpha);
}

// The surface seen from below: inside Snell's window (about 48.6 degrees from
// straight up) the sky shows through; outside it the surface mirrors the murk.
vec4 shadeWaterFromBelow(vec3 playerPos, vec3 normal) {
	vec3 viewDir = normalize(playerPos);
	vec3 n = dot(viewDir, normal) > 0.0 ? -normal : normal;
	vec3 murk = underwaterMurk(eyeWaterDepth());
	vec3 refracted = refract(viewDir, n, 1.333);
	if (dot(refracted, refracted) < 1e-6) return vec4(murk, 1.0);
	float cosTheta = clamp(dot(-viewDir, n), 0.0, 1.0);
	// Reflection rises steeply towards the edge of the window.
	float fresnel = 0.02 + 0.98 * pow(1.0 - sqrt(max(1.0 - 1.777 * (1.0 - cosTheta * cosTheta), 0.0)), 5.0);
	return vec4(murk, clamp(fresnel + 0.1, 0.0, 1.0));
}
#endif

#if defined GB_CHUNK && defined GB_GBUFFER
// Relief read from the texture: brighter texels are taken as raised, up to
// BUMP_DEPTH blocks. Gives stone, bark and soil a surface that catches light
// at a slant instead of looking painted on.
const float BUMP_DEPTH = 0.03 * BUMP_STRENGTH;

float bumpHeight(vec2 uv, vec2 dx, vec2 dy) {
	return luminance(textureGrad(gtexture, clamp(uv, tileRect.xy, tileRect.zw), dx, dy).rgb) * BUMP_DEPTH;
}

vec3 textureBumpNormal(vec3 normal, vec3 playerPos) {
	vec2 dx = dFdx(texcoord);
	vec2 dy = dFdy(texcoord);
	vec3 dpx = dFdx(playerPos);
	vec3 dpy = dFdy(playerPos);
	// World-space gradients of the texture coordinates across the face.
	vec3 dpyPerp = cross(dpy, normal);
	vec3 dpxPerp = cross(normal, dpx);
	float det = dot(dpx, dpyPerp);
	if (abs(det) < 1e-12) return normal;
	vec3 gradU = (dpyPerp * dx.x + dpxPerp * dy.x) / det;
	vec3 gradV = (dpyPerp * dx.y + dpxPerp * dy.y) / det;

	// Central differences over two texels up close and a pixel's footprint
	// further away, read from a mip level blurrier than the colour: the grain
	// of a texture is colour, only its larger shapes are relief. Distant
	// relief averages out instead of shimmering.
	vec2 atlas = vec2(textureSize(gtexture, 0));
	vec2 delta = max(2.0 / atlas, vec2(max(abs(dx.x), abs(dy.x)), max(abs(dx.y), abs(dy.y))));
	vec2 blurX = max(abs(dx), vec2(delta.x, 0.0)) * 2.0;
	vec2 blurY = max(abs(dy), vec2(0.0, delta.y)) * 2.0;
	float slopeU = (bumpHeight(texcoord + vec2(delta.x, 0.0), blurX, blurY) - bumpHeight(texcoord - vec2(delta.x, 0.0), blurX, blurY)) / (2.0 * delta.x);
	float slopeV = (bumpHeight(texcoord + vec2(0.0, delta.y), blurX, blurY) - bumpHeight(texcoord - vec2(0.0, delta.y), blurX, blurY)) / (2.0 * delta.y);
	vec3 gradient = slopeU * gradU + slopeV * gradV;
	// Keep the relief shallow (under about 35 degrees) and let it fade out
	// with distance, where a pixel covers many texels.
	gradient /= max(1.0, length(gradient) / 0.7);
	gradient *= 1.0 - smoothstep(24.0, 64.0, length(playerPos));
	return normalize(normal - gradient);
}
#endif

#ifdef GB_WEATHER
uniform int biome_precipitation; // 0 none, 1 rain, 2 snow
layout(location = 1) out vec4 out1; // colortex8: rain light (premultiplied) and coverage

// Near rain on the vanilla weather quads, which already stop at roofs. Only the
// streak coverage and the light the drops pick up are stored; composite2 turns
// them into refracting drops. The quads scroll their texture coordinates,
// which moves the streaks.
vec4 shadeRain() {
	const float LANES = 14.0; // Streak columns across one quad.
	vec2 coord = vec2(texcoord.x * LANES, texcoord.y * 1.3);
	float pixelWidth = max(fwidth(coord.x), 1e-4);
	float coverage = rainStreak(coord, pixelWidth, 1.7) * 0.6;
	if (coverage < 0.003) return vec4(0.0);

	// Drops are tiny lenses showing their surroundings; the ones in front of the
	// sun or moon light up.
	vec3 playerPos = viewToPlayer(viewPos);
	vec2 light = normalizeLightmap(lmcoord);
	vec3 sunDir = worldSunDir();
	vec3 surroundings = (ambientIrradiance(vec3(0.0, 1.0, 0.0), sunDir, light.y) + blockLightIrradiance(light.x, playerPos + cameraPosition)) / PI;
	float towardsLight = hasSkylight ? henyeyGreenstein(dot(normalize(playerPos), worldLightDir()), 0.8) : 0.0;
	vec3 glow = directIlluminance(sunDir) * towardsLight * 0.05 * smoothstep(0.8, 0.97, light.y);
	return vec4(surroundings * 0.4 + glow, coverage);
}
#endif

void main() {
#ifdef GB_NO_TEXTURE
	vec4 albedo = vertexColor;
	float ao = 1.0;
#elif defined GB_CHUNK
	vec4 tex = texture(gtexture, texcoord);
	vec4 albedo = vec4(tex.rgb * vertexColor.rgb, tex.a);
	float ao = vertexColor.a;
#else
	vec4 albedo = texture(gtexture, texcoord) * vertexColor;
	float ao = 1.0;
#endif

#ifdef GB_WEATHER
	out1 = vec4(0.0);
	if (biome_precipitation != 2) {
		out1 = shadeRain();
		if (out1.a <= 0.0) discard;
		out0 = vec4(0.0); // With blending, alpha 0 leaves the scene untouched.
		return;
	}
#endif

#ifdef GB_WATER
	if (albedo.a < 0.004) discard;
#else
	if (albedo.a < max(alphaTestRef, 0.1)) discard;
#endif

#ifdef GB_ENTITY
	albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
#endif

	vec3 normal = mat3(gbufferModelViewInverse) * viewNormal;
	float normalLength = length(normal);
	normal = normalLength > 1e-3 ? normal / normalLength : vec3(0.0, 1.0, 0.0);
	vec2 light = normalizeLightmap(lmcoord);
#ifdef GB_WATER
	// Derivatives must be taken outside the per-block branches below.
	vec3 surfaceWorldPos = viewToPlayer(viewPos) + cameraPosition;
	float footprint = max(length(dFdx(surfaceWorldPos)), length(dFdy(surfaceWorldPos)));
#endif

#if defined GB_CHUNK && defined GB_GBUFFER && defined TEXTURE_BUMPS
	// Derivatives are taken here, outside the per-material branches.
	vec3 bumpedNormal = textureBumpNormal(normal, viewToPlayer(viewPos));
	if (!isFoliageId(blockId)) normal = bumpedNormal;
#endif

#if defined GB_RAW
	out0 = vec4(albedo.rgb, 1.0);

#elif defined GB_EMISSIVE
	out0 = vec4(srgbToLinear(albedo.rgb) * GB_EMISSIVE, albedo.a);

#elif defined GB_GBUFFER
	int material = MAT_DEFAULT;
	#ifdef GB_ENTITY
	material = MAT_ENTITY;
	#endif
	if (isFoliageId(blockId)) material = MAT_FOLIAGE;
	else if (blockId == ID_EMISSIVE) material = MAT_EMISSIVE;
	#ifdef GB_PRELIT
	// Translucent entities (player skins) may be drawn after deferred, so they are lit
	// here and flagged in the material for deferred1 to keep as is.
	vec3 playerPos = viewToPlayer(viewPos);
	float dither = interleavedGradientNoise(gl_FragCoord.xy);
	vec3 lit = shadeSurface(srgbToLinear(albedo.rgb), playerPos, normal, light, ao, material, true, dither, vec4(0.0, 0.0, 0.0, 1.0), 1.0);
	out0 = vec4(lit, albedo.a);
	bool prelit = true;
	// Name tag backgrounds and other see-through parts must not replace the
	// surface behind them; with blending on, coverage 0 leaves it untouched.
	float coverage = albedo.a > 0.9 ? 1.0 : 0.0;
	#else
	out0 = vec4(0.0, 0.0, 0.0, 1.0);
	bool prelit = false;
	// Terrain is drawn without blending, so its coverage is written as is: the
	// soft alpha at leaf edges must still mark the pixel for deferred lighting.
	float coverage = 1.0;
	#endif
	out1 = vec4(albedo.rgb, coverage);
	out2 = vec4(encodeNormal(normal), light.x, coverage);
	out3 = vec4(ao, encodeMaterial(material, prelit), light.y, coverage);

#else
	vec3 linearAlbedo = srgbToLinear(albedo.rgb);
	vec3 playerPos = viewToPlayer(viewPos);
	float dither = interleavedGradientNoise(gl_FragCoord.xy);
	int material = MAT_DEFAULT;
	#ifdef GB_FLAT
	material = MAT_FLAT;
	#endif
	#ifdef GB_SHADOWS
	bool useShadowMap = true;
	#else
	bool useShadowMap = false;
	#endif

	#ifdef GB_WATER
	int surface = MAT_DEFAULT;
	vec4 result;
	if (blockId == ID_WATER) {
		surface = MAT_WATER;
		// The underside of the surface faces down but carries the same waves.
		if (abs(normal.y) > 0.5) normal = waterWaveNormal(playerPos + cameraPosition, footprint, 1.0 + rainStrength) * sign(normal.y);
		if (isEyeInWater == 1) result = shadeWaterFromBelow(playerPos, normal);
		else result = shadeWaterVolume(srgbToLinear(vertexColor.rgb), playerPos, light, dither);
	} else {
		if (blockId == ID_GLASS) surface = MAT_GLASS;
		result = vec4(shadeSurface(linearAlbedo, playerPos, normal, light, ao, MAT_DEFAULT, true, dither, vec4(0.0, 0.0, 0.0, 1.0), 1.0), albedo.a);
	}
	out0 = result;
	out1 = vec4(encodeNormal(normal), light.x, 1.0);
	// Water keeps its opacity in place of AO, which nothing reads for it:
	// composite1 needs it to refract the scene behind.
	out2 = vec4(surface == MAT_WATER ? result.a : ao, encodeMaterial(surface, false), light.y, 1.0);
	#elif defined GB_WEATHER
	// Snowflakes: small white scatterers lit like any matte surface.
	vec3 snow = shadeSurface(vec3(0.8), playerPos, normal, light, 1.0, MAT_FLAT, useShadowMap, dither, vec4(0.0, 0.0, 0.0, 1.0), 1.0);
	out0 = vec4(snow, albedo.a);
	#else
	vec3 color = shadeSurface(linearAlbedo, playerPos, normal, light, ao, material, useShadowMap, dither, vec4(0.0, 0.0, 0.0, 1.0), 1.0);
	out0 = vec4(color, albedo.a);
	#endif
#endif
}
