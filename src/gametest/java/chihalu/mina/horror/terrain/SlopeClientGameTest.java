package chihalu.mina.horror.terrain;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public class SlopeClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var world=context.worldBuilder().create();
        try {
            chihalu.mina.horror.client.render.tree.VillageTimberCheck.run(context,world);
            context.runOnClient(client -> client.gui.setScreen(new net.minecraft.client.gui.screens.options.OptionsScreen(null,client.options)));
            context.clickScreenButton("mina.terrain.title");
            context.runOnClient(client -> {
                if (!(client.gui.screen() instanceof chihalu.mina.horror.client.settings.TerrainSettingsScreen))
                    throw new AssertionError("Terrain settings screen did not open");
                var cycles=net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(client.gui.screen()).stream()
                    .filter(w -> w instanceof net.minecraft.client.gui.components.CycleButton<?>).toList();
                for(int i=0;i<cycles.size();i++) {
                    var cycle=(net.minecraft.client.gui.components.CycleButton<?>)cycles.get(i);
                    int wanted=i==0?2:4;
                    for(int attempt=0;attempt<3 && !cycle.getValue().equals(wanted);attempt++) cycle.mouseScrolled(0,0,0,-1);
                }
            });
            context.clickScreenButton("mina.terrain.apply");
            context.runOnClient(client -> client.gui.setScreen(null));
            context.waitTicks(2);
            if(TerrainDetail.renderCells!=2 || TerrainDetail.collisionCells!=4) throw new AssertionError("Subdivision choices not applied");
            try(var reader=java.nio.file.Files.newBufferedReader(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mina-horror-terrain.properties"))) {
                var saved=new java.util.Properties(); saved.load(reader);
                if(!"2".equals(saved.getProperty("renderCells")) || !"4".equals(saved.getProperty("collisionCells")))
                    throw new AssertionError("Subdivision choices not saved");
            } catch(java.io.IOException e) { throw new AssertionError(e); }
            world.getServer().runOnServer(server -> {
                for(int x=-8;x<24;x++) for(int z=-4;z<5;z++) for(int y=98;y<105;y++)
                    server.overworld().setBlockAndUpdate(new BlockPos(x,y,z),
                        (y<(x<4?100:101)?Blocks.STONE:Blocks.AIR).defaultBlockState());
            });
            world.getServer().runCommand("gamemode survival @a");
            for(int direction : new int[]{1,-1}) {
                world.getServer().runCommand("tp @a "+(direction==1?0:8)+" "+(direction==1?100:101)+" 0 "+(direction==1?-90:90)+" 0");
                context.waitTicks(30);
                context.runOnClient(client -> client.options.autoJump().set(true));
                context.getInput().holdKey(options -> options.keyUp);
                double previous=context.computeOnClient(client -> client.player.getX());
                try {
                    for(int tick=0;tick<38;tick++) {
                        context.waitTick();
                        double[] sample=context.computeOnClient(client -> {
                            var p=client.player;
                            return new double[]{p.getX(),p.getY(),p.onGround()?1:0,SlopeWalking.height(client.level,p.getBoundingBox(),p.getY())};
                        });
                        double dx=direction*(sample[0]-previous);
                        if(tick>8 && (dx<.10 || dx>.35 || sample[2]==0 || Math.abs(sample[1]-sample[3])>.03))
                            throw new AssertionError("Walking jitter at tick "+tick+", dx="+dx+", height error="+(sample[1]-sample[3]));
                        previous=sample[0];
                    }
                } finally {
                    context.getInput().releaseKey(options -> options.keyUp);
                }
                System.out.println("Slope client walking passed: direction="+direction+", 38 ticks, no pushback or lost contact");
            }
        } finally {
            try { chihalu.mina.horror.client.settings.TerrainSettings.save(4,16); }
            catch(java.io.IOException e) { throw new AssertionError(e); }
            // Stop on the server thread: context.close() in this API version deadlocks at the tick barrier.
            world.getServer().runOnServer(server -> server.halt(false));
            context.waitFor(client -> client.level == null);
            context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        }
    }
}
