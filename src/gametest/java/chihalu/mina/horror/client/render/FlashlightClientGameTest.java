package chihalu.mina.horror.client.render;

import chihalu.mina.horror.item.FlashlightItem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;

/**
 * Holds the flashlight, switches it on, and screenshots it in first person, third person and the inventory.
 * At midnight facing a wall, so with the MinaRealism shader pack the screenshots also show the beam.
 */
public class FlashlightClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		var world = context.worldBuilder().create();
		try {
			world.getServer().runCommand("time set 18000");
			world.getServer().runCommand("item replace entity @a weapon.mainhand with mina-horror:flashlight");
			world.getServer().runCommand("item replace entity @a weapon.offhand with mina-horror:flashlight");
			world.getServer().runCommand("fill -6 99 -6 6 99 8 minecraft:stone");
			// a wall to shine on and a pillar to cast a shadow
			world.getServer().runCommand("fill -6 100 8 6 104 8 minecraft:stone_bricks");
			world.getServer().runCommand("fill 1 100 4 1 102 4 minecraft:oak_log");
			world.getServer().runCommand("tp @a 0 100 0 0 10");
			context.waitTicks(40);
			if (enableShaderPack(context)) {
				context.waitTicks(100); // let the exposure adapt to the dark
			}
			System.out.println("Flashlight screenshot: " + context.takeScreenshot("flashlight_first_person"));

			context.runOnClient(client -> client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
			// frame by frame through switching on, where exposure or beam glitches would show
			for (int tick = 0; tick < 12; tick++) {
				context.waitTick();
				System.out.println("Flashlight screenshot: " + context.takeScreenshot("flashlight_switching_on_" + tick));
			}
			boolean on = context.computeOnClient(client -> FlashlightItem.isOn(client.player.getMainHandItem()));
			if (!on) throw new AssertionError("Flashlight did not switch on");
			System.out.println("Flashlight screenshot: " + context.takeScreenshot("flashlight_first_person_on"));

			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(5);
			System.out.println("Flashlight screenshot: " + context.takeScreenshot("flashlight_third_person"));
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));

			context.setScreen(() -> null);
			context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
			context.waitTicks(5);
			System.out.println("Flashlight screenshot: " + context.takeScreenshot("flashlight_inventory"));
			context.setScreen(() -> null);
		} finally {
			disableShaderPack(context);
			world.getServer().runOnServer(server -> server.halt(false));
			context.waitFor(client -> client.level == null);
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		}
	}

	/** Turns on the bundled MinaRealism pack when Iris is loaded (it is optional, hence reflection). */
	private static boolean enableShaderPack(ClientGameTestContext context) {
		if (!FabricLoader.getInstance().isModLoaded("iris")) return false;
		setShaderPack(context, true);
		return true;
	}

	private static void disableShaderPack(ClientGameTestContext context) {
		if (FabricLoader.getInstance().isModLoaded("iris")) setShaderPack(context, false);
	}

	private static void setShaderPack(ClientGameTestContext context, boolean enabled) {
		context.runOnClient(client -> {
			try {
				Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
				Object config = iris.getMethod("getIrisConfig").invoke(null);
				config.getClass().getMethod("setShaderPackName", String.class).invoke(config, "MinaRealism");
				config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
				config.getClass().getMethod("save").invoke(config); // reload reads the settings back from disk
				iris.getMethod("reload").invoke(null);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Could not switch the shader pack", e);
			}
		});
	}
}
