package chihalu.mina.horror.mixin;

import chihalu.mina.horror.terrain.SmoothGround;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ground in the areas chosen with the terrain wand collides and outlines along its drawn slope instead of as a cube
 * ({@link SmoothGround#shapeFor}).
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

	/** A slope may rise above its block, so entities standing over it must still test it. */
	@Inject(method = "hasLargeCollisionShape", at = @At("HEAD"), cancellable = true)
	private void smoothLargeShape(CallbackInfoReturnable<Boolean> cir) {
		if (SmoothGround.isGroundBlock(this.asState().getBlock())) cir.setReturnValue(true);
	}
}
