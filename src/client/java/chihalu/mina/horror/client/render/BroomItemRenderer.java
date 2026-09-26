package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * The broom as an item ("mina-horror:broom" special model): the whole mesh scaled into the item's block, lying
 * diagonally with its front up to the right and its right side (the lantern side) towards the viewer, like a
 * sword sprite; item/broom.json holds the display transforms.
 */
public class BroomItemRenderer implements NoDataSpecialModelRenderer {
	private static final float SCALE = 0.68F;
	private static final float CENTRE_Z = 0.135F;

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(MinaHorror.id("broom"), Unbaked.MAP_CODEC);
	}

	private static void place(final PoseStack poseStack) {
		poseStack.translate(0.5F, 0.5F, 0.5F);
		poseStack.rotateDegrees(Axis.ZP, 45.0F);
		poseStack.rotateDegrees(Axis.YP, 90.0F);
		poseStack.scale(SCALE, SCALE, SCALE);
		poseStack.translate(0.0F, 0.0F, -CENTRE_Z);
	}

	@Override
	public void submit(
		final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int lightCoords, final int overlayCoords,
		final boolean hasFoil, final int outlineColor
	) {
		poseStack.pushPose();
		place(poseStack);
		BroomRenderer.submitMesh(poseStack, submitNodeCollector, lightCoords, overlayCoords, 0.0F, 0.0F);
		poseStack.popPose();
	}

	@Override
	public void getExtents(final Consumer<Vector3fc> output) {
		PoseStack poseStack = new PoseStack();
		place(poseStack);
		Matrix4f matrix = poseStack.last().pose();
		BroomMesh.get().getExtents(corner -> output.accept(matrix.transformPosition(corner)));
	}

	public record Unbaked() implements NoDataSpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<Void> bake(final SpecialModelRenderer.BakingContext context) {
			return new BroomItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
