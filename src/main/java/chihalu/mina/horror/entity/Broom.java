package chihalu.mina.horror.entity;

import chihalu.mina.horror.registry.MinaHorrorItems;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A witch's broom that hovers and flies with its rider. Flying works like riding a happy ghast: forward follows the
 * rider's gaze (looking down dives, looking up climbs), back brakes and reverses, sideways strafes, jump rises and
 * sprint boosts; sneaking dismounts. It bounces off what it flies into, and with a dead rider it loses control and
 * falls. Nobody aboard, it sinks gently to the ground and hovers there. The rider's black cat, if it is following
 * them, hops on behind when they mount and off when they get down.
 * The client keeps the broom's and the rider's motion in {@link #motion}.
 */
public class Broom extends VehicleEntity {
	/** Height of the handle's axis (and the seat) above the entity's position. */
	public static final float AXIS_HEIGHT = 0.6F;
	private static final double CRUISE_SPEED = 0.45;
	private static final double SPRINT_SPEED = 0.85;
	private static final double RESPONSE = 0.12;
	private static final double BRAKING = 0.2;
	private static final double SINK_SPEED = 0.05;
	/** Where the familiar sits: on the binding behind the rider. */
	private static final Vec3 FAMILIAR_SEAT = new Vec3(0.0, AXIS_HEIGHT + 0.11, -0.45);

	public final BroomMotion motion = new BroomMotion();
	private int lastRiderHurtTime;

	public Broom(final EntityType<? extends Broom> type, final Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	@Override
	public void tick() {
		if (this.getHurtTime() > 0) {
			this.setHurtTime(this.getHurtTime() - 1);
		}

		if (this.getDamage() > 0.0F) {
			this.setDamage(this.getDamage() - 1.0F);
		}

		super.tick();
		if (this.isLocalInstanceAuthoritative()) {
			if (this.getControllingPassenger() instanceof Player rider) {
				this.fly(rider);
			} else {
				this.drift();
			}

			Vec3 before = this.getDeltaMovement();
			this.move(MoverType.SELF, before);
			double speed = before.horizontalDistance();
			if (this.horizontalCollision && speed > 0.2) {
				// light knocks bounce it back, hard ones stop it dead
				double bounce = speed > 0.55 ? -0.08 : -0.3;
				this.setDeltaMovement(before.x * bounce, this.getDeltaMovement().y, before.z * bounce);
			}
		} else {
			this.setDeltaMovement(Vec3.ZERO);
		}

		this.applyEffectsFromBlocks();
		if (this.level().isClientSide()) {
			this.motion.tick(this);
		} else {
			this.tickPassengers();
		}
	}

	private void fly(final Player rider) {
		Vec3 velocity = this.getDeltaMovement();
		if (rider.isDeadOrDying()) {
			// out of control: it pitches over and falls
			this.setDeltaMovement(velocity.x * 0.96, Math.max(velocity.y - 0.03, -1.2), velocity.z * 0.96);
			this.setYRot(this.getYRot() + 3.0F);
			return;
		}

		Vec3 wish = Vec3.ZERO;
		if (rider.zza != 0.0F) {
			Vec3 gaze = Vec3.directionFromRotation(rider.getXRot(), rider.getYRot());
			wish = gaze.scale(rider.zza > 0.0F ? rider.zza : rider.zza * 0.5F);
		}

		if (rider.xxa != 0.0F) {
			wish = wish.add(Vec3.directionFromRotation(0.0F, rider.getYRot() - 90.0F).scale(rider.xxa));
		}

		if (rider.isJumping()) {
			wish = wish.add(0.0, 0.6, 0.0);
		}

		if (wish.lengthSqr() > 1.0) {
			wish = wish.normalize();
		}

		Vec3 target = wish.scale(rider.isSprinting() ? SPRINT_SPEED : CRUISE_SPEED);
		boolean braking = rider.zza < 0.0F && velocity.dot(this.getLookAngle()) > 0.1;
		this.setDeltaMovement(velocity.add(target.subtract(velocity).scale(braking ? BRAKING : RESPONSE)));
		// the broom swings round after its rider's gaze
		this.setYRot(this.getYRot() + Mth.wrapDegrees(rider.getYRot() - this.getYRot()) * 0.35F);
	}

	private void drift() {
		Vec3 velocity = this.getDeltaMovement();
		double fall = this.onGround() ? 0.0 : Math.max(velocity.y - 0.004, -SINK_SPEED);
		this.setDeltaMovement(velocity.x * 0.85, fall, velocity.z * 0.85);
	}

	/** Server: the familiar boards with its master and leaves with them; hits on the rider are shown to all. */
	private void tickPassengers() {
		Player rider = this.getControllingPassenger() instanceof Player player ? player : null;
		for (Entity passenger : this.getPassengers()) {
			if (passenger instanceof BlackCat cat && rider == null) {
				cat.stopRiding();
				cat.setInSittingPose(cat.isOrderedToSit());
			}
		}

		if (rider != null && this.getPassengers().size() == 1 && this.tickCount % 10 == 0) {
			for (BlackCat cat : this.level().getEntitiesOfClass(BlackCat.class, this.getBoundingBox().inflate(6.0))) {
				if (cat.isOwnedBy(rider) && !cat.isOrderedToSit() && !cat.isPassenger() && cat.isAlive() && cat.startRiding(this)) {
					cat.setInSittingPose(true);
					break;
				}
			}
		}

		LivingEntity hurt = this.getFirstPassenger() instanceof LivingEntity living ? living : null;
		int hurtTime = hurt == null ? 0 : hurt.hurtTime;
		if (hurt != null && hurtTime > this.lastRiderHurtTime) {
			// hurtDir points at the damage, relative to the rider's heading (0 on its left, 90 ahead)
			float side = Mth.wrapDegrees(hurt.getHurtDir() - 90.0F + hurt.getYRot() - this.getYRot());
			byte event = Math.abs(side) < 45.0F ? BroomMotion.HIT_FRONT : Math.abs(side) > 135.0F ? BroomMotion.HIT_BACK
				: side < 0.0F ? BroomMotion.HIT_LEFT : BroomMotion.HIT_RIGHT;
			this.level().broadcastEntityEvent(this, event);
		}

		this.lastRiderHurtTime = hurtTime;
	}

	@Override
	public void handleEntityEvent(final byte id) {
		if (id >= BroomMotion.HIT_FRONT && id <= BroomMotion.HIT_RIGHT) {
			this.motion.hit(id);
		} else {
			super.handleEntityEvent(id);
		}
	}

	@Override
	protected void checkFallDamage(final double ya, final boolean onGround, final BlockState onState, final BlockPos pos) {
		// it flies: landing, however fast, hurts neither broom nor rider
		this.resetFallDistance();
	}

	@Override
	public boolean canSprint() {
		// the rider's sprint is the broom's boost
		return true;
	}

	@Override
	public boolean isFlyingVehicle() {
		return true;
	}

	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		InteractionResult result = super.interact(player, hand, location);
		if (result != InteractionResult.PASS) {
			return result;
		}

		if (player.isSecondaryUseActive()) {
			return InteractionResult.PASS;
		}

		return !this.level().isClientSide() && !player.startRiding(this) ? InteractionResult.PASS : InteractionResult.SUCCESS;
	}

	@Override
	protected boolean canAddPassenger(final Entity passenger) {
		// a rider, and then perhaps their familiar
		return this.getPassengers().isEmpty()
			|| passenger instanceof BlackCat && this.getPassengers().size() == 1 && this.getFirstPassenger() instanceof Player;
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return this.getFirstPassenger() instanceof Player rider ? rider : super.getControllingPassenger();
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dimensions, final float scale) {
		if (passenger instanceof BlackCat) {
			return FAMILIAR_SEAT.yRot(-this.getYRot() * Mth.DEG_TO_RAD);
		}

		// a sitting rider's thighs rest on top of the handle
		return new Vec3(0.0, AXIS_HEIGHT + 0.02, 0.0);
	}

	@Override
	protected void positionRider(final Entity passenger, final Entity.MoveFunction moveFunction) {
		super.positionRider(passenger, moveFunction);
		passenger.setYBodyRot(this.getYRot());
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean canBeCollidedWith(final @Nullable Entity other) {
		return false;
	}

	@Override
	protected Item getDropItem() {
		return MinaHorrorItems.BROOM;
	}

	@Override
	public ItemStack getPickResult() {
		return new ItemStack(MinaHorrorItems.BROOM);
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
	}
}
