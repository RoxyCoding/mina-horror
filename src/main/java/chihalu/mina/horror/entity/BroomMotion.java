package chihalu.mina.horror.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The procedural motion of a broom and its rider, run on the client every tick from what the broom is seen doing,
 * so every player sees the same motion. Each channel is a damped spring pulled towards a target (a lean for the
 * speed, a bank for the turn rate, ...) and kicked by events (inertia while speeding up or slowing down, take-off,
 * touchdown, collisions, hits), which gives the overshoot and settling of each motion:
 * <ul>
 * <li>hover: the rider sits up and breathes with the broom, which bobs and sways a little;</li>
 * <li>mount/dismount: the broom dips under the rider's weight, the legs swing over (or down to the ground);</li>
 * <li>take-off, climb, descent, landing: leaning back, tucking the feet, nose up or down, legs down and a knee-soft
 * touchdown, the broom dipping before it climbs and bouncing after;</li>
 * <li>forward, boost, reverse, braking: leaning into the speed and crouching to the handle at a boost with the
 * broom levelling, humming and its bristles streaming back; inertia throws the body back as it speeds up and
 * forward as it slows;</li>
 * <li>turns and strafes: weight shifted and the broom banked into the turn, sinking in a sharp one, and a little
 * overshoot as it rights itself; the hips slide towards a strafe;</li>
 * <li>low flight follows the ground, an obstacle ahead makes the rider flinch back, collisions and hits from each
 * side jolt both, a dying rider sways and grips with one hand, a dead one goes limp and the broom falls away;</li>
 * <li>a familiar aboard weighs the broom down and has the rider glance back at it.</li>
 * </ul>
 */
public final class BroomMotion {
	// broom: pitch (deg, nose down), roll (deg, right side down), lift (blocks), surge (blocks forward),
	// sway (blocks right), shake (deg of buzz), stream (0..1, bristles swept back), lantern swing (rad)
	public static final int PITCH = 0;
	public static final int ROLL = 1;
	public static final int LIFT = 2;
	public static final int SURGE = 3;
	public static final int SWAY = 4;
	public static final int SHAKE = 5;
	public static final int STREAM = 6;
	public static final int SWING_FORWARD = 7;
	public static final int SWING_SIDE = 8;
	// rider: lean (deg forward), roll (deg to the right), shift (blocks right), lift (blocks), legs (-1 tucked up,
	// 1 let down), grip (1: one hand), limp (1: dead), mount (0 standing .. 1 astride), glance (1: looking back)
	public static final int LEAN = 9;
	public static final int RIDER_ROLL = 10;
	public static final int SHIFT = 11;
	public static final int RIDER_LIFT = 12;
	public static final int LEGS = 13;
	public static final int GRIP = 14;
	public static final int LIMP = 15;
	public static final int MOUNT = 16;
	public static final int GLANCE = 17;
	private static final int CHANNELS = 18;

	public static final byte HIT_FRONT = 100;
	public static final byte HIT_BACK = 101;
	public static final byte HIT_LEFT = 102;
	public static final byte HIT_RIGHT = 103;

	/** Game time at which each player got off a broom, for the dismount motion. */
	private static final Map<Integer, Long> DISMOUNTED = new HashMap<>();

	private final float[] value = new float[CHANNELS];
	private final float[] previous = new float[CHANNELS];
	private final float[] speed = new float[CHANNELS];
	private @Nullable Vec3 lastPosition;
	private Vec3 lastVelocity = Vec3.ZERO;
	private float lastYRot;
	private @Nullable Entity lastRider;
	private double lastGround = 4.0;
	private double groundAverage = 4.0;
	private int takeOff;
	private boolean ridden;

	public float get(final int channel, final float partialTick) {
		return Mth.lerp(partialTick, this.previous[channel], this.value[channel]);
	}

	public boolean ridden() {
		return this.ridden;
	}

	/** Hover bob of the broom in blocks, higher while nobody is aboard. */
	public float bob(final float ageInTicks) {
		return Mth.sin(ageInTicks * 0.09F) * (this.ridden ? 0.012F : 0.03F);
	}

	/** The buzz of the broom (deg), a fast irregular shake scaled by the shake channel. */
	public float buzz(final float ageInTicks, final float partialTick, final float phase) {
		return this.get(SHAKE, partialTick) * (Mth.sin(ageInTicks * 2.9F + phase) * 0.6F + Mth.sin(ageInTicks * 4.7F + phase * 2.0F) * 0.4F);
	}

	/** Ticks since the player got off a broom, or -1. */
	public static float sinceDismount(final Entity player, final float partialTick) {
		Long at = DISMOUNTED.get(player.getId());
		if (at == null) {
			return -1.0F;
		}

		float since = player.level().getGameTime() - at + partialTick;
		if (since > 20.0F || since < 0.0F) {
			DISMOUNTED.remove(player.getId());
			return -1.0F;
		}

		return since;
	}

	private void spring(final int channel, final float target, final float stiffness, final float damping) {
		this.speed[channel] += (target - this.value[channel]) * stiffness - this.speed[channel] * damping;
		this.value[channel] += this.speed[channel];
	}

	private void approach(final int channel, final float target, final float rate) {
		this.value[channel] += (target - this.value[channel]) * rate;
	}

	private void kick(final int channel, final float amount) {
		this.speed[channel] += amount;
	}

	/** A hit on the rider, reported by the server as one of the HIT_ entity events. */
	public void hit(final byte side) {
		switch (side) {
			case HIT_FRONT -> {
				this.kick(LEAN, -9.0F);
				this.kick(SURGE, -0.05F);
			}
			case HIT_BACK -> {
				this.kick(LEAN, 9.0F);
				this.kick(SURGE, 0.05F);
			}
			case HIT_LEFT -> {
				this.kick(RIDER_ROLL, 8.0F);
				this.kick(ROLL, 10.0F);
			}
			case HIT_RIGHT -> {
				this.kick(RIDER_ROLL, -8.0F);
				this.kick(ROLL, -10.0F);
			}
			default -> {
				return;
			}
		}

		this.value[SHAKE] = Math.max(this.value[SHAKE], 3.0F);
	}

	public void tick(final Broom broom) {
		System.arraycopy(this.value, 0, this.previous, 0, CHANNELS);
		Vec3 position = broom.position();
		Vec3 moved = this.lastPosition == null ? Vec3.ZERO : position.subtract(this.lastPosition);
		if (moved.lengthSqr() > 4.0) {
			moved = this.lastVelocity;                      // teleported, not flown
		}

		// smoothed, as other players' brooms arrive in steps; no flight speeds up faster than about 0.1/tick^2
		Vec3 velocity = this.lastVelocity.add(moved.subtract(this.lastVelocity).scale(0.6));
		Vec3 acceleration = clamp(velocity.subtract(this.lastVelocity), 0.1);
		float turn = this.lastPosition == null ? 0.0F : Mth.wrapDegrees(broom.getYRot() - this.lastYRot);
		float yaw = broom.getYRot() * Mth.DEG_TO_RAD;
		float sin = Mth.sin(yaw);
		float cos = Mth.cos(yaw);
		// forward (-sin, cos) and right (-cos, -sin) of the broom's heading
		float forward = (float) (-velocity.x * sin + velocity.z * cos);
		float right = (float) (-velocity.x * cos - velocity.z * sin);
		float up = (float) velocity.y;
		float accelForward = (float) (-acceleration.x * sin + acceleration.z * cos);
		float accelRight = (float) (-acceleration.x * cos - acceleration.z * sin);
		float accelUp = (float) acceleration.y;
		float horizontal = (float) velocity.horizontalDistance();
		double ground = this.groundBelow(broom, position);
		this.groundAverage += (ground - this.groundAverage) * 0.08;

		Entity first = broom.getFirstPassenger();
		LivingEntity rider = first instanceof LivingEntity living ? living : null;
		this.ridden = rider != null;
		boolean dead = rider != null && rider.isDeadOrDying();
		float danger = rider == null || dead ? 0.0F : Mth.clamp(1.0F - rider.getHealth() / rider.getMaxHealth() / 0.3F, 0.0F, 1.0F);
		boolean familiar = broom.getPassengers().stream().anyMatch(p -> p instanceof BlackCat);
		boolean sprinting = rider != null && rider.isSprinting();
		boolean braking = rider instanceof Player player && player.zza < 0.0F && forward > 0.05F;
		float age = broom.tickCount;

		// events
		if (rider != null && this.lastRider == null) {
			this.kick(LIFT, -0.035F);                       // the broom takes the rider's weight
			this.value[MOUNT] = 0.0F;
		} else if (rider == null && this.lastRider != null) {
			DISMOUNTED.put(this.lastRider.getId(), broom.level().getGameTime());
		}

		if (rider != null && this.lastGround < 0.25 && up > 0.04F && this.takeOff == 0) {
			this.takeOff = 16;
			this.kick(LIFT, -0.05F);                        // dips before it climbs
		}

		if (ground > 0.3) {
			this.takeOff = Math.max(this.takeOff - 1, 0);
		}

		if (this.lastGround > 0.12 && ground <= 0.08 && this.lastVelocity.y < -0.04) {
			float impact = (float) Math.min(-this.lastVelocity.y, 0.3);
			this.kick(LIFT, -impact * 0.35F);             // touchdown: sinks, then springs back
			this.kick(RIDER_LIFT, -impact * 0.45F);       // the knees take it
			this.kick(LEAN, impact * 25.0F);
		}

		if (this.lastVelocity.y > 0.08 && up < this.lastVelocity.y - 0.04) {
			this.kick(RIDER_LIFT, -0.03F);                // levelling off: the body settles
			this.kick(LIFT, 0.025F);
		}

		float lastHorizontal = (float) this.lastVelocity.horizontalDistance();
		float lost = lastHorizontal - horizontal;
		if (lastHorizontal > 0.2F && lost > 0.18F) {
			// a collision: light ones jolt and bounce the broom back, hard ones stop it dead
			boolean hard = lost > 0.45F;
			this.kick(LEAN, hard ? 22.0F : 12.0F);
			this.kick(SURGE, hard ? -0.04F : -0.08F);
			this.kick(PITCH, hard ? 7.0F : 3.0F);
			this.kick(ROLL, (broom.getRandom().nextFloat() - 0.5F) * (hard ? 14.0F : 6.0F));
			this.value[SHAKE] = Math.max(this.value[SHAKE], hard ? 14.0F : 7.0F);
		} else if (accelForward < -0.09F && forward > 0.1F) {
			this.kick(LEAN, 5.0F);                          // a hard stop throws the rider forward
			this.kick(LIFT, -0.02F);
		}

		// inertia: speeding up pulls the rider back and the broom's tail down, slowing throws them forward
		this.kick(LEAN, -accelForward * 45.0F);
		this.kick(PITCH, -accelForward * 45.0F);
		this.kick(SURGE, -accelForward * 0.6F);
		this.kick(RIDER_ROLL, -accelRight * 40.0F);
		this.kick(SWAY, -accelRight * 0.5F);

		// what the rider is doing
		float cruise = Mth.clamp(forward / 0.45F, 0.0F, 1.0F);
		float boost = sprinting ? Mth.clamp((forward - 0.45F) / 0.3F, 0.0F, 1.0F) : 0.0F;
		float reverse = Mth.clamp(-forward / 0.2F, 0.0F, 1.0F);
		float climb = Mth.clamp(up / 0.3F, -1.0F, 1.0F);
		float sharp = Mth.clamp((Math.abs(turn) - 6.0F) / 6.0F, 0.0F, 1.0F);
		float bank = Mth.clamp(turn * Mth.lerp(sharp, 1.8F, 2.8F), -55.0F, 55.0F);
		float strafe = Mth.clamp(right / 0.3F, -1.0F, 1.0F);
		boolean low = ground < 2.5 && horizontal > 0.15F;
		boolean landing = rider != null && ground < 1.8 && up < -0.03F;
		float flinch = rider != null ? this.obstacleAhead(broom, position, velocity, horizontal) : 0.0F;
		float idle = rider == null || horizontal < 0.05F ? 1.0F : 0.0F;
		boolean lift = this.takeOff > 0;

		float pitch = -5.0F * cruise * (1.0F - boost) - 8.0F * reverse - 22.0F * climb - 10.0F * flinch
			+ (landing ? -8.0F : 0.0F) + (braking ? -8.0F : 0.0F) + (dead ? 25.0F : 0.0F);
		float roll = bank + strafe * 8.0F + idle * Mth.sin(age * 0.05F) * 1.5F
			+ danger * (Mth.sin(age * 0.31F) * 4.0F + Mth.sin(age * 0.53F) * 2.5F) + (dead ? 50.0F : 0.0F);
		float sink = -0.05F * sharp - (familiar ? 0.03F : 0.0F)
			+ (low ? (float) Mth.clamp((this.groundAverage - ground) * 0.5, -0.2, 0.2) : 0.0F)
			+ danger * Mth.sin(age * 0.23F) * 0.03F;
		this.spring(PITCH, pitch, 0.18F, 0.32F);
		this.spring(ROLL, roll, 0.16F, 0.3F);
		this.value[PITCH] = Mth.clamp(this.value[PITCH], -40.0F, 40.0F);
		this.value[ROLL] = Mth.clamp(this.value[ROLL], -65.0F, 65.0F);
		this.spring(LIFT, sink, 0.12F, 0.25F);
		this.spring(SURGE, 0.0F, 0.2F, 0.35F);
		this.spring(SWAY, idle * Mth.sin(age * 0.037F) * 0.01F, 0.2F, 0.35F);
		this.value[SHAKE] = Math.max(this.value[SHAKE] * 0.85F, boost * 0.7F + cruise * 0.12F + danger * 1.2F);
		this.approach(STREAM, Math.max(Mth.clamp((forward - 0.25F) / 0.55F, 0.0F, 1.0F), boost), 0.15F);
		this.swingLantern(forward, accelForward, accelRight);

		float lean = -3.0F + 14.0F * cruise * (1.0F - boost) + 34.0F * boost - 10.0F * reverse
			- 12.0F * Math.max(climb, 0.0F) + 8.0F * Math.max(-climb, 0.0F) + (low ? 5.0F : 0.0F)
			- (landing ? 10.0F : 0.0F) - 15.0F * flinch - (lift ? 12.0F : 0.0F) - (braking ? 8.0F : 0.0F)
			+ (familiar ? 2.0F : 0.0F) + (dead ? 25.0F : 0.0F);
		float riderRoll = Mth.clamp(turn * Mth.lerp(sharp, 1.3F, 2.2F), -38.0F, 38.0F)
			+ danger * (Mth.sin(age * 0.27F) * 5.0F + Mth.sin(age * 0.61F) * 3.0F) + (familiar ? -3.0F : 0.0F);
		this.spring(LEAN, lean, 0.15F, 0.28F);
		this.value[LEAN] = Mth.clamp(this.value[LEAN], -40.0F, 55.0F);
		this.spring(RIDER_ROLL, riderRoll, 0.15F, 0.28F);
		this.spring(SHIFT, strafe * 0.1F, 0.2F, 0.4F);
		this.spring(RIDER_LIFT, -0.07F * boost + idle * Mth.sin(age * 0.09F + 0.6F) * 0.01F, 0.2F, 0.3F);
		this.approach(LEGS, lift ? -0.6F : landing ? 1.0F : dead ? 0.8F : -0.35F * boost, 0.15F);
		this.approach(GRIP, danger > 0.3F ? 1.0F : 0.0F, 0.1F);
		this.approach(LIMP, dead ? 1.0F : 0.0F, 0.08F);
		this.value[MOUNT] = Math.min(this.value[MOUNT] + 0.1F, 1.0F);
		this.approach(GLANCE, familiar ? (float) Math.pow(Math.max(Mth.sin(age * 0.03F), 0.0F), 6.0) : 0.0F, 0.2F);

		this.lastPosition = position;
		this.lastVelocity = velocity;
		this.lastYRot = broom.getYRot();
		this.lastRider = rider;
		this.lastGround = ground;
	}

	private static Vec3 clamp(final Vec3 v, final double max) {
		double length = v.length();
		return length > max ? v.scale(max / length) : v;
	}

	/** The lantern as a pendulum hung from the bow: an acceleration a holds it at a/g off the vertical. */
	private void swingLantern(final float forward, final float accelForward, final float accelRight) {
		// stiffness 0.25/tick^2 is a period of about 0.6 s; a steady wind leans it back at speed
		this.speed[SWING_FORWARD] += -0.25F * this.value[SWING_FORWARD] + 3.1F * accelForward + 0.12F * forward * forward
			- 0.15F * this.speed[SWING_FORWARD];
		this.speed[SWING_SIDE] += -0.25F * this.value[SWING_SIDE] + 3.1F * accelRight - 0.15F * this.speed[SWING_SIDE];
		this.value[SWING_FORWARD] = Mth.clamp(this.value[SWING_FORWARD] + this.speed[SWING_FORWARD], -0.9F, 0.9F);
		this.value[SWING_SIDE] = Mth.clamp(this.value[SWING_SIDE] + this.speed[SWING_SIDE], -0.9F, 0.9F);
	}

	private double groundBelow(final Broom broom, final Vec3 position) {
		Vec3 from = position.add(0.0, 0.1, 0.0);
		HitResult hit = broom.level().clip(new ClipContext(from, position.add(0.0, -4.0, 0.0), ClipContext.Block.COLLIDER,
			ClipContext.Fluid.ANY, broom));
		return hit.getType() == HitResult.Type.MISS ? 4.0 : Math.max(position.y - hit.getLocation().y, 0.0);
	}

	/** 0..1 as a wall comes up ahead, within about half a second of flight. */
	private float obstacleAhead(final Broom broom, final Vec3 position, final Vec3 velocity, final float horizontal) {
		if (horizontal < 0.15F) {
			return 0.0F;
		}

		double range = 1.0 + horizontal * 10.0;
		Vec3 from = position.add(0.0, Broom.AXIS_HEIGHT + 0.3, 0.0);
		Vec3 to = from.add(velocity.x / horizontal * range, 0.0, velocity.z / horizontal * range);
		HitResult hit = broom.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, broom));
		return hit.getType() == HitResult.Type.MISS ? 0.0F : (float) Mth.clamp(1.0 - hit.getLocation().distanceTo(from) / range, 0.0, 1.0);
	}
}
