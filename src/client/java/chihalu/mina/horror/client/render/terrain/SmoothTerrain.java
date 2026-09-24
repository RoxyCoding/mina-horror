package chihalu.mina.horror.client.render.terrain;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Client-only: rounds the one-block steps of natural ground into slopes. Only the drawn shape changes; collision,
 * block shapes, saves and world generation stay vanilla.
 *
 * <p>Every top corner of the ground is moved to the average height of the four columns that meet there, but only
 * where those heights differ by at most one block: cliffs, buildings and anything else standing on the ground keep
 * their square edges. A corner's height depends on nothing but the corner itself, so neighbouring blocks, and the
 * side faces running up to a slope, always meet without gaps. Plants and snow layers standing on the ground follow it.
 */
public final class SmoothTerrain {
	/** Natural ground whose top corners are smoothed. */
	private static final Set<Block> GROUND = new HashSet<>();
	/** Small things standing on the ground, moved to follow it. */
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
	private static final int SPAN = 3;

	private SmoothTerrain() { }

	public static void register() {
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			if (!id.getNamespace().equals("minecraft")) continue;
			String name = id.getPath();
			if (name.endsWith("_ore") || name.endsWith("_terracotta") && !name.endsWith("glazed_terracotta")) GROUND.add(block);
			for (String ground : GROUND_NAMES) if (name.equals(ground)) GROUND.add(block);
			for (String resting : RESTING_NAMES) if (name.equals(resting)) RESTING.add(block);
		}
		ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((original, context) -> {
			Block block = context.state().getBlock();
			if (GROUND.contains(block)) return new GroundModel(original);
			if (RESTING.contains(block)) return new RestingModel(original);
			return original;
		}));
	}

	private static boolean isGround(BlockState state) {
		return GROUND.contains(state.getBlock()) && state.isSolidRender();
	}

	/** Air, water, plants, snow layers: nothing the ground would have to fit around. */
	private static boolean isOpen(BlockState state) {
		return state.isAir() || state.canBeReplaced() || RESTING.contains(state.getBlock());
	}

	/**
	 * Height of the top of the ground in column (x, z), searched within {@link #SPAN} blocks of y, or {@link #NONE}
	 * where something other than open space and ground comes first, or the ground rises past the search.
	 */
	private static int groundTop(BlockAndTintGetter level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
		for (int dy = SPAN; dy >= -SPAN - 1; dy--) {
			BlockState state = level.getBlockState(cursor.set(x, y + dy, z));
			if (isGround(state)) return dy == SPAN ? NONE : y + dy + 1;
			if (!isOpen(state)) return NONE;
		}
		return NONE;
	}

	/**
	 * The smoothed top of the ground over one block: its four top corners, each moved to a height shared by every
	 * block that has a top there, and the slope of the ground at each corner.
	 */
	record Top(float[] offsets, float[][] normals) { }

	/**
	 * Smoothed top for the block column at (x, z) whose ground ends at height y, or null where nothing moves.
	 *
	 * <p>At each corner, the four columns meeting there are averaged when their tops lie within {@link #SPAN} of each
	 * other. The corner is shared by all four tops, and never lower than one block below the highest: every column's
	 * uppermost side face then keeps its height, and side faces of a higher column always reach down to the
	 * lower column's top, so the surface stays closed. Blocks whose tops meet at a corner search overlapping
	 * ranges that both hold all four tops, so they agree on it.
	 */
	static Top top(BlockAndTintGetter level, int x, int y, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[][] tops = new int[3][3];
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) tops[dx + 1][dz + 1] = groundTop(level, cursor, x + dx, y, z + dz);
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

	/** Corner index of a block-local point on the top: x0z0, x1z0, x0z1, x1z1. */
	private static int cornerOf(float x, float z) {
		return (x > 0.5f ? 1 : 0) + (z > 0.5f ? 2 : 0);
	}

	/** Offset at a block-local point, blended between the corners. */
	private static float offsetAt(float[] offsets, float x, float z) {
		x = Math.clamp(x, 0f, 1f);
		z = Math.clamp(z, 0f, 1f);
		float near = offsets[0] + (offsets[1] - offsets[0]) * x;
		float far = offsets[2] + (offsets[3] - offsets[2]) * x;
		return near + (far - near) * z;
	}

	/** Ground: the corners of its top move and take the slope's normal; its bottom and anything below stay put. */
	private static final class GroundModel extends WrapperBlockStateModel {
		GroundModel(BlockStateModel original) {
			super(original);
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			// Only the top block of a column meets the air; the ones under it keep their shape.
			Top top = isOpen(level.getBlockState(pos.above())) ? top(level, pos.getX(), pos.getY() + 1, pos.getZ()) : null;
			if (top == null) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			emitter.pushTransform(quad -> {
				boolean upward = quad.y(0) > 0.999f && quad.y(1) > 0.999f && quad.y(2) > 0.999f && quad.y(3) > 0.999f;
				for (int i = 0; i < 4; i++) {
					float y = quad.y(i);
					if (y <= 0.999f) continue;
					quad.pos(i, quad.x(i), y + offsetAt(top.offsets(), quad.x(i), quad.z(i)), quad.z(i));
					float[] normal = top.normals()[cornerOf(quad.x(i), quad.z(i))];
					if (upward && normal != null) quad.normal(i, normal[0], normal[1], normal[2]);
				}
				return true;
			});
			try {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
			} finally {
				emitter.popTransform();
			}
		}

		@Override
		public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
			// The shape depends on the neighbouring columns; never reuse the plain cube's geometry.
			return null;
		}
	}

	/** Plants and snow layers: moved with the ground they stand on, following its slope. */
	private static final class RestingModel extends WrapperBlockStateModel {
		RestingModel(BlockStateModel original) {
			super(original);
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			// The upper half of a tall plant stands on the ground two blocks down.
			boolean upper = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
			BlockPos ground = upper ? pos.below(2) : pos.below();
			if (!isGround(level.getBlockState(ground))) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			Top top = top(level, ground.getX(), ground.getY() + 1, ground.getZ());
			if (top == null) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			emitter.pushTransform(quad -> {
				for (int i = 0; i < 4; i++) {
					quad.pos(i, quad.x(i), quad.y(i) + offsetAt(top.offsets(), quad.x(i), quad.z(i)), quad.z(i));
				}
				return true;
			});
			try {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
			} finally {
				emitter.popTransform();
			}
		}

		@Override
		public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
			return null;
		}
	}
}
