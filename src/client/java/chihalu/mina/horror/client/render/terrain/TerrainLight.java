package chihalu.mina.horror.client.render.terrain;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;

/** Light samples share world-space positions across block and section boundaries. */
final class TerrainLight {
    static int at(BlockAndTintGetter level, double x, double y, double z) {
        int bx=(int)Math.floor(x-.5), by=(int)Math.floor(y-.5), bz=(int)Math.floor(z-.5);
        double fx=x-.5-bx, fy=y-.5-by, fz=z-.5-bz;
        double block=0, sky=0, total=0;
        var cursor=new BlockPos.MutableBlockPos();
        for(int dx=0;dx<2;dx++) for(int dy=0;dy<2;dy++) for(int dz=0;dz<2;dz++) {
            cursor.set(bx+dx,by+dy,bz+dz);
            if(level.getBlockState(cursor).isSolidRender()) continue;
            double weight=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz);
            int light=LightCoordsUtil.getLightCoords(level,cursor);
            block+=(light & 65535)*weight;
            sky+=((light >>> 16)&65535)*weight;
            total+=weight;
        }
        if(total < 1e-6) return LightCoordsUtil.getLightCoords(level,BlockPos.containing(x,y,z));
        return (int)Math.round(block/total) | ((int)Math.round(sky/total)<<16);
    }
}
