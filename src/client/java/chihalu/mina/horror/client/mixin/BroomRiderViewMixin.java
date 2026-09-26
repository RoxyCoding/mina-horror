package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.BroomRiderPose;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A broom's rider sees in first person what their body does: the bank, pitch, lean and bob of the flight. */
@Mixin(GameRenderer.class)
public class BroomRiderViewMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void rideBroom(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		BroomRiderPose.firstPersonView(cameraState, poseStack);
	}
}
