package chihalu.mina.horror.client.render;

import chihalu.mina.horror.item.FlashlightItem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;

/** Holds the flashlight, switches it on, and screenshots it in first person, third person and the inventory. */
public class FlashlightClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		var world = context.worldBuilder().create();
		try {
			world.getServer().runCommand("time set 13000");
			world.getServer().runCommand("item replace entity @a weapon.mainhand with mina-horror:flashlight");
			world.getServer().runCommand("item replace entity @a weapon.offhand with mina-horror:flashlight");
			world.getServer().runCommand("fill -6 99 -6 6 99 6 minecraft:stone");
			world.getServer().runCommand("tp @a 0 100 0 0 10");
			context.waitTicks(40);
			System.out.println("Flashlight screenshot: " + context.takeScreenshot("flashlight_first_person"));

			context.runOnClient(client -> client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
			context.waitTicks(10);
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
			world.getServer().runOnServer(server -> server.halt(false));
			context.waitFor(client -> client.level == null);
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		}
	}
}
