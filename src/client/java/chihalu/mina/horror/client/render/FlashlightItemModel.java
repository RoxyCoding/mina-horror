package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.item.FlashlightItem;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;

/**
 * The "mina-horror:flashlight" item model: the flashlight mesh ({@link FlashlightRenderer}) on a base model that holds
 * the display transforms. Unlike a plain special model it sees who holds the item and how, so the renderer knows
 * when it is drawing the local player's lit flashlight and can report the lens to {@link FlashlightBeam}.
 */
public final class FlashlightItemModel {
	/** Hand (0 main, 1 off) and view of the item being updated, read by FlashlightRenderer.extractArgument. */
	private static FlashlightRenderer.State current = FlashlightRenderer.State.OFF;

	private FlashlightItemModel() {
	}

	public static void register() {
		ItemModels.ID_MAPPER.put(MinaHorror.id("flashlight"), Unbaked.MAP_CODEC);
	}

	static FlashlightRenderer.State state(final ItemStack stack) {
		return FlashlightItem.isOn(stack) ? current : FlashlightRenderer.State.OFF;
	}

	private static FlashlightRenderer.State stateFor(final ItemDisplayContext context, final Object owner) {
		Minecraft minecraft = Minecraft.getInstance();
		if (owner == null || owner != minecraft.player) {
			return FlashlightRenderer.State.LIT;
		}

		boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
		boolean held = firstPerson
			? context.firstPerson()
			// the inventory screen draws the player again, away from the world
			: minecraft.gui.screen() == null
				&& (context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
		if (!held) {
			return FlashlightRenderer.State.LIT;
		}

		boolean mainIsLeft = minecraft.player.getMainArm() == HumanoidArm.LEFT;
		return new FlashlightRenderer.State(true, context.leftHand() == mainIsLeft ? 0 : 1, firstPerson);
	}

	public record Unbaked(Identifier base) implements ItemModel.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(
			i -> i.group(Identifier.CODEC.fieldOf("base").forGetter(Unbaked::base)).apply(i, Unbaked::new)
		);

		@Override
		public void resolveDependencies(final ResolvableModel.Resolver resolver) {
			resolver.markDependency(this.base);
		}

		@Override
		public ItemModel bake(final ItemModel.BakingContext context, final Matrix4fc transformation) {
			ItemModel mesh = new SpecialModelWrapper.Unbaked(this.base, Optional.empty(), new FlashlightRenderer.Unbaked())
				.bake(context, transformation);
			return (output, item, resolver, displayContext, level, owner, seed) -> {
				current = stateFor(displayContext, owner);
				try {
					mesh.update(output, item, resolver, displayContext, level, owner, seed);
				} finally {
					current = FlashlightRenderer.State.OFF;
				}
			};
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
