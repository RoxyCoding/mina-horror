package chihalu.mina.horror.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;

/** Continuous foot support; ordinary collision still handles walls, ceilings and entities. */
public final class SlopeWalking {
    private static final ThreadLocal<Double> SUPPORT = new ThreadLocal<>();

    private SlopeWalking() { }

    public static boolean replaces(BlockPos pos) {
        Double feet = SUPPORT.get();
        return feet != null && pos.getY() >= feet - SmoothGround.SPAN - 2 && pos.getY() <= feet + 1;
    }

    public static Vec3 move(Entity entity, Vec3 requested) {
        if (!(entity instanceof Player player)
                || entity.noPhysics || player.getAbilities().flying || entity.isPassenger()
                || !entity.onGround() && requested.y < -.2
                || entity.isInWater() || entity.isInLava() || requested.horizontalDistanceSqr() > .64)
            return null;
        AABB box = entity.getBoundingBox();
        double current = height(entity.level(), box, box.minY);
        if (!Double.isFinite(current) || Math.abs(current - box.minY) > .15) return null;
        double target = height(entity.level(), box.move(requested.x, 0, requested.z), current);
        if (!Double.isFinite(target) || Math.abs(target - box.minY) > entity.maxUpStep()) return null;
        // Server movement packets contain the actual uphill rise, not the client's gravity velocity.
        if (!walkingVertical(requested.y, target-box.minY, entity.level().isClientSide())) return null;
        Vec3 along = new Vec3(requested.x, target - box.minY, requested.z);
        SUPPORT.set(box.minY);
        try {
            var colliders = entity.level().getEntityCollisions(entity, box.expandTowards(along));
            Vec3 clipped = Entity.collideBoundingBox(entity, along, box, entity.level(), colliders);
            // Leave blocked paths to normal collision/step handling; never bypass a wall or ceiling.
            return clipped.subtract(along).lengthSqr() < 1e-12 ? along : null;
        } finally {
            SUPPORT.remove();
        }
    }

    static boolean walkingVertical(double requested, double rise, boolean client) {
        return requested <= 0 || !client && Math.abs(requested-rise) < .01;
    }

    /** Keep movement validation and ordinary grounding consistent with continuous foot support. */
    public static VoxelShape contact(BlockGetter level, BlockPos pos, CollisionContext context, VoxelShape shape) {
        if (shape.isEmpty() || !(context instanceof EntityCollisionContext ec)
                || !(ec.getEntity() instanceof Player player)) return shape;
        // withPosition() also sets isPlacement=true during server movement validation.
        // Filtering that flag makes the server see stair collisions the client has already smoothed.
        double feet=player.getBoundingBox().minY;
        double floor=height(level,player.getBoundingBox(),feet);
        if (!Double.isFinite(floor) || Math.abs(feet-floor)>.005) return shape;
        double cap=Math.clamp(feet-pos.getY(),0,1);
        if(cap>=1) return shape;
        if(cap<=0) return Shapes.empty();
        return Shapes.joinUnoptimized(shape,Shapes.box(0,0,0,1,cap,1),BooleanOp.AND);
    }

    /** Highest continuous support under the footprint, sampled independently of voxel collision cells. */
    static double height(BlockGetter level, AABB box, double feet) {
        double highest = Double.NEGATIVE_INFINITY;
        for (int ix = 0; ix < 3; ix++) for (int iz = 0; iz < 3; iz++) {
            double x = box.minX + 1e-5 + (box.getXsize()-2e-5)*ix/2;
            double z = box.minZ + 1e-5 + (box.getZsize()-2e-5)*iz/2;
            int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
            double nearest = Double.NaN, distance = Double.POSITIVE_INFINITY;
            for (int y = (int)Math.floor(feet)-SmoothGround.SPAN-1; y <= (int)Math.floor(feet)+1; y++) {
                BlockPos pos = new BlockPos(bx,y,bz);
                if (!SmoothGround.isGround(level.getBlockState(pos))
                        || !SmoothGround.isSurfaceAbove(level.getBlockState(pos.above()))) continue;
                var top = SmoothGround.top(level, null, bx, y+1, bz);
                double h = y+1 + (top == null ? 0 : top.offsetAt((float)(x-bx),(float)(z-bz)));
                if (Math.abs(h-feet) < distance) {
                    nearest=h; distance=Math.abs(h-feet);
                }
            }
            if (!Double.isFinite(nearest)) return Double.NaN;
            highest=Math.max(highest,nearest);
        }
        return highest;
    }
}
