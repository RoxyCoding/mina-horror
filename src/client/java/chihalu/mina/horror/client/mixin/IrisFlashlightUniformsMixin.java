package chihalu.mina.horror.client.mixin;

import chihalu.mina.horror.client.render.FlashlightBeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every Iris shader program the flashlight beam uniforms (see FlashlightBeam). */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.IrisExclusiveUniforms")
public abstract class IrisFlashlightUniformsMixin {
	@Inject(method = "addIrisExclusiveUniforms", at = @At("TAIL"))
	private static void mina$addFlashlightUniforms(@Coerce Object uniforms, @Coerce Object notifier, CallbackInfo ci) {
		FlashlightBeam.registerUniforms(uniforms);
	}
}
