package chihalu.mina.horror.terrain;

import chihalu.mina.horror.MinaHorror;
import com.mojang.serialization.Codec;
import java.util.Arrays;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * The block columns the player marked with the terrain wand, at every height. Kept with each chunk, so they are saved
 * with it and sent to every player who sees it, and the drawn slopes and the collision agree on both sides.
 */
public final class SmoothColumns {
	/** One bit per column of a chunk, (z * 16 + x). Never changed in place: a new one replaces it, so readers on other threads are safe. */
	public record Marks(long[] bits) {
		static final Marks EMPTY = new Marks(new long[4]);
		static final Codec<Marks> CODEC = Codec.LONG_STREAM.xmap(stream -> new Marks(stream.toArray()), marks -> Arrays.stream(marks.bits));
		static final StreamCodec<RegistryFriendlyByteBuf, Marks> STREAM_CODEC = ByteBufCodecs.LONG_ARRAY.map(Marks::new, Marks::bits).cast();

		public Marks {
			bits = Arrays.copyOf(bits, 4);
		}

		public boolean has(int localX, int localZ) {
			int bit = localZ * 16 + localX;
			return (bits[bit >> 6] >>> (bit & 63) & 1L) != 0;
		}

		Marks with(int localX, int localZ, boolean marked) {
			int bit = localZ * 16 + localX;
			long[] copy = bits.clone();
			if (marked) copy[bit >> 6] |= 1L << (bit & 63);
			else copy[bit >> 6] &= ~(1L << (bit & 63));
			return new Marks(copy);
		}

		boolean isEmpty() {
			return (bits[0] | bits[1] | bits[2] | bits[3]) == 0;
		}
	}

	private static final AttachmentType<Marks> TYPE = AttachmentRegistry.create(MinaHorror.id("smooth_columns"),
		builder -> builder.persistent(Marks.CODEC).syncWith(Marks.STREAM_CODEC, AttachmentSyncPredicate.all()));

	private SmoothColumns() { }

	/** Loads the class, registering the attachment. */
	public static void init() { }

	public static AttachmentType<Marks> type() {
		return TYPE;
	}

	/** Marks or clears the column at (x, z); false where its chunk is not loaded or nothing changed. */
	public static boolean set(Level level, int x, int z, boolean marked) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
		if (chunk == null) return false;
		Marks marks = chunk.getAttachedOrElse(TYPE, Marks.EMPTY);
		if (marks.has(x & 15, z & 15) == marked) return false;
		Marks changed = marks.with(x & 15, z & 15, marked);
		if (changed.isEmpty()) chunk.removeAttached(TYPE);
		else chunk.setAttached(TYPE, changed);
		return true;
	}

	/**
	 * Reads the marks of one level, remembering the last chunk asked for since neighbouring columns mostly share it.
	 * Chunks that are not loaded, or not reachable from this thread (a server's chunks away from its main thread), have
	 * no marks.
	 */
	public static final class Lookup {
		private final Level level;
		private long chunkKey = Long.MIN_VALUE;
		private @Nullable Marks marks;

		public Lookup(Level level) {
			this.level = level;
		}

		public boolean has(int x, int z) {
			long key = (long) (x >> 4) << 32 | (z >> 4) & 0xFFFFFFFFL;
			if (key != chunkKey) {
				chunkKey = key;
				LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
				marks = chunk != null ? chunk.getAttached(TYPE) : null;
			}
			return marks != null && marks.has(x & 15, z & 15);
		}

		/** Whether any column in the 3x3 around (x, z) is marked: only there can the ground move. */
		public boolean anyAround(int x, int z) {
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (has(x + dx, z + dz)) return true;
			return false;
		}
	}
}
