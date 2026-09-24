package chihalu.mina.horror.client.render.tree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Client-only replacement; vanilla saves, loot, growth and collision remain authoritative. Nether fungi stay vanilla. */
public final class NaturalTreeModels {
	private static final String[] SPECIES = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "crimson", "warped", "poplar"};
	private static final Map<Block, Profile> PROFILES = new HashMap<>();
	private static final Material ATLAS = new Material(Identifier.fromNamespaceAndPath("mina-horror", "block/tree_atlas"));
	private static final String[] POPLAR_SPRITES = {"poplar_bark", "poplar_yellow", "poplar_orange", "poplar_red"};
	private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private record Profile(int bark, int leaf, int kind, boolean stripped, boolean allBark) { }
	private record ModelKey(Block block, int axis) { }
	private record Shape(int connections, int corner, int variant, boolean rooted, int edges, int thick) { }
	private NaturalTreeModels() { }

	public static void register() {
		// Enumerate exact vanilla names, including stripped logs, wood, stems and hyphae.
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			if (!id.getNamespace().equals("minecraft")) continue;
			String name = id.getPath();
			for (int i = 0; i < SPECIES.length; i++) {
				String species = SPECIES[i];
				if (species.equals("crimson") || species.equals("warped")) continue;
				int bark = species.equals("poplar") ? 24 : i;
				if (name.equals(species + "_leaves")) PROFILES.put(block, new Profile(i, i + 12, 1, false, false));
				for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
					if (name.equals(species + suffix) || name.equals("stripped_" + species + suffix)) {
						PROFILES.put(block, new Profile(bark, -1, 0, name.startsWith("stripped_"), suffix.equals("_wood") || suffix.equals("_hyphae")));
					}
				}
			}
			switch (name) {
				case "yellow_poplar_leaves" -> PROFILES.put(block, new Profile(24, 25, 1, false, false));
				case "orange_poplar_leaves" -> PROFILES.put(block, new Profile(24, 26, 1, false, false));
				case "red_poplar_leaves" -> PROFILES.put(block, new Profile(24, 27, 1, false, false));
				case "azalea_leaves" -> PROFILES.put(block, new Profile(0, 21, 1, false, false));
				case "flowering_azalea_leaves" -> PROFILES.put(block, new Profile(0, 22, 1, false, false));
				case "mangrove_roots" -> PROFILES.put(block, new Profile(6, -1, 2, false, false));
				default -> { }
			}
		}
		ModelLoadingPlugin.register(plugin -> {
			// The cache belongs to this resource reload, so old atlas UVs are never reused.
			Map<ModelKey, TreeModel> models = new ConcurrentHashMap<>();
			NaturalTrees trees = new NaturalTrees();
			plugin.modifyBlockModelAfterBake().register((original, context) -> {
				BlockState state = context.state();
				Profile profile = PROFILES.get(state.getBlock());
				if (profile == null) return original;
				int axis = state.hasProperty(BlockStateProperties.AXIS) ? switch (state.getValue(BlockStateProperties.AXIS)) {
					case X -> 0; case Y -> 1; case Z -> 2;
				} : 1;
				return models.computeIfAbsent(new ModelKey(state.getBlock(), axis), key -> {
					Material.Baked[] poplar = new Material.Baked[4];
					for (int i = 0; i < poplar.length; i++) poplar[i] = context.baker().materials().get(
						new Material(Identifier.fromNamespaceAndPath("mina-horror", "block/"+POPLAR_SPRITES[i])), () -> "mina-horror poplar trees");
					return new TreeModel(original, profile, axis, context.baker().materials().get(ATLAS, () -> "mina-horror natural trees"), poplar, trees);
				});
			});
		});
	}

	public static boolean replaces(BlockState state) { return PROFILES.containsKey(state.getBlock()); }

	private static boolean sameWood(BlockState a, BlockState b) {
		Profile pa = PROFILES.get(a.getBlock()), pb = PROFILES.get(b.getBlock());
		return pa != null && pb != null && pa.kind == 0 && pb.kind == 0 && pa.bark == pb.bark;
	}

	private static final class TreeModel extends WrapperBlockStateModel {
		private final Profile profile;
		private final int axis;
		private final Material.Baked atlas;
		private final Material.Baked[] poplarMaterials;
		private final Map<Shape, Mesh> meshes = new ConcurrentHashMap<>();
		private final NaturalTrees trees;

		TreeModel(BlockStateModel original, Profile profile, int axis, Material.Baked atlas, Material.Baked[] poplarMaterials, NaturalTrees trees) {
			super(original);
			this.profile = profile;
			this.axis = axis;
			this.atlas = atlas;
			this.poplarMaterials = poplarMaterials;
			this.trees = trees;
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			// Blocks of a recognised whole tree draw their share of it (log blocks may carry leaf cards too).
			NaturalTrees.Hit tree = NaturalTrees.handles(state) ? trees.find(level, pos, state) : null;
			Mesh mesh = tree != null ? tree.tree().mesh(tree.key(), this::bakeFaces) : meshes.computeIfAbsent(shape(level, pos, state), this::bake);
			// Colored photographic leaves receive a gentle biome tint instead of being tinted twice. Whole trees may mix
			// leaf kinds, so there the baked tag decides per quad.
			boolean tint = tree != null
				|| profile.kind == 1 && (profile.bark == 0 || profile.bark == 3 || profile.bark == 4 || profile.bark == 5 || profile.bark == 6) && profile.leaf < 21;
			int biome = tint ? BiomeColors.getAverageFoliageColor(level, pos) : 0xffffff;
			int color = 0xff000000 | (205 + ((biome >> 16 & 255) * 50 / 255)) << 16 | (205 + ((biome >> 8 & 255) * 50 / 255)) << 8 | (205 + ((biome & 255) * 50 / 255));
			int[] light = new int[6];
			for (int i = 0; i < 6; i++) light[i] = LightCoordsUtil.getLightCoords(level, pos.relative(DIRECTIONS[i]));
			// Rounded faces often point into the ground or the next log (both opaque, light 0) and would turn black;
			// every face gets at least the brightest neighbouring light.
			int brightest = light[0];
			for (int i = 1; i < 6; i++) brightest = LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(brightest), LightCoordsUtil.block(light[i])),
				Math.max(LightCoordsUtil.sky(brightest), LightCoordsUtil.sky(light[i])));
			// Whole-tree geometry reaches beyond its block, and a block inside a trunk or canopy may have only dark
			// face neighbours: the surrounding 3x3x3 decides there.
			if (tree != null) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				int around = LightCoordsUtil.getLightCoords(level, pos.offset(dx, dy, dz));
				brightest = LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(brightest), LightCoordsUtil.block(around)),
					Math.max(LightCoordsUtil.sky(brightest), LightCoordsUtil.sky(around)));
			}
			for (int i = 0; i < 6; i++) light[i] = brightest;
			// Inset curved faces otherwise sample the opaque vanilla log's completely dark interior.
			emitter.pushTransform(quad -> {
				// Sodium's copyFrom keeps the "geometry computed" flag but not the cached normal face, which leaves
				// the facing null for mesh quads; rewriting a vertex position forces it to be recomputed.
				quad.pos(0, quad.x(0), quad.y(0), quad.z(0));
				quad.minLightmap(light[quad.lightFace().get3DDataValue()]);
				if (tint && quad.tag() == 1) quad.multiplyColor(color);
				return true;
			});
			try {
				mesh.outputTo(emitter);
			} finally { emitter.popTransform(); }
		}

		/** True if the log's own axis points toward the offset and nothing continues it on that end. */
		private static boolean openEndToward(BlockAndTintGetter level, BlockPos pos, BlockState state, int dx, int dy, int dz) {
			Direction.Axis axis = state.hasProperty(BlockStateProperties.AXIS) ? state.getValue(BlockStateProperties.AXIS) : Direction.Axis.Y;
			int step = switch (axis) { case X -> dx; case Y -> dy; case Z -> dz; };
			if (step == 0) return false;
			BlockPos end = switch (axis) { case X -> pos.offset(step, 0, 0); case Y -> pos.offset(0, step, 0); case Z -> pos.offset(0, 0, step); };
			return !sameWood(state, level.getBlockState(end));
		}

		@Override
		public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
			// Biome color and neighboring logs affect the emitted geometry; never reuse the wrapped cube key.
			return null;
		}
		@Override public int materialFlags() { return wrapped.materialFlags() & BakedQuad.FLAG_ANIMATED; }
		@Override public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) { return materialFlags(); }
		@Override public boolean hasMaterialFlag(int flag) { return (materialFlags() & flag) != 0; }
		@Override public boolean hasMaterialFlag(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, int flag) { return hasMaterialFlag(flag); }

		private Shape shape(BlockAndTintGetter level, BlockPos pos, BlockState state) {
			int variant = Math.floorMod(pos.getX()*31 + pos.getY()*7 + pos.getZ()*13, 4);
			if (profile.kind != 0) return new Shape(0, 0, variant, false, 0, 0);
			int connections = 0;
			for (int i = 0; i < DIRECTIONS.length; i++) if (sameWood(state, level.getBlockState(pos.relative(DIRECTIONS[i])))) connections |= 1 << i;
			int corner = 0;
			if (axis == 1) {
				// Detect all four quarters together, so giant spruce/jungle/dark-oak trunks form one broad bole.
				for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
					if (verticalWood(level, pos.offset(dx, 0, 0), state) && verticalWood(level, pos.offset(0, 0, dz), state)
						&& verticalWood(level, pos.offset(dx, 0, dz), state)) corner = 1 + (dx > 0 ? 1 : 0) + (dz > 0 ? 2 : 0);
				}
			}
			BlockState below = level.getBlockState(pos.below());
			boolean rooted = axis == 1 && below.isSolidRender() && !replaces(below);
			int edges = 0, thick = 0;
			if (corner == 0) {
				// Every rule below gives the same answer from both logs, so each connection is drawn from both sides.
				Direction.Axis own = axisOf(state);
				for (int k = 0; k < 26; k++) {
					int[] d = TreeGeometry.NEIGHBORS[k];
					BlockState neighbor = level.getBlockState(pos.offset(d[0], d[1], d[2]));
					if (!sameWood(state, neighbor)) continue;
					if (Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]) == 1) {
						// Face neighbours only join when one of the two logs actually runs that way; logs stacked or laid
						// side by side (walls, floors) stay separate.
						Direction.Axis along = d[0] != 0 ? Direction.Axis.X : d[1] != 0 ? Direction.Axis.Y : Direction.Axis.Z;
						if (own != along && axisOf(neighbor) != along) continue;
						edges |= 1 << k;
						if (along == Direction.Axis.Y && own == Direction.Axis.Y && axisOf(neighbor) == Direction.Axis.Y) thick |= 1 << k;
					} else if (!faceRouteExists(level, pos, state, d) && openEndToward(level, pos, state, d[0], d[1], d[2])
						&& openEndToward(level, pos.offset(d[0], d[1], d[2]), neighbor, -d[0], -d[1], -d[2])) {
						// A limb continuing diagonally from the free ends of both logs.
						edges |= 1 << k;
					}
				}
			}
			return new Shape(connections, corner, rooted ? variant : 0, rooted, edges, thick);
		}
		private static Direction.Axis axisOf(BlockState state) {
			return state.hasProperty(BlockStateProperties.AXIS) ? state.getValue(BlockStateProperties.AXIS) : Direction.Axis.Y;
		}
		/** Any log on the blocks between two diagonal neighbours (the same set seen from either side). */
		private static boolean faceRouteExists(BlockAndTintGetter level, BlockPos pos, BlockState state, int[] d) {
			for (int mask = 1; mask < 7; mask++) {
				int x = (mask & 1) != 0 ? d[0] : 0, y = (mask & 2) != 0 ? d[1] : 0, z = (mask & 4) != 0 ? d[2] : 0;
				if ((x == 0 && y == 0 && z == 0) || (x == d[0] && y == d[1] && z == d[2])) continue;
				if (sameWood(state, level.getBlockState(pos.offset(x, y, z)))) return true;
			}
			return false;
		}
		private boolean verticalWood(BlockAndTintGetter level, BlockPos pos, BlockState state) {
			BlockState neighbor = level.getBlockState(pos);
			return sameWood(state, neighbor) && neighbor.hasProperty(BlockStateProperties.AXIS) && neighbor.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y;
		}

		private Mesh bake(Shape shape) {
			List<TreeGeometry.Face> geometry = switch (profile.kind) {
				case 0 -> shape.corner != 0
					? TreeGeometry.trunk(profile.bark, axis, shape.connections, shape.corner, shape.variant, shape.rooted)
					: TreeGeometry.limb(profile.bark, axis, shape.edges, shape.thick, shape.rooted, shape.variant,
						profile.bark == 4 || profile.bark == 7 ? .30f : .40f, .23f);
				case 2 -> TreeGeometry.roots(shape.variant);
				default -> TreeGeometry.foliage(profile.leaf, profile.bark, shape.variant);
			};
			return bakeFaces(geometry);
		}

		/** Leaves vanilla colours by biome: oak, jungle, acacia, dark oak and mangrove. */
		private static boolean biomeTinted(int tile) { return tile == 12 || (tile >= 15 && tile <= 18); }

		private Mesh bakeFaces(List<TreeGeometry.Face> geometry) {
			MutableMesh mesh = Renderer.get().mutableMesh();
			QuadEmitter emitter = mesh.emitter();
			for (TreeGeometry.Face face : geometry) {
				boolean originalBark = profile.stripped && !face.foliage() && face.tile() != 11;
				int tile = face.tile() == 11 && profile.allBark ? profile.bark : face.tile();
				boolean poplar = tile >= 24;
				Material.Baked material = originalBark ? wrapped.particleMaterial() : poplar ? poplarMaterials[tile-24] : atlas;
				for (int i = 0; i < 4; i++) {
					emitter.pos(i, face.positions()[i]).normal(i, face.normals()[i]).color(i, face.color());
					float u = face.uv()[i*2], v = face.uv()[i*2+1];
					// Keep UVs one texel inside each tile to avoid bleeding at atlas boundaries.
					if (!originalBark && !poplar) {
						u = (tile % 4 + .004f + u*.992f)/4;
						v = (tile / 4 + .004f + v*.992f)/6;
					}
					emitter.uv(i, u, v);
				}
				emitter.materialBake(material, MutableQuadView.BAKE_NORMALIZED);
				// AI image alpha is antialiased; explicit cutout avoids translucent sorting and solid leaf backgrounds.
				emitter.chunkLayer(face.foliage() ? ChunkSectionLayer.CUTOUT : ChunkSectionLayer.SOLID);
				emitter.tintIndex(-1).tag(face.foliage() ? (biomeTinted(tile) ? 1 : 2) : 0).cullFace(null).ambientOcclusion(TriState.FALSE).emit();
			}
			return mesh.immutableCopy();
		}
	}
}
