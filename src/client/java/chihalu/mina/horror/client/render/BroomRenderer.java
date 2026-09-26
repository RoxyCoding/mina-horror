package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.entity.Broom;
import chihalu.mina.horror.entity.BroomMotion;
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
 * Draws the flying broom in the motion {@link BroomMotion} gives it: bobbing, pitching, banking, jolting and
 * humming about its seat, the bristles streaming back at speed and the lantern swinging from the bow. The lantern's
 * flame and glass are drawn at full brightness. Its passengers are moved along with it ({@link #followBroom}).
 */
public class BroomRenderer extends EntityRenderer<Broom, BroomRenderState> {
	static final Identifier TEXTURE = MinaHorror.id("textures/entity/broom.png");

	public BroomRenderer(final EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.65F;
	}

	@Override
	public BroomRenderState createRenderState() {
		return new BroomRenderState();
	}

	@Override
	public void extractRenderState(final Broom entity, final BroomRenderState state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		BroomMotion motion = entity.motion;
		state.yRot = entity.getYRot(partialTicks);
		state.pitch = pitch(motion, state.ageInTicks, partialTicks);
		state.roll = roll(motion, state.ageInTicks, partialTicks);
		state.lift = lift(motion, state.ageInTicks, partialTicks);
		state.surge = motion.get(BroomMotion.SURGE, partialTicks);
		state.sway = motion.get(BroomMotion.SWAY, partialTicks);
		state.stream = motion.get(BroomMotion.STREAM, partialTicks);
		state.swingForward = motion.get(BroomMotion.SWING_FORWARD, partialTicks);
		state.swingSide = motion.get(BroomMotion.SWING_SIDE, partialTicks);
		state.hurtTime = entity.getHurtTime() - partialTicks;
		state.hurtDir = entity.getHurtDir();
		state.damageTime = Math.max(entity.getDamage() - partialTicks, 0.0F);
	}

	static float pitch(final BroomMotion motion, final float age, final float partialTick) {
		return motion.get(BroomMotion.PITCH, partialTick) + motion.buzz(age, partialTick, 0.0F);
	}

	static float roll(final BroomMotion motion, final float age, final float partialTick) {
		return motion.get(BroomMotion.ROLL, partialTick) + motion.buzz(age, partialTick, 1.7F) * 0.6F;
	}

	private static float lift(final BroomMotion motion, final float age, final float partialTick) {
		return motion.get(BroomMotion.LIFT, partialTick) + motion.bob(age);
	}

	@Override
	public void submit(
		final BroomRenderState state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final CameraRenderState camera
	) {
		poseStack.pushPose();
		poseStack.translate(0.0F, Broom.AXIS_HEIGHT + state.lift, 0.0F);
		poseStack.rotateDegrees(Axis.YP, -state.yRot);
		// model space: front +z, right -x
		poseStack.translate(-state.sway, 0.0F, state.surge);
		poseStack.rotateDegrees(Axis.XP, state.pitch);
		poseStack.rotateDegrees(Axis.ZP, state.roll);
		if (state.hurtTime > 0.0F) {
			poseStack.rotateDegrees(Axis.ZP, Mth.sin(state.hurtTime) * state.hurtTime * state.damageTime / 10.0F * state.hurtDir);
		}

		submitMesh(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.swingForward, state.swingSide,
			state.stream, state.ageInTicks);
		poseStack.popPose();
		super.submit(state, poseStack, submitNodeCollector, camera);
	}

	/**
	 * Moves a passenger with the broom's motion. The pose stack is in the passenger's frame after its body rotation
	 * (the broom's heading: forward -z, right +x, up +y); seatY and seatZ place the handle's axis at the seat
	 * relative to the passenger's feet in that frame.
	 */
	public static void followBroom(final PoseStack poseStack, final Broom broom, final float seatY, final float seatZ, final float partialTick) {
		BroomMotion motion = broom.motion;
		float age = broom.tickCount + partialTick;
		poseStack.translate(0.0F, seatY, seatZ);
		poseStack.translate(motion.get(BroomMotion.SWAY, partialTick), lift(motion, age, partialTick), -motion.get(BroomMotion.SURGE, partialTick));
		poseStack.rotateDegrees(Axis.XP, -pitch(motion, age, partialTick));
		poseStack.rotateDegrees(Axis.ZP, -roll(motion, age, partialTick));
		poseStack.translate(0.0F, -seatY, -seatZ);
	}

	/** The broom in model space; the lantern swings forward/back and sideways (radians) about its hook. */
	static void submitMesh(
		final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int light, final int overlay,
		final float swingForward, final float swingSide, final float stream, final float age
	) {
		BroomMesh mesh = BroomMesh.get();
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE), (pose, buffer) -> {
			mesh.render(pose, buffer, BroomMesh.SOLID, light, overlay);
			mesh.renderBristles(pose, buffer, stream, age, light, overlay);
		});
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
