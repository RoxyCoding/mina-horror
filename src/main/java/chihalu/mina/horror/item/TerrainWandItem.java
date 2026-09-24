package chihalu.mina.horror.item;

import chihalu.mina.horror.terrain.SmoothColumns;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Chooses where the ground is smoothed, a brush at a time: right-click the ground to smooth there (hold the button and
 * sweep to paint), sneak and right-click it to put it back. Sneak and right-click the air to change the brush size.
 */
public class TerrainWandItem extends Item {
	/** Brush sizes in blocks across; the largest is rounded off at its corners. */
	private static final int[] SIZES = {1, 3, 5};
	/** Index into {@link #SIZES} for each player who changed it. */
	private static final Map<UUID, Integer> BRUSHES = new ConcurrentHashMap<>();

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
		boolean smooth = !player.isShiftKeyDown();
		int size = SIZES[BRUSHES.getOrDefault(player.getUUID(), 0)];
		int radius = size / 2;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius + 1) continue;
				SmoothColumns.set(level, pos.getX() + dx, pos.getZ() + dz, smooth);
			}
		}
		player.sendOverlayMessage(Component.translatable(smooth ? "item.mina-horror.terrain_wand.smoothed" : "item.mina-horror.terrain_wand.restored", size, size));
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!player.isShiftKeyDown()) return InteractionResult.PASS;
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		int next = (BRUSHES.getOrDefault(player.getUUID(), 0) + 1) % SIZES.length;
		BRUSHES.put(player.getUUID(), next);
		player.sendOverlayMessage(Component.translatable("item.mina-horror.terrain_wand.brush", SIZES[next], SIZES[next]));
		return InteractionResult.SUCCESS;
	}
}
