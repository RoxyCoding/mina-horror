package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.tree.NaturalTreeModels;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The ground and nearby blocks remain visible through the space around rounded trunks. */
@Mixin(Block.class)
public class TreeFaceCullingMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private static void naturalTreeFaces(BlockState state, BlockState neighbor, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (NaturalTreeModels.replaces(neighbor)) cir.setReturnValue(true);
	}
}
