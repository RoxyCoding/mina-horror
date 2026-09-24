package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.tree.NaturalTreeModels;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium replaces Block.shouldRenderFace with its own check, so {@link TreeFaceCullingMixin} does not apply there:
 * faces touching a rounded trunk must still be drawn. Only applied when Sodium is installed.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext")
public abstract class SodiumTreeFaceCullingMixin {
	@Shadow
	protected BlockAndTintGetter level;
	@Shadow
	protected BlockPos pos;

	@Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, require = 0)
	private void naturalTreeFaces(final Direction direction, final CallbackInfoReturnable<Boolean> cir) {
		if (NaturalTreeModels.replaces(this.level.getBlockState(this.pos.relative(direction)))) {
			cir.setReturnValue(true);
		}
	}
}
