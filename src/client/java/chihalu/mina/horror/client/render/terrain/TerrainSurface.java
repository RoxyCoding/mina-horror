package chihalu.mina.horror.client.render.terrain;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import chihalu.mina.horror.terrain.SmoothGround;
import chihalu.mina.horror.terrain.TerrainDetail;

/** Tessellate twisted ground tops along their bilinear height field instead of one long diagonal. */
final class TerrainSurface {
    static float blend(float a, float b, float c, float d, float u, float v) {
        return (a * (1-v) + b * v) * (1-u) + (d * (1-v) + c * v) * u;
    }

    static void emit(QuadView source, QuadEmitter out) {
        emit(source, out, null);
    }

    static void emit(QuadView source, QuadEmitter out, SmoothGround.Top top) {
        boolean upward = source.y(0) > .999f && source.y(1) > .999f && source.y(2) > .999f && source.y(3) > .999f;
        int cells = TerrainDetail.renderCells;
        int cellsU = top == null || upward || source.x(0) != source.x(3) || source.z(0) != source.z(3) ? cells : 1;
        int cellsV = top == null || upward || source.x(0) != source.x(1) || source.z(0) != source.z(1) ? cells : 1;
        for (int x = 0; x < cellsU; x++) for (int z = 0; z < cellsV; z++) {
            out.copyFrom(source);
            out.cullFace(null);
            for (int i = 0; i < 4; i++) {
                float u = (x + (i >= 2 ? 1 : 0)) / (float)cellsU;
                float v = (z + (i == 1 || i == 2 ? 1 : 0)) / (float)cellsV;
                float px = blend(source.x(0),source.x(1),source.x(2),source.x(3),u,v);
                float py = blend(source.y(0),source.y(1),source.y(2),source.y(3),u,v);
                float pz = blend(source.z(0),source.z(1),source.z(2),source.z(3),u,v);
                out.pos(i, px, top == null ? py : py + py * top.offsetAt(px,pz), pz);
                out.uv(i, blend(source.u(0),source.u(1),source.u(2),source.u(3),u,v),
                    blend(source.v(0),source.v(1),source.v(2),source.v(3),u,v));
                int color = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int channel = Math.round(blend((source.color(0) >>> shift)&255, (source.color(1) >>> shift)&255,
                        (source.color(2) >>> shift)&255, (source.color(3) >>> shift)&255, u,v));
                    color |= channel << shift;
                }
                out.color(i, source.tag() == 0x4D4154
                    ? TerrainMaterials.blend(source.color(0),source.color(1),source.color(2),source.color(3),u,v) : color);
                if (top != null && upward) {
                    float[] normal = top.normalAt(px,pz);
                    out.normal(i,normal[0],normal[1],normal[2]);
                } else if (source.hasNormal(0) && source.hasNormal(1) && source.hasNormal(2) && source.hasNormal(3)) {
                    float nx = blend(source.normalX(0),source.normalX(1),source.normalX(2),source.normalX(3),u,v);
                    float ny = blend(source.normalY(0),source.normalY(1),source.normalY(2),source.normalY(3),u,v);
                    float nz = blend(source.normalZ(0),source.normalZ(1),source.normalZ(2),source.normalZ(3),u,v);
                    float length = (float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                    out.normal(i,nx/length,ny/length,nz/length);
                }
            }
            out.emit();
        }
    }
}
