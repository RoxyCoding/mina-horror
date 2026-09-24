package chihalu.mina.horror.mixin;

import chihalu.mina.horror.terrain.SmoothGround;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ground around the columns marked with the terrain wand collides and outlines along its drawn slope instead of as a
 * cube ({@link SmoothGround#shapeFor}), and the open blocks the slope rises into collide with the rest of it
 * ({@link SmoothGround#fillFor}).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class GroundShapeMixin {
	@Shadow
	protected abstract BlockState asState();

	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void smoothCollision(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		VoxelShape shape = SmoothGround.shapeFor(this.asState(), level, pos);
		if (shape != null) cir.setReturnValue(shape);
	}

	@Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void smoothOutline(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		VoxelShape shape = SmoothGround.shapeFor(this.asState(), level, pos);
		if (shape != null) cir.setReturnValue(shape);
	}

	/** Air, plants and snow layers over a slope rising into them collide with that part of it as well. */
	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("RETURN"), cancellable = true)
	private void smoothFill(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		VoxelShape fill = SmoothGround.fillFor(this.asState(), level, pos);
		if (fill == null) return;
		VoxelShape own = cir.getReturnValue();
		cir.setReturnValue(own.isEmpty() ? fill : Shapes.or(own, fill));
	}

	/**
	 * Smoothed ground is no longer a full block: a player whose feet stand on one slope while its body reaches over
	 * the lowered edge of the next must not be pushed out of it as if walking into a wall.
	 */
	@Inject(method = "isSuffocating", at = @At("HEAD"), cancellable = true)
	private void smoothSuffocating(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (SmoothGround.shapeFor(this.asState(), level, pos) != null) cir.setReturnValue(false);
	}
}
