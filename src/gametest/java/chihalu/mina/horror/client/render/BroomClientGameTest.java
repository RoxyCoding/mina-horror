package chihalu.mina.horror.client.render;

import chihalu.mina.horror.entity.Broom;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.phys.AABB;

/**
 * Screenshots the broom from its side, empty and with a mannequin aboard, then rides it: flies up and forward,
 * checks it moved and that sneaking dismounts, and screenshots the broom as an item in hand and in the inventory.
 */
public class BroomClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		var world = context.worldBuilder().create();
		try {
			world.getServer().runCommand("time set 6000");
			world.getServer().runCommand("fill -12 99 -12 12 99 24 minecraft:grass_block");
			world.getServer().runCommand("tp @a 0.5 100 0.5 0 12");
			// facing west, so its right side (the lantern) faces the camera with the front on the right, as in the art
			world.getServer().runCommand("summon mina-horror:broom 0.5 100 3.6 {Rotation:[90f,0f]}");
			context.waitTicks(40);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_side"));

			world.getServer().runCommand("summon minecraft:mannequin 0.5 100 3.6");
			world.getServer().runCommand("ride @e[type=minecraft:mannequin,limit=1] mount @e[type=mina-horror:broom,limit=1]");
			context.waitTicks(20);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_mannequin"));
			world.getServer().runCommand("kill @e[type=minecraft:mannequin]");
			context.waitTicks(40); // let its puff of smoke clear

			world.getServer().runCommand("summon mina-horror:broom 0.5 100 8 {Rotation:[0f,0f]}");
			world.getServer().runCommand("tp @a 0.5 100 8 0 0");
			world.getServer().runCommand("ride @p mount @e[type=mina-horror:broom,sort=nearest,limit=1]");
			context.waitTicks(20);
			boolean riding = context.computeOnClient(client -> client.player.getVehicle() instanceof Broom);
			if (!riding) throw new AssertionError("The player did not mount the broom");
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(10);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_riding_back"));
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(5);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_riding_front"));
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));

			double startY = context.computeOnClient(client -> client.player.getVehicle().getY());
			double startZ = context.computeOnClient(client -> client.player.getVehicle().getZ());
			context.getInput().holdKey(options -> options.keyUp);
			context.getInput().holdKey(options -> options.keyJump);
			context.waitTicks(30);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_flying"));
			context.getInput().releaseKey(options -> options.keyJump);
			context.getInput().releaseKey(options -> options.keyUp);
			context.waitTicks(20);
			double endY = context.computeOnClient(client -> client.player.getVehicle().getY());
			double endZ = context.computeOnClient(client -> client.player.getVehicle().getZ());
			if (endY - startY < 3.0 || endZ - startZ < 5.0) {
				throw new AssertionError("The broom did not fly: moved " + (endY - startY) + " up and " + (endZ - startZ) + " forward");
			}

			double serverY = world.getServer().computeOnServer(server -> server.overworld()
				.getEntitiesOfClass(Broom.class, new AABB(-50, 90, -50, 50, 150, 80)).stream()
				.filter(Broom::isVehicle).findFirst().orElseThrow().getY());
			if (Math.abs(serverY - endY) > 0.5) throw new AssertionError("The server did not follow the broom: " + serverY + " vs " + endY);

			context.getInput().holdKey(options -> options.keyShift);
			context.waitTicks(5);
			context.getInput().releaseKey(options -> options.keyShift);
			context.waitTicks(5);
			boolean dismounted = context.computeOnClient(client -> client.player.getVehicle() == null);
			if (!dismounted) throw new AssertionError("Sneaking did not dismount");
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));

			world.getServer().runCommand("kill @e[type=mina-horror:broom]");
			world.getServer().runCommand("tp @a 0.5 100 0.5 0 0");
			world.getServer().runCommand("item replace entity @a weapon.mainhand with mina-horror:broom");
			context.waitTicks(20);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_first_person"));
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(5);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_third_person"));
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player)));
			context.waitTicks(5);
			System.out.println("Broom screenshot: " + context.takeScreenshot("broom_inventory"));
			context.setScreen(() -> null);
		} finally {
			world.getServer().runOnServer(server -> server.halt(false));
			context.waitFor(client -> client.level == null);
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		}
	}
}
