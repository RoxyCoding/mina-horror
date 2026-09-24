package chihalu.mina.horror.client.render.terrain;

import chihalu.mina.horror.terrain.SmoothGround;
import chihalu.mina.horror.terrain.SmoothRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * Draws the ground in the areas chosen with the terrain wand as slopes ({@link SmoothGround}), matching the collision
 * the same class gives it on both sides. Plants and snow layers standing on it follow it.
 */
public final class SmoothTerrain {
	/** The areas the chunk meshes were last built for. */
	private static SmoothRegions.Regions drawn = new SmoothRegions.Regions(List.of());
	private static @Nullable ClientLevel drawnLevel;

	private SmoothTerrain() { }

	public static void register() {
		ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((original, context) -> {
			Block block = context.state().getBlock();
			if (SmoothGround.isGroundBlock(block)) return new GroundModel(original);
			if (SmoothGround.isRestingBlock(block)) return new RestingModel(original);
			return original;
		}));
		ClientTickEvents.END_CLIENT_TICK.register(SmoothTerrain::redrawChangedAreas);
	}

	/** The areas of the dimension being drawn; read from chunk meshing threads, hence an immutable snapshot. */
	private static SmoothRegions.Regions regions() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null ? SmoothRegions.of(level) : new SmoothRegions.Regions(List.of());
	}

	/** Areas added or removed by the server: rebuild the chunk meshes they touch. */
	private static void redrawChangedAreas(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			drawnLevel = null;
			return;
		}
		SmoothRegions.Regions current = SmoothRegions.of(level);
		if (level == drawnLevel && current.equals(drawn)) return;
		List<SmoothRegions.Area> changed = new ArrayList<>();
		for (SmoothRegions.Area area : current.areas()) if (!drawn.areas().contains(area)) changed.add(area);
		if (level == drawnLevel) for (SmoothRegions.Area area : drawn.areas()) if (!current.areas().contains(area)) changed.add(area);
		drawn = current;
		drawnLevel = level;
		for (SmoothRegions.Area area : changed) {
			// Corners on the edge depend on the columns just outside the area.
			level.setSectionRangeDirty(
				SectionPos.blockToSectionCoord(area.minX() - 2), level.getMinSectionY(), SectionPos.blockToSectionCoord(area.minZ() - 2),
				SectionPos.blockToSectionCoord(area.maxX() + 2), level.getMaxSectionY(), SectionPos.blockToSectionCoord(area.maxZ() + 2));
		}
	}

	/** Ground: the corners of its top move and take the slope's normal; its bottom and anything below stay put. */
	private static final class GroundModel extends WrapperBlockStateModel {
		GroundModel(BlockStateModel original) {
			super(original);
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			// Only the top block of a column meets the air; the ones under it keep their shape.
			SmoothGround.Top top = SmoothGround.isOpen(level.getBlockState(pos.above()))
				? SmoothGround.top(level, regions(), pos.getX(), pos.getY() + 1, pos.getZ()) : null;
			if (top == null) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			emitter.pushTransform(quad -> {
				boolean upward = quad.y(0) > 0.999f && quad.y(1) > 0.999f && quad.y(2) > 0.999f && quad.y(3) > 0.999f;
				for (int i = 0; i < 4; i++) {
					float y = quad.y(i);
					if (y <= 0.999f) continue;
					quad.pos(i, quad.x(i), y + top.offsetAt(quad.x(i), quad.z(i)), quad.z(i));
					float[] normal = top.normalNear(quad.x(i), quad.z(i));
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
			SmoothGround.Top top = SmoothGround.isGround(level.getBlockState(ground))
				? SmoothGround.top(level, regions(), ground.getX(), ground.getY() + 1, ground.getZ()) : null;
			if (top == null) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			emitter.pushTransform(quad -> {
				for (int i = 0; i < 4; i++) quad.pos(i, quad.x(i), quad.y(i) + top.offsetAt(quad.x(i), quad.z(i)), quad.z(i));
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
