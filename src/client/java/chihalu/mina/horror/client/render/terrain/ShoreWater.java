package chihalu.mina.horror.client.render.terrain;

import chihalu.mina.horror.terrain.SmoothColumns;
import chihalu.mina.horror.terrain.SmoothGround;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Extend the water plane into ground cells lowered by smoothing. The opaque slope clips it by depth. */
public final class ShoreWater implements FluidRenderHandler {
	public static void register() {
		var model = new FluidModel.Unbaked(
			new Material(Identifier.withDefaultNamespace("block/water_still")),
			new Material(Identifier.withDefaultNamespace("block/water_flow")),
			new Material(Identifier.withDefaultNamespace("block/water_overlay")), BlockTintSources.water());
		FluidRenderingRegistry.register(Fluids.WATER, Fluids.FLOWING_WATER, model, new ShoreWater());
	}

	@Override
	public void renderFluid(FluidRenderer renderer, BlockPos pos, BlockAndTintGetter level,
		FluidRenderer.Output output, BlockState state, FluidState fluid) {
		FluidRenderHandler.super.renderFluid(renderer, pos, level, output, state, fluid);
		if (!surface(level, pos)) return;
		var world = Minecraft.getInstance().level;
		if (world == null) return;
		var marks = new SmoothColumns.Lookup(world);
		var model = renderer.fluidModels.get(fluid);
		var sprite = model.stillMaterial().sprite();
		int color = model.tintSource() == null ? -1 : model.tintSource().colorInWorld(state, level, pos);
		color = ARGB.scaleRGB(color, level.cardinalLighting().up());
		int light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, pos), LightCoordsUtil.getLightCoords(level, pos.above()));
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dz == 0) continue;
			BlockPos ground = pos.offset(dx, 0, dz);
			if (!SmoothGround.isGround(level.getBlockState(ground)) || !SmoothGround.isOpen(level.getBlockState(ground.above()))) continue;
			// A shore cell gets one water plane, even at a corner between several water cells.
			if (!pos.equals(owner(level, ground))) continue;
			SmoothGround.Top top = SmoothGround.top(level, marks, ground.getX(), ground.getY() + 1, ground.getZ());
			float height = fluid.getOwnHeight() - 0.001f;
			if (top == null || !reaches(top.offsets(), height)) continue;
			VertexConsumer builder = output.getBuilder(model.layer());
			boolean shaderContext = ShoreWaterShaders.begin(builder, fluid.createLegacyBlock(), ground);
			try {
				// Coordinates belong to the water cell's section, including when the shore crosses a section edge.
				float x = (pos.getX() & 15) + dx, y = (pos.getY() & 15) + height, z = (pos.getZ() & 15) + dz;
				for (int back = 0; back < 2; back++) {
					for (int i = 0; i < 4; i++) {
						int corner = back == 0 ? i : (4 - i) & 3;
						float u = corner >= 2 ? 1 : 0, v = corner == 1 || corner == 2 ? 1 : 0;
						builder.addVertex(x + u, y, z + v, color, sprite.getU(u), sprite.getV(v),
							OverlayTexture.NO_OVERLAY, light, 0, back == 0 ? 1 : -1, 0);
					}
				}
				} finally {
				if (shaderContext) ShoreWaterShaders.end(builder);
			}
		}
	}

	static boolean reaches(float[] offsets, float waterHeight) {
		for (float offset : offsets) if (1 + offset < waterHeight) return true;
		return false;
	}

	private static boolean surface(BlockAndTintGetter level, BlockPos pos) {
		var fluid = level.getFluidState(pos);
		// ponytail: standing shore water only; flowing waterfalls need a separate continuous flow mesh.
		return fluid.is(FluidTags.WATER) && fluid.isSource() && !level.getFluidState(pos.above()).is(FluidTags.WATER);
	}

	private static BlockPos owner(BlockAndTintGetter level, BlockPos ground) {
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			BlockPos candidate = ground.offset(dx, 0, dz);
			if (surface(level, candidate)) return candidate;
		}
		return null;
	}
}
