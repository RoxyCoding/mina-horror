package chihalu.mina.horror.mixin;

import chihalu.mina.horror.terrain.SlopeWalking;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.entity.MoverType;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class SlopeWalkingMixin {
    @Unique private boolean mina$walking;

    @Inject(method = "move", at = @At("HEAD"))
    private void beginMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        mina$walking=false;
    }
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void walkSlope(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 result = SlopeWalking.move((Entity)(Object)this, movement);
        if (result != null) {
            mina$walking=true;
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void finishMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (!mina$walking) return;
        Entity entity=(Entity)(Object)this;
        entity.setOnGround(true);
        entity.verticalCollisionBelow=true;
        entity.resetFallDistance();
        Vec3 velocity=entity.getDeltaMovement();
        entity.setDeltaMovement(velocity.x,0,velocity.z);
        mina$walking=false;
    }
}
