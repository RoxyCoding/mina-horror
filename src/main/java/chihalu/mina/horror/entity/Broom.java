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
 * A witch's broom that hovers and flies with one rider. Flying works like riding a happy ghast: forward follows the
 * rider's gaze (looking down dives, looking up climbs), sideways strafes, jump rises and sprint speeds up; sneaking
 * dismounts. Nobody aboard, it sinks gently to the ground and hovers there.
 * The client also keeps the visual state: the hover bob, the bank into turns and the lantern's swing.
 */
public class Broom extends VehicleEntity {
	/** Height of the handle's axis (and the seat) above the entity's position. */
	public static final float AXIS_HEIGHT = 0.45F;
	private static final double CRUISE_SPEED = 0.45;
	private static final double SPRINT_SPEED = 0.85;
	private static final double RESPONSE = 0.12;
	private static final double SINK_SPEED = 0.05;

	// client visuals, previous and current tick
	public float bank;
	public float bankO;
	public float pitch;
	public float pitchO;
	public float swingForward;
	public float swingForwardO;
	public float swingSide;
	public float swingSideO;
	private float swingForwardSpeed;
	private float swingSideSpeed;
	private @Nullable Vec3 lastPosition;
	private Vec3 lastVelocity = Vec3.ZERO;
	private float lastYRot;

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

			this.move(MoverType.SELF, this.getDeltaMovement());
		} else {
			this.setDeltaMovement(Vec3.ZERO);
		}

		this.applyEffectsFromBlocks();
		if (this.level().isClientSide()) {
			this.animate();
		}
	}

	private void fly(final Player rider) {
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
		Vec3 velocity = this.getDeltaMovement();
		this.setDeltaMovement(velocity.add(target.subtract(velocity).scale(RESPONSE)));
		// the broom swings round after its rider's gaze
		this.setYRot(this.getYRot() + Mth.wrapDegrees(rider.getYRot() - this.getYRot()) * 0.35F);
	}

	private void drift() {
		Vec3 velocity = this.getDeltaMovement();
		double fall = this.onGround() ? 0.0 : Math.max(velocity.y - 0.004, -SINK_SPEED);
		this.setDeltaMovement(velocity.x * 0.85, fall, velocity.z * 0.85);
	}

	/** Bank into turns, nose up while climbing, and swing the lantern like a pendulum hung from the bow. */
	private void animate() {
		this.bankO = this.bank;
		this.pitchO = this.pitch;
		this.swingForwardO = this.swingForward;
		this.swingSideO = this.swingSide;
		Vec3 position = this.position();
		Vec3 velocity = this.lastPosition == null ? Vec3.ZERO : position.subtract(this.lastPosition);
		Vec3 acceleration = velocity.subtract(this.lastVelocity);
		this.lastPosition = position;
		this.lastVelocity = velocity;
		float turn = Mth.wrapDegrees(this.getYRot() - this.lastYRot);
		this.lastYRot = this.getYRot();

		this.bank += (Mth.clamp(turn * 1.6F, -22.0F, 22.0F) - this.bank) * 0.2F;
		this.pitch += ((float) Mth.clamp(-velocity.y * 40.0, -18.0, 18.0) - this.pitch) * 0.2F;

		float yaw = this.getYRot() * Mth.DEG_TO_RAD;
		double forward = -acceleration.x * Mth.sin(yaw) + acceleration.z * Mth.cos(yaw);
		double left = acceleration.x * Mth.cos(yaw) + acceleration.z * Mth.sin(yaw);
		// stiffness 0.25/tick^2 (a period of about 0.6 s); an acceleration a holds it at a/g off the vertical
		this.swingForwardSpeed += (float) (-0.25F * this.swingForward + 3.1 * forward) - 0.15F * this.swingForwardSpeed;
		this.swingSideSpeed += (float) (-0.25F * this.swingSide - 3.1 * left) - 0.15F * this.swingSideSpeed;
		this.swingForward = Mth.clamp(this.swingForward + this.swingForwardSpeed, -0.9F, 0.9F);
		this.swingSide = Mth.clamp(this.swingSide + this.swingSideSpeed, -0.9F, 0.9F);
	}

	@Override
	protected void checkFallDamage(final double ya, final boolean onGround, final BlockState onState, final BlockPos pos) {
		// it flies: landing, however fast, hurts neither broom nor rider
		this.resetFallDistance();
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
		return this.getPassengers().isEmpty();
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return this.getFirstPassenger() instanceof Player rider ? rider : super.getControllingPassenger();
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dimensions, final float scale) {
		// a sitting rider's thighs rest on the handle
		return new Vec3(0.0, AXIS_HEIGHT, 0.0);
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
