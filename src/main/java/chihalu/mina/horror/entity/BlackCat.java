package chihalu.mina.horror.entity;

import java.util.EnumSet;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.feline.CatSoundVariant;
import net.minecraft.world.entity.animal.feline.CatSoundVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Red-ribboned black cat modelled after the three-view sheet.
 * Wanders, is tempted and tamed with fish, follows its owner, sits on command and sits down to rest now
 * and then. While untamed it trails nearby players at a distance.
 */
public class BlackCat extends TamableAnimal {
	private static final CatSoundVariant.CatSoundSet SOUNDS = SoundEvents.CAT_SOUNDS.get(CatSoundVariants.SoundSet.CLASSIC).adultSounds();

	public BlackCat(final EntityType<? extends BlackCat> type, final Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Animal.createAnimalAttributes()
			.add(Attributes.MAX_HEALTH, 10.0)
			.add(Attributes.MOVEMENT_SPEED, 0.3);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new PanicGoal(this, 1.5));
		this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
		this.goalSelector.addGoal(3, new TemptGoal(this, 0.6, stack -> stack.is(ItemTags.CAT_FOOD), true));
		this.goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.0, 10.0F, 5.0F));
		this.goalSelector.addGoal(5, new RestGoal());
		this.goalSelector.addGoal(6, new TrailPlayerGoal());
		this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8, 1.0E-5F));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10.0F));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	@Override
	public InteractionResult mobInteract(final Player player, final InteractionHand hand) {
		ItemStack itemStack = player.getItemInHand(hand);
		if (this.isTame()) {
			if (this.isOwnedBy(player)) {
				if (this.isFood(itemStack) && this.getHealth() < this.getMaxHealth()) {
					if (!this.level().isClientSide()) {
						this.usePlayerItem(player, hand, itemStack);
						this.heal(2.0F);
						this.playEatingSound();
					}

					return InteractionResult.SUCCESS;
				}

				InteractionResult parentInteraction = super.mobInteract(player, hand);
				if (!parentInteraction.consumesAction()) {
					this.setOrderedToSit(!this.isOrderedToSit());
					return InteractionResult.SUCCESS;
				}

				return parentInteraction;
			}
		} else if (this.isFood(itemStack)) {
			if (!this.level().isClientSide()) {
				this.usePlayerItem(player, hand, itemStack);
				this.playEatingSound();
				if (this.random.nextInt(3) == 0) {
					this.tame(player);
					this.setOrderedToSit(true);
					this.level().broadcastEntityEvent(this, (byte) 7);
				} else {
					this.level().broadcastEntityEvent(this, (byte) 6);
				}
			}

			return InteractionResult.SUCCESS;
		}

		return super.mobInteract(player, hand);
	}

	@Override
	public boolean isFood(final ItemStack itemStack) {
		return itemStack.is(ItemTags.CAT_FOOD);
	}

	// No breeding: there is no kitten model.
	@Override
	public @Nullable AgeableMob getBreedOffspring(final ServerLevel level, final AgeableMob partner) {
		return null;
	}

	@Override
	public boolean canMate(final Animal partner) {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(final double distanceSqr) {
		return false;
	}

	@Override
	protected @Nullable SoundEvent getAmbientSound() {
		Holder<SoundEvent> sound;
		if (!this.isTame()) {
			sound = SOUNDS.strayAmbientSound();
		} else if (this.isInSittingPose()) {
			sound = SOUNDS.purrSound();
		} else {
			sound = this.random.nextInt(4) == 0 ? SOUNDS.purreowSound() : SOUNDS.ambientSound();
		}

		return sound.value();
	}

	@Override
	public int getAmbientSoundInterval() {
		return 120;
	}

	@Override
	protected SoundEvent getHurtSound(final DamageSource source) {
		return SOUNDS.hurtSound().value();
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SOUNDS.deathSound().value();
	}

	@Override
	protected void playEatingSound() {
		this.playSound(SOUNDS.eatSound().value(), 1.0F, 1.0F);
	}

	/** Sits down for a while when idle, like a cat settling in. */
	private class RestGoal extends Goal {
		private int remaining;

		RestGoal() {
			this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
		}

		@Override
		public boolean canUse() {
			BlackCat cat = BlackCat.this;
			return !cat.isOrderedToSit() && cat.onGround() && !cat.isInWater() && cat.getNavigation().isDone()
				&& cat.getRandom().nextInt(reducedTickDelay(150)) == 0;
		}

		@Override
		public boolean canContinueToUse() {
			BlackCat cat = BlackCat.this;
			return this.remaining > 0 && !cat.isOrderedToSit() && cat.hurtTime == 0 && !cat.isInWater();
		}

		@Override
		public void start() {
			this.remaining = adjustedTickDelay(100 + BlackCat.this.getRandom().nextInt(300));
			BlackCat.this.getNavigation().stop();
			BlackCat.this.setInSittingPose(true);
		}

		@Override
		public void tick() {
			this.remaining--;
		}

		@Override
		public void stop() {
			if (!BlackCat.this.isOrderedToSit()) {
				BlackCat.this.setInSittingPose(false);
			}
		}
	}

	/** While untamed, keeps a few blocks behind the nearest player. */
	private class TrailPlayerGoal extends Goal {
		private static final double START_DISTANCE = 5.0;
		private static final double STOP_DISTANCE = 2.5;
		private static final double MAX_DISTANCE = 14.0;
		private @Nullable Player target;
		private int repathDelay;

		TrailPlayerGoal() {
			this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			BlackCat cat = BlackCat.this;
			if (cat.isTame() || cat.getRandom().nextInt(reducedTickDelay(20)) != 0) {
				return false;
			}

			Player player = cat.level().getNearestPlayer(cat, MAX_DISTANCE);
			if (player == null || player.isSpectator() || cat.distanceTo(player) < START_DISTANCE) {
				return false;
			}

			this.target = player;
			return true;
		}

		@Override
		public boolean canContinueToUse() {
			Player player = this.target;
			if (player == null || !player.isAlive() || player.isSpectator()) {
				return false;
			}

			double distance = BlackCat.this.distanceTo(player);
			return distance > STOP_DISTANCE && distance < MAX_DISTANCE + 4.0;
		}

		@Override
		public void start() {
			this.repathDelay = 0;
		}

		@Override
		public void stop() {
			this.target = null;
			BlackCat.this.getNavigation().stop();
		}

		@Override
		public void tick() {
			Player player = this.target;
			if (player == null) {
				return;
			}

			BlackCat.this.getLookControl().setLookAt(player, 10.0F, BlackCat.this.getMaxHeadXRot());
			if (--this.repathDelay <= 0) {
				this.repathDelay = adjustedTickDelay(10);
				BlackCat.this.getNavigation().moveTo(player, 0.8);
			}
		}
	}
}
