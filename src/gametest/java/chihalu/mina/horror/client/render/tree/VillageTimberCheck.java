package chihalu.mina.horror.client.render.tree;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class VillageTimberCheck {
    public static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var level=server.overworld();
            for(int x : new int[]{0,8}) {
                level.setBlockAndUpdate(new BlockPos(x,99,12),Blocks.DIRT.defaultBlockState());
                for(int y=100;y<=103;y++) level.setBlockAndUpdate(new BlockPos(x,y,12),Blocks.OAK_LOG.defaultBlockState());
                for(Direction d : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST})
                    level.setBlockAndUpdate(new BlockPos(x,103,12).relative(d),Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(BlockStateProperties.DISTANCE,1).setValue(BlockStateProperties.PERSISTENT,true));
            }
            level.setBlockAndUpdate(new BlockPos(9,101,12),Blocks.OAK_PLANKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(16,100,12),Blocks.OAK_LOG.defaultBlockState());
        });
        context.waitFor(client -> client.level.getBlockState(new BlockPos(16,100,12)).is(Blocks.OAK_LOG)
            && client.level.getBlockState(new BlockPos(9,101,12)).is(Blocks.OAK_PLANKS));
        context.runOnClient(client -> {
            var trees=new NaturalTrees();
            for(int x : new int[]{0,8,16}) {
                BlockPos pos=new BlockPos(x,100,12);
                boolean recognised=trees.find(client.level,pos,client.level.getBlockState(pos))!=null;
                if(recognised!=(x==0)) throw new AssertionError("Tree/building classification incorrect at x="+x);
            }
            if(NaturalTrees.handles(Blocks.STRIPPED_OAK_LOG.defaultBlockState()))
                throw new AssertionError("Stripped building timber classified as tree");
        });
        System.out.println("Natural tree retained; connected building timber, isolated and stripped logs stay vanilla");
    }
}
