package chihalu.mina.horror.item;

import chihalu.mina.horror.entity.Broom;
import chihalu.mina.horror.registry.MinaHorrorEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Sets a flying broom down where the player clicks, lying along their gaze; right-click the broom to ride it. */
public class BroomItem extends Item {
	public BroomItem(final Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(final UseOnContext context) {
		Level level = context.getLevel();
		Player player = context.getPlayer();
		ItemStack stack = context.getItemInHand();
		Broom broom = MinaHorrorEntities.BROOM.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (broom == null) {
			return InteractionResult.FAIL;
		}

		Vec3 at = context.getClickLocation();
		broom.snapTo(at.x, at.y, at.z, player != null ? player.getYRot() : 0.0F, 0.0F);
		if (!level.noCollision(broom, broom.getBoundingBox())) {
			return InteractionResult.FAIL;
		}

		if (level instanceof ServerLevel serverLevel) {
			EntityType.<Broom>createDefaultStackConfig(serverLevel, stack, player).apply(broom);
			serverLevel.addFreshEntity(broom);
			serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, at);
			stack.consume(1, player);
		}

		return InteractionResult.SUCCESS;
	}
}
