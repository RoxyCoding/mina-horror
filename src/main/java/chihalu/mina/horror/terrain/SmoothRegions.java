package chihalu.mina.horror.terrain;

import chihalu.mina.horror.MinaHorror;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;

/**
 * The parts of a dimension the player chose to smooth with the terrain wand, as rectangles of block columns at every
 * height. Saved with the dimension and sent to every player in it, so the drawn slopes and the collision agree on
 * both sides.
 */
public final class SmoothRegions {
	/** A rectangle of block columns, bounds inclusive. */
	public record Area(int minX, int minZ, int maxX, int maxZ) {
		static final Codec<Area> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("min_x").forGetter(Area::minX),
			Codec.INT.fieldOf("min_z").forGetter(Area::minZ),
			Codec.INT.fieldOf("max_x").forGetter(Area::maxX),
			Codec.INT.fieldOf("max_z").forGetter(Area::maxZ)
		).apply(instance, Area::new));
		static final StreamCodec<RegistryFriendlyByteBuf, Area> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Area::minX, ByteBufCodecs.VAR_INT, Area::minZ,
			ByteBufCodecs.VAR_INT, Area::maxX, ByteBufCodecs.VAR_INT, Area::maxZ, Area::new);

		public static Area between(BlockPos a, BlockPos b) {
			return new Area(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		public int width() {
			return maxX - minX + 1;
		}

		public int depth() {
			return maxZ - minZ + 1;
		}
	}

	/** All areas of one dimension. Never changed in place: a new list replaces it, so readers on other threads are safe. */
	public record Regions(List<Area> areas) {
		static final Regions EMPTY = new Regions(List.of());
		static final Codec<Regions> CODEC = Area.CODEC.listOf().xmap(Regions::new, Regions::areas);
		static final StreamCodec<RegistryFriendlyByteBuf, Regions> STREAM_CODEC =
			Area.STREAM_CODEC.apply(ByteBufCodecs.list()).map(Regions::new, Regions::areas);

		public Regions {
			areas = List.copyOf(areas);
		}

		public boolean isEmpty() {
			return areas.isEmpty();
		}

		public boolean contains(int x, int z) {
			for (Area area : areas) if (area.contains(x, z)) return true;
			return false;
		}
	}

	/** Largest side of one area, to keep a stray click from covering a whole map. */
	public static final int MAX_SIZE = 512;

	private static final AttachmentType<Regions> TYPE = AttachmentRegistry.create(MinaHorror.id("smooth_regions"),
		builder -> builder.persistent(Regions.CODEC).syncWith(Regions.STREAM_CODEC, AttachmentSyncPredicate.all()));

	private SmoothRegions() { }

	/** Loads the class, registering the attachment. */
	public static void init() { }

	public static Regions of(Level level) {
		Regions regions = level.getAttached(TYPE);
		return regions != null ? regions : Regions.EMPTY;
	}

	public static void add(Level level, Area area) {
		List<Area> areas = new ArrayList<>(of(level).areas());
		areas.add(area);
		level.setAttached(TYPE, new Regions(areas));
	}

	/** Removes every area covering the column; returns how many there were. */
	public static int removeAt(Level level, int x, int z) {
		List<Area> kept = new ArrayList<>();
		List<Area> areas = of(level).areas();
		for (Area area : areas) if (!area.contains(x, z)) kept.add(area);
		int removed = areas.size() - kept.size();
		if (removed > 0) level.setAttached(TYPE, new Regions(kept));
		return removed;
	}
}
