package chihalu.mina.horror.client.render.terrain;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Optional Iris bridge for Sodium's fallback fluid vertices. */
final class ShoreWaterShaders {
    private static final Class<?> BUFFER;
    private static final Object SETTINGS;
    private static final Field VERTICES;
    private static final Method IDS, DATA;

    static {
        Class<?> buffer = null;
        Object settings = null;
        Field vertices = null;
        Method ids = null, data = null;
        try {
            Class<?> extension = Class.forName("net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension");
            buffer = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkVertexConsumer");
            vertices = buffer.getDeclaredField("vertices");
            vertices.setAccessible(true);
            Class<?> type = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            settings = type.getField("INSTANCE").get(null);
            ids = type.getMethod("getBlockStateIds");
            data = extension.getMethod("iris$setData", byte.class, byte.class, int.class, int.class, int.class, int.class);
        } catch (ClassNotFoundException absent) {
            buffer = null;
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Unsupported Iris shore water vertex API", incompatible);
        }
        BUFFER = buffer;
        SETTINGS = settings;
        VERTICES = vertices;
        IDS = ids;
        DATA = data;
    }

    static boolean begin(Object builder, BlockState water, BlockPos pos) {
        if (BUFFER == null || !BUFFER.isInstance(builder)) return false;
        try {
            var ids = (Object2IntMap<?>) IDS.invoke(SETTINGS);
            if (ids == null) return false;
            // Iris 1.11.6's consumer.beginBlock delegates to a builder that no longer implements its interface.
            // Set the same metadata on the actual four vertices, as Iris's default fluid renderer does.
            write((Object[]) VERTICES.get(builder), DATA, (byte) water.getLightEmission(), (byte) 1,
                ids.getInt(water), pos.getX(), pos.getY(), pos.getZ());
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not set Iris water vertex data", failure);
        }
    }

    static boolean active() {
        if (BUFFER == null) return false;
        try {
            return IDS.invoke(SETTINGS) != null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not read Iris material mapping", failure);
        }
    }

    static void end(Object builder) {
        try {
            write((Object[]) VERTICES.get(builder), DATA, (byte) 0, (byte) 0, -1, 0, 0, 0);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not clear Iris water vertex data", failure);
        }
    }

    static void write(Object[] vertices, Method data, byte emission, byte fluid, int id, int x, int y, int z)
        throws ReflectiveOperationException {
        for (Object vertex : vertices) data.invoke(vertex, emission, fluid, id, x, y, z);
    }
}
