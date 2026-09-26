package chihalu.mina.horror.terrain;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Smoothed ground, shared by the drawn slopes (client) and the collision (both sides) so the two always agree.
 *
 * <p>Steps up to three blocks in natural ground smooth automatically, without terrain-wand marks.
 * Every top corner of natural ground smooths
 * to a height shared by the four block columns meeting there: their average, where the tops lie within {@link #SPAN}
 * blocks of each other, but never lower than one block below the highest. Every column's uppermost side face then
 * keeps its height, and the side faces of a higher column always reach down to the lower column's top, so the
 * surface stays closed. Blocks whose tops meet at a corner search overlapping ranges that both hold all four tops,
 * so they agree on it, marked or not. Corners next to anything but open space and ground (buildings, trunks, paths),
 * or at cliffs higher than {@link #SPAN}, keep their square shape.
 */
public final class SmoothGround {
	/** Natural ground whose top corners are smoothed. */
	private static final Set<Block> GROUND = new HashSet<>();
	/** Small things standing on the ground, drawn following it. */
	private static final Set<Block> RESTING = new HashSet<>();
	private static final Set<Block> VEGETATED = Set.of(Blocks.GRASS_BLOCK, Blocks.MOSS_BLOCK);
	private static final String[] GROUND_NAMES = {
		"grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "mud", "clay", "moss_block",
		"stone", "granite", "diorite", "andesite", "tuff", "calcite", "deepslate", "dripstone_block",
		"sand", "red_sand", "suspicious_sand", "gravel", "suspicious_gravel", "sandstone", "red_sandstone", "snow_block",
		"terracotta", "netherrack", "soul_soil", "end_stone", "blackstone"
	};
	private static final String[] RESTING_NAMES = {
		"short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass",
		"dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
		"oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "wither_rose", "sunflower", "lilac", "rose_bush", "peony",
		"sweet_berry_bush", "pink_petals", "wildflowers", "leaf_litter", "moss_carpet", "snow", "brown_mushroom", "red_mushroom"
	};
	/** No usable ground height in a column. */
	private static final int NONE = Integer.MIN_VALUE;
	/** Largest height difference between the columns at a corner that is still smoothed; steeper cliffs stay square. */
	public static final int SPAN = 3;
	private static final int AUTO_SPAN = SPAN;
	/** Collision follows the slope in columns of this many per block side, in steps of 1/{@link #STEPS} block. */
	private static final int STEPS = 256;
	// Bound the denser collision meshes; terrain edits must not grow the cache forever.
	private static final Map<List<Integer>, VoxelShape> SHAPES = Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<List<Integer>, VoxelShape> entry) { return size() > 64; }
	});

	/**
	 * The smoothed top of the ground over one block: how far each top corner moves (x0z0, x1z0, x0z1, x1z1) and the
	 * slope's normal there, null where the corner keeps its place.
	 */
	public record Top(float[] offsets, float[][] normals) {
		/** Cubic patch: adjacent cells share both edge heights and edge slopes. */
		public float offsetAt(float x, float z) {
			x = Math.clamp(x, 0f, 1f);
			z = Math.clamp(z, 0f, 1f);
			float near = cubic(offsets[0], offsets[1], slope(0, 0), slope(1, 0), x);
			float far = cubic(offsets[2], offsets[3], slope(2, 0), slope(3, 0), x);
			return cubic(near, far, cubic(slope(0, 2), slope(1, 2), 0, 0, x),
				cubic(slope(2, 2), slope(3, 2), 0, 0, x), z);
		}

		private float slope(int corner, int axis) {
			float[] normal = normals[corner];
			return normal == null ? 0 : -normal[axis] / normal[1];
		}

		private static float cubic(float a, float b, float da, float db, float t) {
			return a + t * (da + t * (3 * (b-a) - 2*da - db + t * (2*(a-b) + da + db)));
		}

		public float[] normalAt(float x, float z) {
			float loX = Math.max(0, x-.001f), hiX = Math.min(1, x+.001f);
			float loZ = Math.max(0, z-.001f), hiZ = Math.min(1, z+.001f);
			float dx = (offsetAt(hiX,z)-offsetAt(loX,z))/(hiX-loX);
			float dz = (offsetAt(x,hiZ)-offsetAt(x,loZ))/(hiZ-loZ);
			float length = (float)Math.sqrt(dx*dx+1+dz*dz);
			return new float[] {-dx/length, 1/length, -dz/length};
		}

		/** Normal at the corner nearest a block-local point, or null. */
		public float @Nullable [] normalNear(float x, float z) {
			return normals[(x > 0.5f ? 1 : 0) + (z > 0.5f ? 2 : 0)];
		}
	}

	private SmoothGround() { }

	public static void init() {
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			if (!id.getNamespace().equals("minecraft")) continue;
			String name = id.getPath();
			if (name.endsWith("_ore") || name.endsWith("_terracotta") && !name.endsWith("glazed_terracotta")) GROUND.add(block);
			for (String ground : GROUND_NAMES) if (name.equals(ground)) GROUND.add(block);
			for (String resting : RESTING_NAMES) if (name.equals(resting)) RESTING.add(block);
		}
	}

	public static boolean isGroundBlock(Block block) {
		return GROUND.contains(block);
	}

	public static boolean isRestingBlock(Block block) {
		return RESTING.contains(block);
	}

	public static boolean canBlendWithVegetation(Block block) {
		return GROUND.contains(block) && !VEGETATED.contains(block);
	}

	public static boolean isVegetatedBlock(Block block) {
		return VEGETATED.contains(block);
	}

	/** Matches the photographic material slots in lib/realtex.glsl. */
	public static int surfaceMaterial(Block block) {
		if (VEGETATED.contains(block)) return 0;
		if (!GROUND.contains(block)) return -1;
		String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (name.equals("netherrack") || name.equals("soul_soil") || name.equals("end_stone")
				|| name.equals("blackstone")) return -1;
		if (name.equals("dirt") || name.equals("rooted_dirt") || name.equals("podzol")
				|| name.equals("mycelium") || name.equals("terracotta")
				|| name.endsWith("_terracotta")) return 1;
		if (name.equals("sand") || name.equals("red_sand") || name.equals("suspicious_sand")
				|| name.equals("sandstone") || name.equals("red_sandstone")) return 3;
		if (name.equals("gravel") || name.equals("suspicious_gravel")) return 4;
		if (name.equals("snow_block")) return 5;
		if (name.equals("deepslate")) return 6;
		if (name.equals("mud") || name.equals("coarse_dirt") || name.equals("clay")) return 7;
		return 2;
	}

	public static int materialPriority(int material) {
		return switch (material) {
			case 0 -> 8; // vegetation grows over soil and rock
			case 1 -> 7;
			case 5 -> 6;
			case 3 -> 5;
			case 7 -> 4;
			case 4 -> 3;
			case 2 -> 2;
			case 6 -> 1;
			default -> 0;
		};
	}

	public static boolean isGround(BlockState state) {
		return GROUND.contains(state.getBlock()) && state.isSolidRender();
	}

	/** Air, water and small plants leave room for the ground surface. */
	public static boolean isOpen(BlockState state) {
		return state.isAir() || state.canBeReplaced() || RESTING.contains(state.getBlock());
	}

	/**
	 * Height of the top of the ground in column (x, z), searched within {@link #SPAN} blocks of y, or {@link #NONE}
	 * where the local floor is obstructed or rises past the search. Detached ceilings are not sampled.
	 */
	static int groundTop(BlockGetter level, BlockPos.MutableBlockPos cursor, int x, int y, int z, int span) {
		// Follow the local floor upward only while it is continuous. A separate ceiling is not this floor.
		if (isGround(level.getBlockState(cursor.set(x, y - 1, z)))) {
			for (int dy = 0; dy <= span; dy++) {
				if (!isGround(level.getBlockState(cursor.set(x, y + dy, z)))) return y + dy;
			}
			return NONE;
		}
		for (int dy = -1; dy >= -span - 1; dy--) {
			BlockState state = level.getBlockState(cursor.set(x, y + dy, z));
			if (isGround(state)) return dy == span ? NONE : y + dy + 1;
			// Torches and other small things stand on the floor rather than hide it; seen from the column
			// they stand in they cover the ground, so the neighbours must find the same height beneath them.
			if (!isOpen(state) && state.isSolid()) return NONE;
		}
		return NONE;
	}

	/** A placed object can cover a surface; a continuous ground column cannot. */
	public static boolean isSurfaceAbove(BlockState above) {
		return !isGround(above);
	}

	/** Use the same local floor search for material boundaries and geometry, including beneath ceilings. */
	public static int surfaceMaterialAt(BlockGetter level, int x, int y, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int height = groundTop(level, cursor, x, y, z, AUTO_SPAN);
		return height == NONE ? -1 : surfaceMaterial(level.getBlockState(cursor.set(x, height - 1, z)).getBlock());
	}

	/** Smoothed top for the block column at (x, z) whose ground ends at height y, or null where nothing moves. */
	public static @Nullable Top top(BlockGetter level, SmoothColumns.Lookup marks, int x, int y, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[][] tops = new int[3][3];
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) tops[dx + 1][dz + 1] = groundTop(level, cursor, x + dx, y, z + dz, AUTO_SPAN);
		float[] offsets = new float[4];
		float[][] normals = new float[4][];
		boolean moved = false;
		for (int i = 0; i <= 1; i++) {
			for (int j = 0; j <= 1; j++) {
				int a = tops[i][j], b = tops[i + 1][j], c = tops[i][j + 1], d = tops[i + 1][j + 1];
				if (a == NONE || b == NONE || c == NONE || d == NONE) continue;
				int high = Math.max(Math.max(a, b), Math.max(c, d));
				int low = Math.min(Math.min(a, b), Math.min(c, d));
				if (high - low > AUTO_SPAN) continue;
				float height = Math.max((a + b + c + d) / 4f, high - 1);
				// Keep contact with blocks resting on this surface while allowing adjacent slopes to meet it.
				int[] cornerTops = {a, b, c, d};
				boolean supported = false;
				for (int k = 0; k < 4; k++) {
					BlockState cover = level.getBlockState(cursor.set(x + i - 1 + (k & 1), cornerTops[k], z + j - 1 + (k >> 1)));
					if (!isOpen(cover)) {
						height = Math.max(height, cornerTops[k]);
						supported = true;
					}
				}
				int corner = i + 2 * j;
				offsets[corner] = height - y;
				// Slope from the same four columns, so both sides of the corner shade alike. A one-block step
				// is spread over about two blocks once smoothed, hence a quarter rather than a half.
				float slopeX = (b + d - a - c) * 0.25f;
				float slopeZ = (c + d - a - b) * 0.25f;
				if (supported) { slopeX = 0; slopeZ = 0; }
				float length = (float) Math.sqrt(slopeX * slopeX + 1f + slopeZ * slopeZ);
				normals[corner] = new float[] {-slopeX / length, 1f / length, -slopeZ / length};
				moved |= offsets[corner] != 0f || slopeX != 0f || slopeZ != 0f;
			}
		}
		return moved ? new Top(offsets, normals) : null;
	}

	/** The level behind a getter handed to a block's shape methods: the level itself, or one of its chunks. */
	private static @Nullable Level levelOf(BlockGetter getter) {
		if (getter instanceof Level level) return level;
		if (getter instanceof LevelChunk chunk) return chunk.getLevel();
		return null;
	}

	/**
	 * Collision and outline of a surface ground block next to a step: columns reaching up to the slope where
	 * it lies within the block, or null where the block keeps its cube. Where the slope rises above the block, the open
	 * blocks over it collide with the rest ({@link #fillFor}).
	 */
	public static @Nullable VoxelShape shapeFor(BlockState state, BlockGetter getter, BlockPos pos) {
		if (!GROUND.contains(state.getBlock())) return null;
		Level level = levelOf(getter);
		if (level == null) return null;
		SmoothColumns.Lookup marks = new SmoothColumns.Lookup(level);
		if (!state.isSolidRender() || !isSurfaceAbove(level.getBlockState(pos.above()))) return null;
		Top top = top(level, marks, pos.getX(), pos.getY() + 1, pos.getZ());
		return top == null ? null : layerShape(top, 0);
	}

	/**
	 * Collision of an open block (air, plants, snow layers) standing up to {@link #SPAN} blocks over smoothed ground
	 * whose slope rises into it: the part of the slope inside it, or null where there is none. Kept in the open blocks
	 * rather than the ground so every shape stays within its block, where entities standing over it find it.
	 */
	public static @Nullable VoxelShape fillFor(BlockState state, BlockGetter getter, BlockPos pos) {
		if (!isOpen(state)) return null;
		Level level = levelOf(getter);
		if (level == null) return null;
		SmoothColumns.Lookup marks = new SmoothColumns.Lookup(level);
		BlockPos.MutableBlockPos cursor = pos.mutable();
		for (int layer = 1; layer <= SPAN; layer++) {
			if (layer > AUTO_SPAN && !marks.anyAround(pos.getX(), pos.getZ())) return null;
			BlockState below = level.getBlockState(cursor.move(0, -1, 0));
			if (isGround(below)) {
				Top top = top(level, marks, pos.getX(), cursor.getY() + 1, pos.getZ());
				return top == null ? null : layerShape(top, layer);
			}
			if (!isOpen(below)) return null;
		}
		return null;
	}

	/**
	 * The part of the smoothed ground inside the block {@code layer} blocks above the ground block, as columns rounded
	 * to 1/{@link #STEPS} block; null where it fills that block (the ground block) or misses it (an open block).
	 * Shared between equal slopes.
	 */
	static @Nullable VoxelShape layerShape(Top top, int layer) {
		final int cells = TerrainDetail.collisionCells;
		int[] heights = new int[cells * cells];
		boolean full = true, empty = true;
		for (int i = 0; i < cells; i++) {
			for (int j = 0; j < cells; j++) {
				float height = 1f + top.offsetAt((i + 0.5f) / cells, (j + 0.5f) / cells);
				int steps = Math.round(height * STEPS) - layer * STEPS;
				// The ground block keeps at least a sliver, so nothing falls through where the slope dips to its bottom.
				steps = Math.clamp(steps, layer == 0 ? 1 : 0, STEPS);
				heights[i * cells + j] = steps;
				full &= steps == STEPS;
				empty &= steps == 0;
			}
		}
		if (layer == 0 ? full : empty) return null;
		List<Integer> key = Arrays.stream(heights).boxed().toList();
		VoxelShape cached = SHAPES.get(key);
		if (cached != null) return cached;
		// Fill the native grid once, without repeated unions/optimizations or holding the cache lock.
		int[] levels = IntStream.concat(IntStream.of(0), Arrays.stream(heights)).distinct().sorted().toArray();
		DoubleList horizontal = DoubleArrayList.wrap(IntStream.rangeClosed(0, cells).mapToDouble(i -> (double)i / cells).toArray());
		DoubleList vertical = DoubleArrayList.wrap(Arrays.stream(levels).mapToDouble(i -> (double)i / STEPS).toArray());
		BitSetDiscreteVoxelShape grid = new BitSetDiscreteVoxelShape(cells, levels.length - 1, cells);
		for (int x = 0; x < cells; x++) for (int z = 0; z < cells; z++) {
			int height = Arrays.binarySearch(levels, heights[x * cells + z]);
			for (int y = 0; y < height; y++) grid.fill(x, y, z);
		}
		VoxelShape result = new VoxelShape(grid) {
			@Override public DoubleList getCoords(Direction.Axis axis) {
				return axis == Direction.Axis.Y ? vertical : horizontal;
			}
		};
		SHAPES.put(key, result);
		return result;
	}
}
