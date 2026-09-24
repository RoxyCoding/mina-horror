package chihalu.mina.horror.terrain;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Smoothed ground, shared by the drawn slopes (client) and the collision (both sides) so the two always agree.
 *
 * <p>Inside the areas chosen with the terrain wand ({@link SmoothRegions}), every top corner of natural ground moves to
 * a height shared by the four block columns meeting there: their average, where the tops lie within {@link #SPAN}
 * blocks of each other, but never lower than one block below the highest. Every column's uppermost side face then
 * keeps its height, and the side faces of a higher column always reach down to the lower column's top, so the
 * surface stays closed. Blocks whose tops meet at a corner search overlapping ranges that both hold all four tops,
 * so they agree on it. Corners next to anything but open space and ground (buildings, trunks, paths), outside the
 * chosen areas, or at cliffs higher than {@link #SPAN}, keep their square shape.
 */
public final class SmoothGround {
	/** Natural ground whose top corners are smoothed. */
	private static final Set<Block> GROUND = new HashSet<>();
	/** Small things standing on the ground, drawn following it. */
	private static final Set<Block> RESTING = new HashSet<>();
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
	/** Collision follows the slope in columns of this many per block side, in steps of 1/{@link #STEPS} block. */
	private static final int CELLS = 4;
	private static final int STEPS = 8;
	/** Highest the collision reaches above its block, as with fences: a slope raised further is capped there. */
	private static final float MAX_RISE = 0.5f;
	private static final Map<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	/**
	 * The smoothed top of the ground over one block: how far each top corner moves (x0z0, x1z0, x0z1, x1z1) and the
	 * slope's normal there, null where the corner keeps its place.
	 */
	public record Top(float[] offsets, float[][] normals) {
		/** Offset at a block-local point, blended between the corners. */
		public float offsetAt(float x, float z) {
			x = Math.clamp(x, 0f, 1f);
			z = Math.clamp(z, 0f, 1f);
			float near = offsets[0] + (offsets[1] - offsets[0]) * x;
			float far = offsets[2] + (offsets[3] - offsets[2]) * x;
			return near + (far - near) * z;
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

	public static boolean isGround(BlockState state) {
		return GROUND.contains(state.getBlock()) && state.isSolidRender();
	}

	/** Air, water, plants, snow layers: nothing the ground would have to fit around. */
	public static boolean isOpen(BlockState state) {
		return state.isAir() || state.canBeReplaced() || RESTING.contains(state.getBlock());
	}

	/**
	 * Height of the top of the ground in column (x, z), searched within {@link #SPAN} blocks of y, or {@link #NONE}
	 * outside the chosen areas, where something other than open space and ground comes first, or where the ground
	 * rises past the search.
	 */
	private static int groundTop(BlockGetter level, SmoothRegions.Regions regions, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
		if (!regions.contains(x, z)) return NONE;
		for (int dy = SPAN; dy >= -SPAN - 1; dy--) {
			BlockState state = level.getBlockState(cursor.set(x, y + dy, z));
			if (isGround(state)) return dy == SPAN ? NONE : y + dy + 1;
			if (!isOpen(state)) return NONE;
		}
		return NONE;
	}

	/** Smoothed top for the block column at (x, z) whose ground ends at height y, or null where nothing moves. */
	public static @Nullable Top top(BlockGetter level, SmoothRegions.Regions regions, int x, int y, int z) {
		if (regions.isEmpty()) return null;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[][] tops = new int[3][3];
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) tops[dx + 1][dz + 1] = groundTop(level, regions, cursor, x + dx, y, z + dz);
		float[] offsets = new float[4];
		float[][] normals = new float[4][];
		boolean moved = false;
		for (int i = 0; i <= 1; i++) {
			for (int j = 0; j <= 1; j++) {
				int a = tops[i][j], b = tops[i + 1][j], c = tops[i][j + 1], d = tops[i + 1][j + 1];
				if (a == NONE || b == NONE || c == NONE || d == NONE) continue;
				int high = Math.max(Math.max(a, b), Math.max(c, d));
				int low = Math.min(Math.min(a, b), Math.min(c, d));
				if (high - low > SPAN) continue;
				float height = Math.max((a + b + c + d) / 4f, high - 1);
				int corner = i + 2 * j;
				offsets[corner] = height - y;
				// Slope from the same four columns, so both sides of the corner shade alike. A one-block step
				// is spread over about two blocks once smoothed, hence a quarter rather than a half.
				float slopeX = (b + d - a - c) * 0.25f;
				float slopeZ = (c + d - a - b) * 0.25f;
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
	 * Collision and outline of a ground block on the surface of a chosen area: columns rising to the slope, or null
	 * where the block keeps its cube.
	 */
	public static @Nullable VoxelShape shapeFor(BlockState state, BlockGetter getter, BlockPos pos) {
		if (!GROUND.contains(state.getBlock())) return null;
		Level level = levelOf(getter);
		if (level == null) return null;
		SmoothRegions.Regions regions = SmoothRegions.of(level);
		if (regions.isEmpty() || !regions.contains(pos.getX(), pos.getZ())) return null;
		if (!state.isSolidRender() || !isOpen(level.getBlockState(pos.above()))) return null;
		Top top = top(level, regions, pos.getX(), pos.getY() + 1, pos.getZ());
		return top == null ? null : slopeShape(top);
	}

	/** Columns reaching up to the smoothed top, rounded to 1/{@link #STEPS} block; shared between equal slopes. */
	static VoxelShape slopeShape(Top top) {
		long key = 0;
		int[] heights = new int[CELLS * CELLS];
		for (int i = 0; i < CELLS; i++) {
			for (int j = 0; j < CELLS; j++) {
				float height = 1f + top.offsetAt((i + 0.5f) / CELLS, (j + 0.5f) / CELLS);
				int steps = Math.clamp(Math.round(height * STEPS), 1, Math.round((1f + MAX_RISE) * STEPS));
				heights[i * CELLS + j] = steps;
				key = key * 16 + steps;
			}
		}
		return SHAPES.computeIfAbsent(key, k -> {
			VoxelShape shape = Shapes.empty();
			for (int i = 0; i < CELLS; i++) {
				for (int j = 0; j < CELLS; j++) {
					shape = Shapes.or(shape, Shapes.box((double) i / CELLS, 0.0, (double) j / CELLS,
						(double) (i + 1) / CELLS, (double) heights[i * CELLS + j] / STEPS, (double) (j + 1) / CELLS));
				}
			}
			return shape.optimize();
		});
	}
}
