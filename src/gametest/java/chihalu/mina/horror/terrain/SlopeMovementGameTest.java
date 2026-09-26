package chihalu.mina.horror.terrain;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class SlopeMovementGameTest {
    @GameTest(skyAccess = true, maxTicks = 100)
    public void walkBothWays(GameTestHelper helper) {
        for(int x=0;x<8;x++) for(int z=0;z<8;z++) for(int y=0;y<4;y++)
            helper.setBlock(x,y,z,y<(x<4?1:2)?Blocks.STONE:Blocks.AIR);
        var player=helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos origin=helper.absolutePos(BlockPos.ZERO);
        for(int direction : new int[]{1,-1}) {
            player.setPos(origin.getX()+(direction==1?2:6),origin.getY()+(direction==1?1:2),origin.getZ()+3.5);
            player.setOnGround(true);
            for(int step=0;step<80;step++) {
                double x=player.getX(), y=player.getY();
                player.setDeltaMovement(direction*.05,-.0784,0);
                player.move(MoverType.SELF,player.getDeltaMovement());
                double actual=player.getX()-x;
                if(Math.abs(actual-direction*.05)>1e-6)
                    throw new AssertionError("Horizontal slowdown: dir="+direction+" step="+step+" x="+(x-origin.getX())+" y="+(y-origin.getY())+" dx="+actual);
                double floor=SlopeWalking.height(helper.getLevel(),player.getBoundingBox(),player.getY());
                if(Math.abs(player.getY()-floor)>.005 || !player.onGround())
                    throw new AssertionError("Lost support: dir="+direction+" step="+step+" y="+player.getY()+" floor="+floor+" ground="+player.onGround());
                if(!helper.getLevel().noCollision(player,player.getBoundingBox().deflate(1e-5)))
                    throw new AssertionError("Movement validation intersects terrain at step "+step);
            }
        }
        // Ordinary buildings must still stop movement, and manual jumps must leave the slope.
        for(int y=1;y<4;y++) helper.setBlock(2,y,3,Blocks.OAK_PLANKS);
        player.setPos(origin.getX()+1.6,origin.getY()+1,origin.getZ()+3.5);
        player.setOnGround(true);
        player.move(MoverType.SELF,new Vec3(.2,-.0784,0));
        if(player.getX()>origin.getX()+1.701) throw new AssertionError("Passed through building wall");
        player.setPos(origin.getX()+4,origin.getY()+1.7,origin.getZ()+3.5);
        player.setOnGround(true);
        double startY=player.getY();
        player.move(MoverType.SELF,new Vec3(0,.42,0));
        if(player.getY()-startY<.4 || player.onGround()) throw new AssertionError("Manual jump suppressed");
        helper.succeed();
    }
}
