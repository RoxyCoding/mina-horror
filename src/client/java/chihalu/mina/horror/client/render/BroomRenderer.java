package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.entity.Broom;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Vector3fc;

/**
 * Draws the flying broom: hovering with a slow bob, banking into turns, nose up in a climb, and the lantern swinging
 * from the bow. The lantern's flame and glass are drawn at full brightness.
 */
public class BroomRenderer extends EntityRenderer<Broom, BroomRenderState> {
	static final Identifier TEXTURE = MinaHorror.id("textures/entity/broom.png");

	public BroomRenderer(final EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.45F;
	}

	@Override
	public BroomRenderState createRenderState() {
		return new BroomRenderState();
	}

	@Override
	public void extractRenderState(final Broom entity, final BroomRenderState state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yRot = entity.getYRot(partialTicks);
		state.pitch = Mth.lerp(partialTicks, entity.pitchO, entity.pitch);
		state.bank = Mth.lerp(partialTicks, entity.bankO, entity.bank);
		state.swingForward = Mth.lerp(partialTicks, entity.swingForwardO, entity.swingForward);
		state.swingSide = Mth.lerp(partialTicks, entity.swingSideO, entity.swingSide);
		state.ridden = entity.isVehicle();
		state.hurtTime = entity.getHurtTime() - partialTicks;
		state.hurtDir = entity.getHurtDir();
		state.damageTime = Math.max(entity.getDamage() - partialTicks, 0.0F);
	}

	@Override
	public void submit(
		final BroomRenderState state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final CameraRenderState camera
	) {
		poseStack.pushPose();
		float bob = Mth.sin(state.ageInTicks * 0.09F) * (state.ridden ? 0.012F : 0.03F);
		poseStack.translate(0.0F, Broom.AXIS_HEIGHT + bob, 0.0F);
		poseStack.rotateDegrees(Axis.YP, -state.yRot);
		poseStack.rotateDegrees(Axis.XP, state.pitch);
		poseStack.rotateDegrees(Axis.ZP, state.bank);
		if (state.hurtTime > 0.0F) {
			poseStack.rotateDegrees(Axis.ZP, Mth.sin(state.hurtTime) * state.hurtTime * state.damageTime / 10.0F * state.hurtDir);
		}

		submitMesh(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.swingForward, state.swingSide);
		poseStack.popPose();
		super.submit(state, poseStack, submitNodeCollector, camera);
	}

	/** The broom in model space; the lantern swings forward/back and sideways (radians) about its hook. */
	static void submitMesh(
		final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int light, final int overlay,
		final float swingForward, final float swingSide
	) {
		BroomMesh mesh = BroomMesh.get();
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
			(pose, buffer) -> mesh.render(pose, buffer, BroomMesh.SOLID, light, overlay));
		poseStack.pushPose();
		Vector3fc pivot = mesh.pivot();
		poseStack.translate(pivot.x(), pivot.y(), pivot.z());
		poseStack.rotate(Axis.XP, swingForward);
		poseStack.rotate(Axis.ZP, swingSide);
		poseStack.translate(-pivot.x(), -pivot.y(), -pivot.z());
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
			(pose, buffer) -> mesh.render(pose, buffer, BroomMesh.LANTERN, light, overlay));
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
			(pose, buffer) -> mesh.render(pose, buffer, BroomMesh.FLAME, LightCoordsUtil.FULL_BRIGHT, overlay));
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE),
			(pose, buffer) -> mesh.render(pose, buffer, BroomMesh.GLASS, LightCoordsUtil.FULL_BRIGHT, overlay));
		poseStack.popPose();
	}
}
