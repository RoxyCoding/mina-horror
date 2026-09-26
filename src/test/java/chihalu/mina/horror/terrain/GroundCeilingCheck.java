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
        checkWalkingHeight();
        checkCollisionResolution();
        checkCollisionBatch();
        checkAutomaticHeightRange();
        check(Blocks.AIR.defaultBlockState(), 2, 0);
        check(Blocks.STONE.defaultBlockState(), 1, 0);
        check(Blocks.OAK_PLANKS.defaultBlockState(), 0, 0);
        check(Blocks.STONE.defaultBlockState(), 0, 1);
        System.out.println("Floor beneath ceiling, placed block, and continuous step checks passed");
    }

    private static void checkWalkingHeight() {
        if (!SlopeWalking.walkingVertical(.08,.08,false)) throw new AssertionError("Server uphill packet rejected");
        if (SlopeWalking.walkingVertical(.42,.08,false) || SlopeWalking.walkingVertical(.42,.42,true))
            throw new AssertionError("Jump treated as grounded walking");
        if (!SlopeWalking.walkingVertical(-.08,-.04,true)) throw new AssertionError("Downhill gravity rejected");
        BlockGetter level = (BlockGetter) Proxy.newProxyInstance(BlockGetter.class.getClassLoader(), new Class<?>[] {BlockGetter.class}, (proxy, method, args) -> {
            if (!method.getName().equals("getBlockState")) throw new AssertionError(method);
            BlockPos pos=(BlockPos)args[0];
            int height=pos.getX()<0 ? 0 : 1;
            return pos.getY()<height ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
        double previous=Double.NaN;
        int changes=0;
        for (int i=0; i<=120; i++) {
            double x=-.9+i*.01;
            double h=SlopeWalking.height(level,new net.minecraft.world.phys.AABB(x-.3,0,-.3,x+.3,1.8,.3),.5);
            if (!Double.isFinite(h)) throw new AssertionError("Slope support lost");
            if (i>0) {
                if (Math.abs(h-previous)>.02) throw new AssertionError("Walking height jumps");
                if (h>previous+1e-6) changes++;
            }
            previous=h;
        }
        if(changes<100) throw new AssertionError("Walking still follows discrete steps");
        for (int direction : new int[]{1,-1}) {
            double feet=direction==1 ? 0 : 1;
            for(int i=0;i<=400;i++) {
                double x=direction*(-2+i*.01);
                double h=SlopeWalking.height(level,new net.minecraft.world.phys.AABB(x-.3,feet,-.3,x+.3,feet+1.8,.3),feet);
                if(!Double.isFinite(h) || Math.abs(h-feet)>.02) throw new AssertionError("Flat/slope transition loses contact");
                if(!SlopeWalking.walkingVertical(h-feet,h-feet,false)) throw new AssertionError("Server disagrees with slope motion");
                feet=h;
            }
        }
        System.out.println("Continuous walking support across block boundaries passed");
    }

    private static void checkCollisionBatch() {
        long start = System.nanoTime();
        for (int i = 0; i < 256; i++) {
            float a = -.8f + (i % 16) * .03f, b = -.7f + (i / 16) * .025f;
            var top = new SmoothGround.Top(new float[] {a,b,0,-.1f}, new float[4][]);
            var shape = SmoothGround.layerShape(top, 0);
            if (shape == null || shape.isEmpty()) throw new AssertionError("Missing batch collision");
            for (int x=0; x<16; x++) for (int z=0; z<16; z++) {
                double expected = Math.clamp(Math.round((1+top.offsetAt((x+.5f)/16,(z+.5f)/16))*256),1,256)/256.0;
                double actual = shape.collide(net.minecraft.core.Direction.Axis.Y,
                    new net.minecraft.world.phys.AABB((x+.4)/16,1,(z+.4)/16,(x+.6)/16,2,(z+.6)/16),-1);
                if (Math.abs(actual-(expected-1)) > 1e-7) throw new AssertionError("Grid collision differs from surface");
            }
        }
        long millis = (System.nanoTime()-start)/1_000_000;
        System.out.println("256 distinct terrain collision meshes and 65536 contact checks: " + millis + " ms");
        if (millis > 5000) throw new AssertionError("Collision generation stalled");
    }

    private static void checkCollisionResolution() {
        var top = new SmoothGround.Top(new float[] {-.5f,0,-.5f,0},
            new float[][] {{-.5f,1,0},{-.5f,1,0},{-.5f,1,0},{-.5f,1,0}});
        var shape = SmoothGround.layerShape(top, 0);
        if (shape == null) throw new AssertionError("Slope collision missing");
        double previous = 0;
        for (int i=0; i<16; i++) {
            double x=(i+.5)/16.0;
            double[] height={0};
            shape.forAllBoxes((x0,y0,z0,x1,y1,z1) -> {
                if (x>=x0 && x<x1 && .51>=z0 && .51<z1) height[0]=Math.max(height[0],y1);
            });
            if (Math.abs(height[0]-(.5+.5*x)) > 1.0/256) throw new AssertionError("Collision does not follow slope");
            if (i>0 && (height[0]<=previous || height[0]-previous>1.0/16)) throw new AssertionError("Coarse collision step");
            previous=height[0];
        }
        var raised = new SmoothGround.Top(new float[] {-.49f,.01f,-.49f,.01f},top.normals());
        if (SmoothGround.layerShape(raised,0)==shape) throw new AssertionError("Distinct collision meshes share cache key");
        System.out.println("Fine slope collision and cache separation checks passed");
    }

    private static void checkAutomaticHeightRange() {
        for (int height = 1; height <= 4; height++) {
            final int top = height;
            BlockGetter level = (BlockGetter) Proxy.newProxyInstance(BlockGetter.class.getClassLoader(), new Class<?>[] {BlockGetter.class}, (proxy, method, args) -> {
                if (!method.getName().equals("getBlockState")) throw new AssertionError(method);
                return ((BlockPos) args[0]).getY() < top ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            });
            int material = SmoothGround.surfaceMaterialAt(level, 0, 0, 0);
            if (material != (height <= 3 ? 2 : -1)) throw new AssertionError("Automatic height range: " + height);
        }
        if (SmoothGround.isGroundBlock(Blocks.OAK_PLANKS) || SmoothGround.isGroundBlock(Blocks.STONE_BRICKS))
            throw new AssertionError("Building materials must not smooth");
        System.out.println("Automatic three-block range and building-material exclusion checks passed");
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
