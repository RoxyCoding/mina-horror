package chihalu.mina.horror.client.render;

import chihalu.mina.horror.MinaHorror;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Where the local player's flashlights shine from, taken from where their lenses were last drawn, for the MinaRealism
 * shader pack. Iris gets them as the uniforms minaFlashlightPos0/1 and minaFlashlightDir0/1 (hand 0 main, 1 off):
 * view-space lens position and beam direction, with w = 1 while fresh. Stored in view space so the shaders place them
 * with the current frame's camera.
 */
public final class FlashlightBeam {
	/** Centre of the lens in item model space (see tools/generate_flashlight.py). */
	private static final Vector3f LENS = new Vector3f(0.5F, 0.5F, 0.07F);
	/** Blocks ahead where a first-person beam crosses the line of sight. */
	private static final float AIM = 12.0F;
	/** After this long without being drawn (hand hidden, screen open) the shaders fall back to their own guess. */
	private static final long STALE_NANOS = 200_000_000L;
	private static final Vector3f[] POSITION = {new Vector3f(), new Vector3f()};
	private static final Vector3f[] DIRECTION = {new Vector3f(), new Vector3f()};
	private static final long[] DRAWN = new long[2];
	private static Method shadowPass;

	private FlashlightBeam() {
	}

	/**
	 * Records the lens drawn with the given pose. First-person poses are already in view space and aim at the
	 * crosshair; third-person ones are camera-relative world space and point where the model points.
	 */
	static void capture(final int hand, final Matrix4fc pose, final boolean firstPerson) {
		if (inShadowPass()) {
			return;
		}

		Vector3f position = pose.transformPosition(LENS, new Vector3f());
		Vector3f direction;
		if (firstPerson) {
			direction = new Vector3f(0.0F, 0.0F, -AIM).sub(position).normalize();
		} else {
			direction = pose.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
			Quaternionf toView = Minecraft.getInstance().gameRenderer.mainCamera().rotation().conjugate(new Quaternionf());
			position.rotate(toView);
			direction.rotate(toView);
		}

		POSITION[hand].set(position);
		DIRECTION[hand].set(direction);
		DRAWN[hand] = System.nanoTime();
	}

	private static Vector4f uniform(final Vector3f[] values, final int hand) {
		boolean fresh = DRAWN[hand] != 0 && System.nanoTime() - DRAWN[hand] < STALE_NANOS;
		return fresh ? new Vector4f(values[hand], 1.0F) : new Vector4f();
	}

	/** Called by IrisFlashlightUniformsMixin with Iris' UniformHolder; Iris is reached by reflection as it is optional. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void registerUniforms(final Object holder) {
		try {
			ClassLoader loader = holder.getClass().getClassLoader();
			Class frequency = Class.forName("net.irisshaders.iris.gl.uniform.UniformUpdateFrequency", false, loader);
			Method uniform4f = Class.forName("net.irisshaders.iris.gl.uniform.UniformHolder", false, loader)
				.getMethod("uniform4f", frequency, String.class, Supplier.class);
			Object perFrame = Enum.valueOf(frequency, "PER_FRAME");
			for (int hand = 0; hand < 2; hand++) {
				int h = hand;
				uniform4f.invoke(holder, perFrame, "minaFlashlightPos" + h, (Supplier<Vector4f>) () -> uniform(POSITION, h));
				uniform4f.invoke(holder, perFrame, "minaFlashlightDir" + h, (Supplier<Vector4f>) () -> uniform(DIRECTION, h));
			}
		} catch (ReflectiveOperationException e) {
			MinaHorror.LOGGER.warn("Could not give the flashlight beam to Iris", e);
		}
	}

	/** Iris draws entities again for its shadow map, with the sun as the camera; those poses are not the lens. */
	private static boolean inShadowPass() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return false;
		}

		try {
			if (shadowPass == null) {
				shadowPass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState")
					.getMethod("areShadowsCurrentlyBeingRendered");
			}

			return (boolean) shadowPass.invoke(null);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}
}
