// Photographic ground materials, scanned from real surfaces (Poly Haven,
// CC0; tools/generate_real_textures.py). They are laid out in world space at
// their real size, so they run on across block edges instead of repeating
// once per block, and a second, turned copy blended in by a slow noise hides
// the repeat of the scan itself.
// Needs settings and common. For the terrain fragment stage.

uniform sampler2D realalbedo;  // Colour, sRGB. 1024 texels per material.
uniform sampler2D realsurface; // Tangent-space normal (OpenGL) and roughness. 512 texels per material.
uniform sampler2D leaflitter;

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
void realProjection(int material, vec3 worldPos, vec3 faceNormal, vec3 T, vec3 B, float footprint, float rotation, float scale, vec2 offset,
		out vec3 albedo, out vec3 normal) {
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
	vec3 detail = T2 * n.x + B2 * n.y;
	detail -= faceNormal * dot(detail, faceNormal);
	normal = normalize(detail + faceNormal * max(n.z, 0.1));
}

// Blend projections continuously; hard axis selection draws seams across sloping triangles.
void realLayer(int material, vec3 worldPos, vec3 faceNormal, float footprint, float rotation, float scale, vec2 offset,
		out vec3 albedo, out vec3 normal) {
	vec3 weights = pow(abs(faceNormal), vec3(4.0));
	weights /= max(weights.x + weights.y + weights.z, 1e-6);
	vec3 s = mix(vec3(-1.0), vec3(1.0), greaterThanEqual(faceNormal, vec3(0.0)));
	albedo = vec3(0.0);
	normal = vec3(0.0);
	vec3 color, bump;
	if (weights.x > 0.0) {
		realProjection(material, worldPos, faceNormal, vec3(0,0,-s.x), vec3(0,1,0), footprint, rotation, scale, offset, color, bump);
		albedo += color * weights.x; normal += bump * weights.x;
	}
	if (weights.y > 0.0) {
		realProjection(material, worldPos, faceNormal, vec3(1,0,0), vec3(0,0,-s.y), footprint, rotation, scale, offset, color, bump);
		albedo += color * weights.y; normal += bump * weights.y;
	}
	if (weights.z > 0.0) {
		realProjection(material, worldPos, faceNormal, vec3(s.z,0,0), vec3(0,1,0), footprint, rotation, scale, offset, color, bump);
		albedo += color * weights.z; normal += bump * weights.z;
	}
	normal = normalize(normal);
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
}

// Interpolated material weights, never interpolated material identifiers.
void realGroundPalette(vec3 worldPos, vec3 faceNormal, float footprint, vec4 weightsA, vec4 weightsB,
        inout vec3 albedo, inout vec3 normal) {
    if (dot(weightsA + weightsB, vec4(1.0)) < 0.001) return;
    vec3 mixedColor = vec3(0.0), mixedNormal = vec3(0.0);
    float sum = 0.0;
    for (int material=0; material<8; material++) {
        float weight = material < 4 ? weightsA[material] : weightsB[material-4];
        if (weight <= 0.0) continue;
        float noise = texture(noisetex, worldPos.xz * 1.3 + vec2(float(material)*0.137)).g;
        weight *= mix(0.65, 1.35, noise);
        vec3 color, bump;
        realMaterial(material, worldPos, faceNormal, footprint, color, bump);
        mixedColor += color * weight;
        mixedNormal += bump * weight;
        sum += weight;
    }
    albedo = mixedColor / sum;
    normal = normalize(mixedNormal);
}
// Scatter leaves in continuous world space instead of restarting the pattern
// in every block model. Rotated, differently scaled copies obscure repetition.
vec4 realLeafLitter(vec3 worldPos) {
	vec2 p = worldPos.xz;
	vec2 uvA = p / 3.2;
	vec4 a = textureGrad(leaflitter, fract(uvA), dFdx(uvA), dFdy(uvA));
	float c = cos(0.83);
	float s = sin(0.83);
	vec2 rotated = mat2(c, -s, s, c) * p;
	vec2 uvB = rotated / 4.7 + vec2(0.37, 0.61);
	vec4 b = textureGrad(leaflitter, fract(uvB), dFdx(uvB), dFdy(uvB));
	float blend = smoothstep(0.3, 0.7, texture(noisetex, p * 0.035).g);
	float weightA = a.a * mix(0.48, 0.72, blend);
	float weightB = b.a * mix(0.72, 0.48, blend);
	float alpha = weightA + weightB * (1.0 - weightA);
	vec3 color = (a.rgb * weightA * (1.0 - weightB) + b.rgb * weightB) / max(alpha, 1e-4);
	return vec4(color, alpha);
}
