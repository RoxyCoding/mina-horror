package chihalu.mina.horror.client.render;

import chihalu.mina.horror.entity.Broom;
import chihalu.mina.horror.entity.BroomMotion;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The rider's side of {@link BroomMotion}, for players and mannequins: the whole body carried with the broom's
 * pitch, bank and bob, then leaning, shifting its weight and sinking about the hips, with the limbs set to match:
 * legs astride the handle (swung over while mounting, tucked up at take-off, let down for landing, stepped down to
 * the ground after dismounting), both hands on the handle (or one when near death), the head keeping its gaze
 * through the lean (and glancing back at a familiar), and everything going slack when the rider dies.
 * In first person the view moves the same way ({@link #firstPersonView}).
 */
public final class BroomRiderPose {
	/** The handle's axis at the seat, above the rider's feet, and the hips, about which the body leans. */
	private static final float SEAT = 0.58F;
	private static final float HIPS = 0.75F;
	private static final float ASTRIDE = -1.4137167F;
	private static final float DISMOUNT_TICKS = 12.0F;
	private static final float EYES = 1.62F;
	/** How much of the body's bank and the broom's pitch the eyes follow; the neck keeps the head a little level. */
	private static final float VIEW_ROLL = 0.6F;
	private static final float VIEW_PITCH = 0.5F;

	private BroomRiderPose() {
	}

	private static @Nullable Entity entity(final AvatarRenderState state) {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.level.getEntity(state.id);
	}

	private static float partialTick(final AvatarRenderState state) {
		return state.ageInTicks - Mth.floor(state.ageInTicks);
	}

	/** After the body rotation: carry the rider with the broom, then lean, roll and shift about the hips. */
	public static void rotations(final AvatarRenderState state, final PoseStack poseStack) {
		Entity entity = entity(state);
		if (entity == null || !(entity.getVehicle() instanceof Broom broom)) {
			return;
		}

		carry(poseStack, broom, partialTick(state));
	}

	/** The rider's body in its frame (forward -z, right +x, feet at the origin): carried by the broom, then leaning. */
	private static void carry(final PoseStack poseStack, final Broom broom, final float partialTick) {
		BroomMotion motion = broom.motion;
		BroomRenderer.followBroom(poseStack, broom, SEAT, 0.0F, partialTick);
		poseStack.translate(motion.get(BroomMotion.SHIFT, partialTick), motion.get(BroomMotion.RIDER_LIFT, partialTick), 0.0F);
		poseStack.translate(0.0F, HIPS, 0.0F);
		poseStack.rotateDegrees(Axis.XP, -motion.get(BroomMotion.LEAN, partialTick));
		poseStack.rotateDegrees(Axis.ZP, -motion.get(BroomMotion.RIDER_ROLL, partialTick));
		poseStack.translate(0.0F, -HIPS, 0.0F);
	}

	/**
	 * The first-person view of a broom's rider, applied to the view's bob stack (which moves the world and the hands
	 * together): the eyes go where the carried, leaning body takes the head, and the view banks with the broom and
	 * the rider's weight, pitches with the broom and hums with it.
	 */
	public static void firstPersonView(final CameraRenderState camera, final PoseStack bob) {
		Minecraft minecraft = Minecraft.getInstance();
		Entity player = minecraft.getCameraEntity();
		if (!camera.isFirstPerson || player == null || !(player.getVehicle() instanceof Broom broom)
			|| player instanceof LivingEntity living && living.isDeadOrDying()) {
			return;
		}

		float partialTick = camera.cameraEntityPartialTicks;
		float age = broom.tickCount + partialTick;
		BroomMotion motion = broom.motion;
		PoseStack body = new PoseStack();
		carry(body, broom, partialTick);
		Vector3f eyes = body.last().pose().transformPosition(new Vector3f(0.0F, EYES, 0.0F)).sub(0.0F, EYES, 0.0F);
		// body frame -> world (the body faces the broom's heading) -> view
		eyes.rotateY((180.0F - broom.getYRot(partialTick)) * Mth.DEG_TO_RAD);
		camera.orientation.transformInverse(eyes);
		float roll = BroomRenderer.roll(motion, age, partialTick) + motion.get(BroomMotion.RIDER_ROLL, partialTick);
		bob.rotateDegrees(Axis.ZP, roll * VIEW_ROLL);
		bob.rotateDegrees(Axis.XP, BroomRenderer.pitch(motion, age, partialTick) * VIEW_PITCH);
		bob.translate(-eyes.x, -eyes.y, -eyes.z);
	}

	/** After the model's own animation: the limbs of a rider, or of someone just stepping off a broom. */
	public static void pose(final PlayerModel model, final AvatarRenderState state) {
		Entity entity = entity(state);
		if (entity == null) {
			return;
		}

		float partialTick = partialTick(state);
		if (!(entity.getVehicle() instanceof Broom broom)) {
			stepOff(model, BroomMotion.sinceDismount(entity, partialTick));
			return;
		}

		BroomMotion motion = broom.motion;
		float lean = motion.get(BroomMotion.LEAN, partialTick) * Mth.DEG_TO_RAD;
		float legs = motion.get(BroomMotion.LEGS, partialTick);
		float mount = motion.get(BroomMotion.MOUNT, partialTick);
		float grip = motion.get(BroomMotion.GRIP, partialTick);
		float limp = motion.get(BroomMotion.LIMP, partialTick);
		float age = state.ageInTicks;

		// legs astride the handle, kept along it through the lean; let down to land, tucked up to take off
		float legX = ASTRIDE - lean + (legs > 0.0F ? legs * 0.8F : legs * 0.35F);
		float swing = Mth.sin(mount * Mth.PI);             // the right leg sweeps out and over while mounting
		model.rightLeg.xRot = Mth.lerp(mount, 0.0F, legX);
		model.leftLeg.xRot = Mth.lerp(Math.min(mount * 1.4F, 1.0F), 0.0F, legX);
		model.rightLeg.zRot += swing * 0.9F;

		// both hands on the handle in front, reaching down less as the body leans towards it
		float reach = -0.62F + lean * 0.85F;
		model.rightArm.xRot = reach;
		model.rightArm.yRot = -0.32F;
		model.rightArm.zRot = 0.12F;
		model.leftArm.xRot = Mth.lerp(grip, reach, -0.15F + Mth.sin(age * 0.21F) * 0.2F);
		model.leftArm.yRot = Mth.lerp(grip, 0.32F, 0.0F);
		model.leftArm.zRot = Mth.lerp(grip, -0.12F, -0.35F + Mth.sin(age * 0.17F) * 0.1F);

		// the gaze holds through the lean; a glance back at a familiar riding behind
		model.head.xRot -= lean;
		model.head.yRot += motion.get(BroomMotion.GLANCE, partialTick) * 1.3F;

		if (limp > 0.0F) {
			model.head.xRot = Mth.lerp(limp, model.head.xRot, 0.9F);
			model.rightArm.xRot = Mth.lerp(limp, model.rightArm.xRot, -0.1F);
			model.leftArm.xRot = Mth.lerp(limp, model.leftArm.xRot, -0.1F);
			model.rightArm.zRot = Mth.lerp(limp, model.rightArm.zRot, 0.25F);
			model.leftArm.zRot = Mth.lerp(limp, model.leftArm.zRot, -0.25F);
			model.rightLeg.xRot = Mth.lerp(limp, model.rightLeg.xRot, -0.8F);
			model.leftLeg.xRot = Mth.lerp(limp, model.leftLeg.xRot, -0.8F);
		}
	}

	/** Just off a broom: the legs come down to the ground one after the other. */
	private static void stepOff(final PlayerModel model, final float since) {
		if (since < 0.0F || since > DISMOUNT_TICKS) {
			return;
		}

		float right = ease(since / DISMOUNT_TICKS);
		float left = ease(Mth.clamp((since / DISMOUNT_TICKS - 0.3F) / 0.7F, 0.0F, 1.0F));
		model.rightLeg.xRot = Mth.lerp(right, -1.1F, model.rightLeg.xRot);
		model.rightLeg.zRot = Mth.lerp(right, 0.35F, model.rightLeg.zRot);
		model.leftLeg.xRot = Mth.lerp(left, -1.1F, model.leftLeg.xRot);
	}

	private static float ease(final float t) {
		return t * t * (3.0F - 2.0F * t);
	}
}
