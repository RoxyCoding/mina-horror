package chihalu.mina.horror.terrain;

/** Read once per generated surface so changing settings cannot split a mesh mid-build. */
public final class TerrainDetail {
    public static volatile int renderCells = 4;
    public static volatile int collisionCells = 16;
    private TerrainDetail() { }
}
