package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Draws the flashlight mesh for {@link FlashlightItemModel}. While the light is on the reflector, LED and lens use
 * their lit texture and full brightness, and the local player's flashlights report their lens to FlashlightBeam.
 */
public class FlashlightRenderer implements SpecialModelRenderer<FlashlightRenderer.State> {
	private static final Identifier TEXTURE = MinaHorror.id("textures/model/flashlight.png");

	/** on: switched on; hand: 0 main, 1 off when held by the local player in the current view, else -1. */
	public record State(boolean on, int hand, boolean firstPerson) {
		public static final State OFF = new State(false, -1, false);
		public static final State LIT = new State(true, -1, false);
	}

	@Override
	public void submit(
		final @Nullable State state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector,
		final int lightCoords, final int overlayCoords, final boolean hasFoil, final int outlineColor
	) {
		FlashlightMesh mesh = FlashlightMesh.get();
		boolean lit = state != null && state.on();
		if (lit && state.hand() >= 0) {
			FlashlightBeam.capture(state.hand(), poseStack.last().pose(), state.firstPerson());
		}

		int glowLight = lit ? LightCoordsUtil.FULL_BRIGHT : lightCoords;
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
			(pose, buffer) -> {
				mesh.render(pose, buffer, FlashlightMesh.SOLID, false, lightCoords, overlayCoords);
				mesh.render(pose, buffer, FlashlightMesh.GLOW, lit, glowLight, overlayCoords);
			});
		submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE),
			(pose, buffer) -> mesh.render(pose, buffer, FlashlightMesh.GLASS, lit, glowLight, overlayCoords));
	}

	@Override
	public void getExtents(final Consumer<Vector3fc> output) {
		FlashlightMesh.get().getExtents(output);
	}

	@Override
	public State extractArgument(final ItemStack stack) {
		return FlashlightItemModel.state(stack);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked<State> {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<State> bake(final SpecialModelRenderer.BakingContext context) {
			return new FlashlightRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
