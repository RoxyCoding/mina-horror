package chihalu.mina.horror.item;

import chihalu.mina.horror.terrain.SmoothRegions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Chooses where the ground is smoothed: right-click two blocks to set the corners of a rectangle (all heights), or
 * sneak and right-click to remove the areas covering a block.
 */
public class TerrainWandItem extends Item {
	/** First corner of each player's selection in progress. */
	private static final Map<UUID, BlockPos> FIRST_CORNERS = new ConcurrentHashMap<>();

	public TerrainWandItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) return InteractionResult.PASS;
		Level level = context.getLevel();
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		BlockPos pos = context.getClickedPos();
		UUID id = player.getUUID();

		if (player.isShiftKeyDown()) {
			FIRST_CORNERS.remove(id);
			int removed = SmoothRegions.removeAt(level, pos.getX(), pos.getZ());
			player.sendOverlayMessage(removed > 0
				? Component.translatable("item.mina-horror.terrain_wand.removed", removed)
				: Component.translatable("item.mina-horror.terrain_wand.nothing_here"));
			return InteractionResult.SUCCESS;
		}

		BlockPos first = FIRST_CORNERS.remove(id);
		if (first == null) {
			FIRST_CORNERS.put(id, pos.immutable());
			player.sendOverlayMessage(Component.translatable("item.mina-horror.terrain_wand.first", pos.getX(), pos.getZ()));
			return InteractionResult.SUCCESS;
		}
		SmoothRegions.Area area = SmoothRegions.Area.between(first, pos);
		if (area.width() > SmoothRegions.MAX_SIZE || area.depth() > SmoothRegions.MAX_SIZE) {
			player.sendOverlayMessage(Component.translatable("item.mina-horror.terrain_wand.too_large", SmoothRegions.MAX_SIZE));
			return InteractionResult.SUCCESS;
		}
		SmoothRegions.add(level, area);
		player.sendOverlayMessage(Component.translatable("item.mina-horror.terrain_wand.added", area.width(), area.depth()));
		return InteractionResult.SUCCESS;
	}
}
