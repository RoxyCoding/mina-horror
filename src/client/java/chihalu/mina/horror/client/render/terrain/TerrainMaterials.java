package chihalu.mina.horror.client.render.terrain;

/** Eight material weights in RGB (3 bits each), decoded before raster interpolation. */
final class TerrainMaterials {
    static int pack(float[] weights) {
        float sum = 0;
        for (float weight : weights) sum += weight;
        if (sum <= 0) throw new IllegalArgumentException("Empty material palette");
        int packed = 0xFF000000;
        for (int i=0; i<8; i++) packed |= Math.clamp(Math.round(weights[i]/sum*7), 0, 7) << (i*3);
        return packed;
    }

    static float weight(int packed, int material) {
        int sum=0;
        for (int i=0; i<8; i++) sum += (packed >>> (i*3)) & 7;
        return sum == 0 ? 0 : ((packed >>> (material*3)) & 7)/(float)sum;
    }

    static int blend(int a, int b, int c, int d, float u, float v) {
        float[] weights = new float[8];
        for (int i=0; i<8; i++) weights[i] = TerrainSurface.blend(weight(a,i),weight(b,i),weight(c,i),weight(d,i),u,v);
        return pack(weights);
    }
}
