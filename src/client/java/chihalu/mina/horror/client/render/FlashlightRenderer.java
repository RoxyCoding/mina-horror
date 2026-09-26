package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.item.FlashlightItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Draws the flashlight mesh for the "mina-horror:flashlight" special item model. The argument is whether the light
 * is on: then the reflector, LED and lens use their lit texture and full brightness.
 */
public class FlashlightRenderer implements SpecialModelRenderer<Boolean> {
	private static final Identifier TEXTURE = MinaHorror.id("textures/model/flashlight.png");

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(MinaHorror.id("flashlight"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(
		final @Nullable Boolean on, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector,
		final int lightCoords, final int overlayCoords, final boolean hasFoil, final int outlineColor
	) {
		FlashlightMesh mesh = FlashlightMesh.get();
		boolean lit = on != null && on;
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
	public Boolean extractArgument(final ItemStack stack) {
		return FlashlightItem.isOn(stack);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked<Boolean> {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<Boolean> bake(final SpecialModelRenderer.BakingContext context) {
			return new FlashlightRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
