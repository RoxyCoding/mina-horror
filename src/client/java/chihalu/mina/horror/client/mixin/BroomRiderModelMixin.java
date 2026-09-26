package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.BroomRiderPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A broom's rider holds the handle and sits astride it (or steps down off it). */
@Mixin(PlayerModel.class)
public class BroomRiderModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void rideBroom(AvatarRenderState state, CallbackInfo ci) {
		BroomRiderPose.pose((PlayerModel) (Object) this, state);
	}
}
