package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.entity.Girl;
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
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;

/**
 * Draws the girl mesh built from the three-view sheet. The mesh is modelled in the sheet's T-pose, so the arms
 * are first lowered by {@link #ARM_REST} and then swung like a humanoid model.
 */
public class GirlRenderer extends MobRenderer<Girl, LivingEntityRenderState, GirlRenderer.EmptyModel> {
	private static final Identifier TEXTURE = MinaHorror.id("textures/entity/girl.png");
	private static final int MAGIC = 0x4749524C;
	private static final int HEAD = 1;
	private static final int LEFT_ARM = 2;
	private static final int RIGHT_ARM = 3;
	private static final int LEFT_LEG = 4;
	private static final int RIGHT_LEG = 5;
	private static final float ARM_REST = 75.0F * Mth.DEG_TO_RAD;
	private static SkinnedMesh mesh;

	public GirlRenderer(final EntityRendererProvider.Context context) {
		super(context, new EmptyModel(), 0.4F);
		this.addLayer(new MeshLayer(this));
	}

	@Override
	public Identifier getTextureLocation(final LivingEntityRenderState state) {
		return TEXTURE;
	}

	@Override
	public LivingEntityRenderState createRenderState() {
		return new LivingEntityRenderState();
	}

	private static SkinnedMesh mesh() {
		if (mesh == null) {
			mesh = SkinnedMesh.load("girl.bin", MAGIC, 5, 0.7F);
		}

		return mesh;
	}

	public static class EmptyModel extends EntityModel<LivingEntityRenderState> {
		public EmptyModel() {
			super(new ModelPart(List.of(), Map.of()));
		}
	}

	private static class MeshLayer extends RenderLayer<LivingEntityRenderState, EmptyModel> {
		MeshLayer(final GirlRenderer parent) {
			super(parent);
		}

		@Override
		public void submit(
			final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int lightCoords,
			final LivingEntityRenderState state, final float yRot, final float xRot
		) {
			if (state.isInvisible) {
				return;
			}

			// same swing formulas as the vanilla humanoid model
			float phase = state.walkAnimationPos * 0.6662F;
			float speed = Math.min(state.walkAnimationSpeed, 1.0F);
			float idle = Mth.cos(state.ageInTicks * 0.09F) * 0.04F;
			Matrix3f[] bones = new Matrix3f[6];
			bones[HEAD] = new Matrix3f().rotationZYX(
				0.0F, Mth.clamp(yRot, -75.0F, 75.0F) * Mth.DEG_TO_RAD, Mth.clamp(xRot, -45.0F, 45.0F) * Mth.DEG_TO_RAD
			);
			bones[LEFT_ARM] = new Matrix3f().rotationX(Mth.cos(phase) * speed).rotateZ(ARM_REST - idle);
			bones[RIGHT_ARM] = new Matrix3f().rotationX(Mth.cos(phase + Mth.PI) * speed).rotateZ(-ARM_REST + idle);
			bones[LEFT_LEG] = new Matrix3f().rotationX(Mth.cos(phase + Mth.PI) * 1.4F * speed);
			bones[RIGHT_LEG] = new Matrix3f().rotationX(Mth.cos(phase) * 1.4F * speed);
			int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.entityCutout(TEXTURE),
				(pose, buffer) -> mesh().render(pose, buffer, lightCoords, overlay, bones)
			);
		}
	}
}
