package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.BroomRiderPose;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A broom's rider is carried with the broom's motion and leans about the hips. */
@Mixin(AvatarRenderer.class)
public class BroomRiderRotationsMixin {
	@Inject(
		method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
		at = @At("TAIL")
	)
	private void rideBroom(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		BroomRiderPose.rotations(state, poseStack);
	}
}
