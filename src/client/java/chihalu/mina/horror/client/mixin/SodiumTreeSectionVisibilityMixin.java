package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.tree.NaturalTreeModels;
import chihalu.mina.horror.client.render.tree.NaturalTrees;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium's counterpart of {@link TreeSectionVisibilityMixin}. Only applied when Sodium is installed. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask")
public abstract class SodiumTreeSectionVisibilityMixin {
	private static final String EXECUTE = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";

	@Inject(method = EXECUTE, at = @At("HEAD"), require = 0)
	private void naturalTreeSectionStart(CallbackInfoReturnable<?> cir) {
		NaturalTrees.beginSection();
	}

	@Inject(method = EXECUTE, at = @At("RETURN"), require = 0)
	private void naturalTreeSectionEnd(CallbackInfoReturnable<?> cir) {
		NaturalTrees.endSection();
	}

	@Redirect(
		method = EXECUTE,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"),
		require = 0
	)
	private boolean naturalTreeVisibility(final BlockState state) {
		return !NaturalTreeModels.replaces(state) && state.isSolidRender();
	}
}
