package chihalu.mina.horror.terrain;

import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class GroundCeilingCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SmoothGround.init();
        checkCurvedSurface();
        check(Blocks.AIR.defaultBlockState(), 2, 0);
        check(Blocks.STONE.defaultBlockState(), 1, 0);
        check(Blocks.OAK_PLANKS.defaultBlockState(), 0, 0);
        check(Blocks.STONE.defaultBlockState(), 0, 1);
        System.out.println("Floor beneath ceiling, placed block, and continuous step checks passed");
    }

    private static void checkCurvedSurface() {
        var left = new SmoothGround.Top(new float[] {0,.5f,0,.5f},
            new float[][] {{-.5f,1,0},{0,1,0},{-.5f,1,0},{0,1,0}});
        var right = new SmoothGround.Top(new float[] {.5f,0,.5f,0},
            new float[][] {{0,1,0},{.5f,1,0},{0,1,0},{.5f,1,0}});
        for (int i=0; i<=16; i++) {
            float z=i/16f;
            if (Math.abs(left.offsetAt(1,z)-right.offsetAt(0,z)) > 1e-6) throw new AssertionError("Cracked curve edge");
            float a=(left.offsetAt(1,z)-left.offsetAt(.999f,z))/.001f;
            float b=(right.offsetAt(.001f,z)-right.offsetAt(0,z))/.001f;
            if (Math.abs(a-b) > .003f) throw new AssertionError("Slope jumps across curve edge");
            if (left.offsetAt(z,.5f) < 0 || left.offsetAt(z,.5f) > .50001f) throw new AssertionError("Curve overshoot");
        }
        if (left.offsetAt(.5f,.5f) <= .25f) throw new AssertionError("Surface still linear");
        System.out.println("Curved surface edge continuity checks passed");
    }

    private static void check(BlockState overhead, int overheadY, int expected) {
        BlockGetter level = (BlockGetter) Proxy.newProxyInstance(BlockGetter.class.getClassLoader(), new Class<?>[] {BlockGetter.class}, (proxy, method, args) -> {
            if (!method.getName().equals("getBlockState")) throw new AssertionError(method);
            int y = ((BlockPos) args[0]).getY();
            return y < 0 ? Blocks.STONE.defaultBlockState() : y == overheadY ? overhead : Blocks.AIR.defaultBlockState();
        });
        int actual = SmoothGround.groundTop(level, new BlockPos.MutableBlockPos(), 0, 0, 0, 1);
        if (actual != expected) throw new AssertionError("Expected floor " + expected + " got " + actual);
    }
}
