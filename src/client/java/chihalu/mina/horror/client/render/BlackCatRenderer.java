package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.entity.BlackCat;
import chihalu.mina.horror.entity.Broom;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Draws the black cat mesh. The regular model is empty; the mesh is submitted as custom geometry
 * from a layer so it gets the usual entity transforms, lighting and hurt overlay.
 */
public class BlackCatRenderer extends MobRenderer<BlackCat, BlackCatRenderState, BlackCatRenderer.EmptyModel> {
	private static final Identifier TEXTURE = MinaHorror.id("textures/entity/black_cat.png");

	public BlackCatRenderer(final EntityRendererProvider.Context context) {
		super(context, new EmptyModel(), 0.25F);
		this.addLayer(new MeshLayer(this));
	}

	@Override
	public Identifier getTextureLocation(final BlackCatRenderState state) {
		return TEXTURE;
	}

	@Override
	public BlackCatRenderState createRenderState() {
		return new BlackCatRenderState();
	}

	@Override
	public void extractRenderState(final BlackCat entity, final BlackCatRenderState state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.isSitting = entity.isInSittingPose();
		state.broom = entity.getVehicle() instanceof Broom broom ? broom : null;
		state.partialTick = partialTicks;
	}

	@Override
	protected void setupRotations(final BlackCatRenderState state, final PoseStack poseStack, final float bodyRot, final float entityScale) {
		super.setupRotations(state, poseStack, bodyRot, entityScale);
		if (state.broom != null) {
			// riding behind its master: carried with the broom about the seat, just ahead of and below it
			BroomRenderer.followBroom(poseStack, state.broom, -0.11F, -0.45F, state.partialTick);
		}
	}

	public static class EmptyModel extends EntityModel<BlackCatRenderState> {
		public EmptyModel() {
			super(new ModelPart(List.of(), Map.of()));
		}
	}

	private static class MeshLayer extends RenderLayer<BlackCatRenderState, EmptyModel> {
		MeshLayer(final BlackCatRenderer parent) {
			super(parent);
		}

		@Override
		public void submit(
			final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int lightCoords,
			final BlackCatRenderState state, final float yRot, final float xRot
		) {
			if (state.isInvisible) {
				return;
			}

			int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
			float headYaw = Mth.clamp(yRot, -70.0F, 70.0F) * Mth.DEG_TO_RAD;
			float headPitch = Mth.clamp(xRot, -35.0F, 35.0F) * Mth.DEG_TO_RAD;
			float[] legSwing = new float[BlackCatMesh.LEG_COUNT];
			BlackCatMesh mesh;
			float tailSwing;
			if (state.isSitting) {
				mesh = BlackCatMesh.sitting();
				tailSwing = Mth.sin(state.ageInTicks * 0.08F) * 0.18F;
			} else {
				mesh = BlackCatMesh.walking();
				// quadruped gait: diagonal legs move together (0 left front, 1 right front, 2 left hind, 3 right hind)
				float phase = state.walkAnimationPos * 0.6662F;
				float amount = Math.min(state.walkAnimationSpeed, 1.0F) * 1.1F;
				legSwing[0] = Mth.cos(phase) * amount;
				legSwing[1] = Mth.cos(phase + Mth.PI) * amount;
				legSwing[2] = Mth.cos(phase + Mth.PI) * amount;
				legSwing[3] = Mth.cos(phase) * amount;
				tailSwing = Mth.sin(state.ageInTicks * 0.1F) * (0.12F + 0.25F * amount);
			}

			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.entityCutout(TEXTURE),
				(pose, buffer) -> mesh.render(pose, buffer, lightCoords, overlay, headYaw, headPitch, tailSwing, legSwing)
			);
		}
	}
}
