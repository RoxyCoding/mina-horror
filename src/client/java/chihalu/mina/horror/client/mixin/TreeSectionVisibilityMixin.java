package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.tree.NaturalTreeModels;
import chihalu.mina.horror.client.render.tree.NaturalTrees;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rounded trunks must not hide entire chunk sections behind a wall of vanilla full cubes. Also marks the section build,
 * so a whole tree is recognised once per section instead of once per block.
 */
@Mixin(SectionCompiler.class)
public class TreeSectionVisibilityMixin {
	@Inject(method = "compile", at = @At("HEAD"))
	private void naturalTreeSectionStart(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		NaturalTrees.beginSection();
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void naturalTreeSectionEnd(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		NaturalTrees.endSection();
	}

	@Redirect(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"))
	private boolean naturalTreeVisibility(BlockState state) {
		return !NaturalTreeModels.replaces(state) && state.isSolidRender();
	}
}
