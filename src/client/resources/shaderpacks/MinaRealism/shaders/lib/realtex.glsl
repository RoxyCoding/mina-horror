// Photographic ground materials, scanned from real surfaces (Poly Haven,
// CC0; tools/generate_real_textures.py). They are laid out in world space at
// their real size, so they run on across block edges instead of repeating
// once per block, and a second, turned copy blended in by a slow noise hides
// the repeat of the scan itself.
// Needs settings and common. For the terrain fragment stage.

uniform sampler2D realalbedo;  // Colour, sRGB. 1024 texels per material.
uniform sampler2D realsurface; // Tangent-space normal (OpenGL) and roughness. 512 texels per material.

const int REAL_GRASS = 0;
const int REAL_DIRT = 1;
const int REAL_STONE = 2;
const int REAL_SAND = 3;
const int REAL_GRAVEL = 4;
const int REAL_SNOW = 5;
const int REAL_DEEPSLATE = 6;
const int REAL_MUD = 7;

// Blocks covered by one copy of each scan: the size of the scanned patch.
const float REAL_SIZE[8] = float[](2.0, 3.15, 1.8, 1.5, 2.0, 2.0, 2.0, 1.3);

// Atlas layout, as written by the generator: materials in two columns, each
// cell holding the mip chain with a one-texel wrapped border per level.
const vec2 REAL_ALBEDO_ATLAS = vec2(3080.0, 4104.0);
const vec2 REAL_ALBEDO_CELL = vec2(1540.0, 1026.0);
const vec2 REAL_SURFACE_ATLAS = vec2(1544.0, 2064.0);
const vec2 REAL_SURFACE_CELL = vec2(772.0, 516.0);
const float REAL_LEVELS = 6.0;

// One mip level: level 0 at the left of the cell, the rest stacked to its right.
vec4 realLevel(sampler2D atlas, vec2 atlasSize, vec2 cellSize, float base, int material, vec2 uv, float level) {
	float size = base / exp2(level);
	vec2 cellOrigin = vec2(float(material % 2), float(material / 2)) * cellSize;
	vec2 origin = level < 0.5 ? vec2(1.0) : vec2(base + 3.0, 1.0 + base * (1.0 - exp2(1.0 - level)) + 2.0 * (level - 1.0));
	return textureLod(atlas, (cellOrigin + origin + fract(uv) * size) / atlasSize, 0.0);
}

// Trilinear between the two mip levels around lod.
vec4 realTexture(sampler2D atlas, vec2 atlasSize, vec2 cellSize, float base, int material, vec2 uv, float lod) {
	lod = clamp(lod, 0.0, REAL_LEVELS);
	float l0 = floor(lod);
	vec4 a = realLevel(atlas, atlasSize, cellSize, base, material, uv, l0);
	if (lod - l0 < 0.01) return a;
	return mix(a, realLevel(atlas, atlasSize, cellSize, base, material, uv, min(l0 + 1.0, REAL_LEVELS)), lod - l0);
}

// Blocks covered by one screen pixel at a point seen at a slant.
float realFootprint(vec3 playerPos, vec3 faceNormal) {
	float pixelAngle = 2.0 / (gbufferProjection[1][1] * viewHeight);
	float slant = max(abs(dot(normalize(playerPos), faceNormal)), 0.2);
	return length(playerPos) * pixelAngle / slant;
}

// Colour (sRGB) and bent normal of one scan at a world position, on a face
// with the given axis-aligned normal. rotation turns the scan, scale
// shrinks it: the second copy used against repetition.
//   footprint  blocks covered by one screen pixel on this face
void realLayer(int material, vec3 worldPos, vec3 faceNormal, float footprint, float rotation, float scale, vec2 offset,
		out vec3 albedo, out vec3 normal) {
	// Texture axes on the face: T along +u, B along the image's up (-v).
	vec3 a = abs(faceNormal);
	vec3 T;
	vec3 B;
	if (a.y >= a.x && a.y >= a.z) {
		T = vec3(1.0, 0.0, 0.0);
		B = vec3(0.0, 0.0, -sign(faceNormal.y));
	} else if (a.x >= a.z) {
		T = vec3(0.0, 0.0, -sign(faceNormal.x));
		B = vec3(0.0, 1.0, 0.0);
	} else {
		T = vec3(sign(faceNormal.z), 0.0, 0.0);
		B = vec3(0.0, 1.0, 0.0);
	}
	float c = cos(rotation);
	float s = sin(rotation);
	vec3 T2 = c * T + s * B;
	vec3 B2 = -s * T + c * B;
	float size = REAL_SIZE[material] * scale;
	vec2 uv = vec2(dot(worldPos, T2), -dot(worldPos, B2)) / size + offset;
	// Mip level from the pixel's footprint rather than screen derivatives,
	// which jump at triangle edges and would draw seams along block edges.
	float lod = log2(max(footprint / size * 1024.0, 1e-6));

	albedo = realTexture(realalbedo, REAL_ALBEDO_ATLAS, REAL_ALBEDO_CELL, 1024.0, material, uv, lod).rgb;
	vec4 surface = realTexture(realsurface, REAL_SURFACE_ATLAS, REAL_SURFACE_CELL, 512.0, material, uv, lod - 1.0);
	vec3 n = surface.xyz * 2.0 - 1.0;
	n.xy *= REAL_NORMAL_STRENGTH;
	normal = normalize(T2 * n.x + B2 * n.y + faceNormal * n.z);
}

// The scan at a point, with its repetition broken up: two copies at
// different angles and sizes, and patches of slightly lighter and darker
// ground over tens of blocks.
void realMaterial(int material, vec3 worldPos, vec3 faceNormal, float footprint, out vec3 albedo, out vec3 normal) {
	vec3 albedoA;
	vec3 normalA;
	vec3 albedoB;
	vec3 normalB;
	realLayer(material, worldPos, faceNormal, footprint, 0.0, 1.0, vec2(0.0), albedoA, normalA);
	realLayer(material, worldPos, faceNormal, footprint, 2.1, 1.37, vec2(0.37, 0.61), albedoB, normalB);
	vec2 macroUV = (worldPos.xz + worldPos.y * vec2(0.37, 0.71)) * 0.021;
	float blend = smoothstep(0.35, 0.65, texture(noisetex, macroUV).g);
	albedo = mix(albedoA, albedoB, blend) * mix(0.9, 1.08, texture(noisetex, macroUV * 0.31 + 0.5).g);
	normal = normalize(mix(normalA, normalB, blend));
}

// Material shown by a block id from block.properties, or -1 for none. The
// grass block is handled by its caller: grass on top, soil on its sides.
int realMaterialOf(int id) {
	if (id == ID_REAL_GRASS_BLOCK || id == ID_REAL_SNOWY_GRASS_BLOCK) return REAL_DIRT;
	if (id == ID_REAL_DIRT) return REAL_DIRT;
	if (id == ID_REAL_STONE) return REAL_STONE;
	if (id == ID_REAL_SAND) return REAL_SAND;
	if (id == ID_REAL_GRAVEL) return REAL_GRAVEL;
	if (id == ID_REAL_SNOW) return REAL_SNOW;
	if (id == ID_REAL_DEEPSLATE) return REAL_DEEPSLATE;
	if (id == ID_REAL_MUD) return REAL_MUD;
	return -1;
}

// Colour (sRGB) and normal of a real-material block. vertexTint is the
// biome colour vanilla gives grass, white on untinted faces.
// footprint: blocks covered by one screen pixel on this face.
void realBlockSurface(int id, vec3 worldPos, vec3 faceNormal, float footprint, vec3 vertexTint, out vec3 albedo, out vec3 normal) {
	int material = realMaterialOf(id);
	realMaterial(material, worldPos, faceNormal, footprint, albedo, normal);
	bool grassBlock = id == ID_REAL_GRASS_BLOCK || id == ID_REAL_SNOWY_GRASS_BLOCK;
	if (!grassBlock || faceNormal.y < -0.5) return;

	// Grass covers the top, and hangs a ragged fringe over the upper edge of
	// the sides; snow does the same on snowy grass.
	int cover = id == ID_REAL_SNOWY_GRASS_BLOCK ? REAL_SNOW : REAL_GRASS;
	float coverage = 1.0;
	if (faceNormal.y < 0.5) {
		float heightInBlock = worldPos.y - floor(worldPos.y - 1e-3);
		float along = dot(worldPos.xz, abs(faceNormal.zx));
		float fringe = 0.1 + 0.12 * texture(noisetex, vec2(along * 0.35, worldPos.y * 0.05)).g + 0.05 * texture(noisetex, vec2(along * 2.1, 0.3)).b;
		coverage = smoothstep(1.0 - fringe - 0.02, 1.0 - fringe + 0.02, heightInBlock);
		if (coverage <= 0.0) return;
	}
	vec3 coverAlbedo;
	vec3 coverNormal;
	realMaterial(cover, worldPos, faceNormal, footprint, coverAlbedo, coverNormal);
	if (cover == REAL_GRASS) {
		// Biome colour relative to plains grass, which the scan resembles.
		bool tinted = any(lessThan(vertexTint, vec3(0.99)));
		if (tinted) coverAlbedo *= mix(vec3(1.0), clamp(vertexTint / vec3(0.569, 0.741, 0.349), 0.4, 1.8), 0.7);
	}
	albedo = mix(albedo, coverAlbedo, coverage);
	normal = normalize(mix(normal, coverNormal, coverage));
}
