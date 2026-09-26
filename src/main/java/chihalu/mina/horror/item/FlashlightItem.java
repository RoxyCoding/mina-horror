package chihalu.mina.horror.item;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.registry.MinaHorrorComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Tactical LED flashlight. Right-click clicks the tail switch on or off; the lens lights up while it is on.
 * While on, the stack also shows the flashlight_on item model: Iris reports held items by their model, which is
 * how the MinaRealism shader pack knows to cast the beam (item.properties).
 */
public class FlashlightItem extends Item {
	public FlashlightItem(Properties properties) {
		super(properties);
	}

	public static boolean isOn(ItemStack stack) {
		return stack.getOrDefault(MinaHorrorComponents.FLASHLIGHT_ON, false);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide()) {
			ItemStack stack = player.getItemInHand(hand);
			boolean on = !isOn(stack);
			stack.set(MinaHorrorComponents.FLASHLIGHT_ON, on);
			stack.set(DataComponents.ITEM_MODEL, MinaHorror.id(on ? "flashlight_on" : "flashlight"));
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.STONE_BUTTON_CLICK_ON,
				SoundSource.PLAYERS, 0.5F, on ? 1.9F : 1.6F);
		}

		// a thumb on the switch, not a swing of the arm
		return InteractionResult.CONSUME;
	}
}
