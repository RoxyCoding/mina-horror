package chihalu.mina.horror.client.render.terrain;

import chihalu.mina.horror.terrain.SmoothColumns;
import chihalu.mina.horror.terrain.SmoothGround;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
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
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Draws natural one-block steps and wand-marked taller steps as slopes ({@link SmoothGround}), matching their collision.
 * Plants and snow layers standing on them follow the slope.
 */
public final class SmoothTerrain {
	private SmoothTerrain() { }

	public static void register() {
		ShoreWater.register();
		ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((original, context) -> {
			Block block = context.state().getBlock();
			if (SmoothGround.isGroundBlock(block)) return new GroundModel(original);
			if (SmoothGround.isRestingBlock(block)) return new RestingModel(original);
			return original;
		}));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> chunk.onAttachedSet(SmoothColumns.type())
			.register((before, after) -> redrawChanged(level, chunk, before, after)));
	}

	/** Marks of the dimension being drawn; read from chunk meshing threads. */
	private static SmoothColumns.@Nullable Lookup marks() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null ? new SmoothColumns.Lookup(level) : null;
	}

	/** Columns marked or cleared by the server, or arriving with their chunk: rebuild the meshes they reach. */
	private static void redrawChanged(ClientLevel level, LevelChunk chunk, SmoothColumns.@Nullable Marks before, SmoothColumns.@Nullable Marks after) {
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				boolean was = before != null && before.has(x, z);
				boolean is = after != null && after.has(x, z);
				if (was == is) continue;
				minX = Math.min(minX, x);
				minZ = Math.min(minZ, z);
				maxX = Math.max(maxX, x);
				maxZ = Math.max(maxZ, z);
			}
		}
		if (minX > maxX) return;
		// A marked column moves the corners it shares with its neighbours.
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		level.setSectionRangeDirty(
			SectionPos.blockToSectionCoord(baseX + minX - 1), level.getMinSectionY(), SectionPos.blockToSectionCoord(baseZ + minZ - 1),
			SectionPos.blockToSectionCoord(baseX + maxX + 1), level.getMaxSectionY(), SectionPos.blockToSectionCoord(baseZ + maxZ + 1));
	}

    private static int cornerPalette(BlockAndTintGetter level, BlockPos pos, int localX, int localZ, int own) {
        float[] weights = new float[8];
        for (int dx = localX-1; dx <= localX; dx++) for (int dz = localZ-1; dz <= localZ; dz++) {
            int material = dx == 0 && dz == 0 ? own : SmoothGround.surfaceMaterialAt(level, pos.getX()+dx, pos.getY()+1, pos.getZ()+dz);
            if (material >= 0) weights[material]++;
        }
        return TerrainMaterials.pack(weights);
    }
	/** Ground: the corners of its top move and take the slope's normal; its bottom and anything below stay put. */
	private static final class GroundModel extends WrapperBlockStateModel {
		GroundModel(BlockStateModel original) {
			super(original);
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			// Only the top block of a column meets the air; the ones under it keep their shape.
			SmoothColumns.Lookup marks = marks();
			SmoothGround.Top top = marks != null && SmoothGround.isSurfaceAbove(level.getBlockState(pos.above()))
				? SmoothGround.top(level, marks, pos.getX(), pos.getY() + 1, pos.getZ()) : null;
            int ownMaterial = SmoothGround.surfaceMaterial(state.getBlock());
            if (ownMaterial == 0 && state.hasProperty(BlockStateProperties.SNOWY) && state.getValue(BlockStateProperties.SNOWY)) ownMaterial = 5;
            int[] palettes = new int[4];
            boolean shaderMaterials = ShoreWaterShaders.active() && ownMaterial >= 0;
            boolean transition = shaderMaterials && SmoothGround.isSurfaceAbove(level.getBlockState(pos.above()));
            if (transition) for (int corner=0; corner<4; corner++) palettes[corner] = cornerPalette(level,pos,corner&1,corner>>1,ownMaterial);
            float[] base = new float[8];
            if (ownMaterial >= 0) base[ownMaterial == 0 ? 1 : ownMaterial] = 1;
            int baseColor = ownMaterial >= 0 ? TerrainMaterials.pack(base) : -1;
			if (top == null && !transition && !shaderMaterials) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			QuadEmitter lit = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(quad -> {
				for (int i=0;i<4;i++) quad.lightmap(i, TerrainLight.at(level,
					pos.getX()+quad.x(i), pos.getY()+quad.y(i)+.5, pos.getZ()+quad.z(i)));
				emitter.copyFrom(quad);
				emitter.pos(0,quad.x(0),quad.y(0),quad.z(0));
				emitter.emit();
			});
			QuadEmitter shaped = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(quad -> {
				if (top != null) {
					// Cube AO and inset-face light samples refer to solid space that this slope no longer occupies.
					quad.ambientOcclusion(net.fabricmc.fabric.api.util.TriState.FALSE);
				}
				if (top != null && quad.nominalFace() != Direction.DOWN) TerrainSurface.emit(quad, lit, top);
				else {
					lit.copyFrom(quad);
					lit.pos(0, quad.x(0), quad.y(0), quad.z(0));
					lit.emit();
				}
			});
            shaped.pushTransform(quad -> {
                if (shaderMaterials) {
                    quad.tintIndex(-1);
                    quad.ambientOcclusion(net.fabricmc.fabric.api.util.TriState.FALSE);
                    quad.shadeDirectionOverride(Direction.UP);
                    quad.tag(0x4D4154);
                    for (int i=0; i<4; i++) {
                        int corner = (quad.x(i)>.5f ? 1:0)+(quad.z(i)>.5f ? 2:0);
                        quad.color(i, transition && quad.y(i)>.999f ? palettes[corner] : baseColor);
                    }
                }
                return true;
            });
			try {
				// Moving top corners can expose a side that vanilla would cull against a full neighbour.
				super.emitQuads(shaped, level, pos, state, random, top == null ? cullTest : direction -> false);
			} finally {
				shaped.popTransform();
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
			SmoothColumns.Lookup marks = marks();
			SmoothGround.Top top = marks != null && SmoothGround.isGround(level.getBlockState(ground))
				? SmoothGround.top(level, marks, ground.getX(), ground.getY() + 1, ground.getZ()) : null;
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
