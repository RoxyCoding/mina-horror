package chihalu.mina.horror.client.render.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Recognises whole trees from the blocks around a log or leaf and caches their generated shapes.
 * A leaf belongs to the tree reached by following vanilla's leaf distance downhill, so every block of a forest
 * agrees on its tree no matter which block (or chunk section) looks first.
 */
public final class NaturalTrees {
	record Hit(Grown tree, int key) { }

	static final class Grown {
		private final Map<Integer, List<TreeGeometry.Face>> faces;
		private final Map<Integer, Mesh> meshes = new ConcurrentHashMap<>();
		Grown(Map<Integer, List<TreeGeometry.Face>> faces) { this.faces = faces; }
		Mesh mesh(int key, Function<List<TreeGeometry.Face>, Mesh> bake) {
			return meshes.computeIfAbsent(key, k -> bake.apply(faces.getOrDefault(k, List.of())));
		}
	}

	private record Key(long root, long hash) { }
	private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static final int MAX_LOGS = 400, MAX_LEAVES = 3000, MAX_PROPS = 400, REACH = 16, HEIGHT = 48;
	private static final Hit NONE = new Hit(null, 0);
	/** Trees already found in the chunk section being built on this thread; null outside section builds. */
	private static final ThreadLocal<Map<Long, Hit>> SECTION = new ThreadLocal<>();
	private static final Map<Block, TreeSkeleton.Species> LOGS = new HashMap<>();
	private static final Map<Block, Integer> LEAVES = new HashMap<>();
	/** Mangrove roots become prop roots of the tree they reach first. */
	private static final Block PROP = Blocks.MANGROVE_ROOTS;
	private static final Predicate<BlockState> ROOTS = state -> state.is(PROP);

	static {
		logs(TreeSkeleton.OAK, Blocks.OAK_LOG, Blocks.OAK_WOOD);
		logs(TreeSkeleton.SPRUCE, Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD);
		logs(TreeSkeleton.BIRCH, Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD);
		logs(TreeSkeleton.JUNGLE, Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD);
		logs(TreeSkeleton.ACACIA, Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD);
		logs(TreeSkeleton.DARK_OAK, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD);
		logs(TreeSkeleton.MANGROVE, Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD);
		logs(TreeSkeleton.CHERRY, Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD);
		logs(TreeSkeleton.PALE_OAK, Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_WOOD);
		logs(TreeSkeleton.POPLAR, Blocks.POPLAR_LOG, Blocks.POPLAR_WOOD);
		LEAVES.put(Blocks.OAK_LEAVES, 12);
		LEAVES.put(Blocks.SPRUCE_LEAVES, 13);
		LEAVES.put(Blocks.BIRCH_LEAVES, 14);
		LEAVES.put(Blocks.JUNGLE_LEAVES, 15);
		LEAVES.put(Blocks.ACACIA_LEAVES, 16);
		LEAVES.put(Blocks.DARK_OAK_LEAVES, 17);
		LEAVES.put(Blocks.MANGROVE_LEAVES, 18);
		LEAVES.put(Blocks.CHERRY_LEAVES, 19);
		LEAVES.put(Blocks.PALE_OAK_LEAVES, 20);
		LEAVES.put(Blocks.AZALEA_LEAVES, 21);
		LEAVES.put(Blocks.FLOWERING_AZALEA_LEAVES, 22);
		LEAVES.put(Blocks.YELLOW_POPLAR_LEAVES, 25);
		LEAVES.put(Blocks.ORANGE_POPLAR_LEAVES, 26);
		LEAVES.put(Blocks.RED_POPLAR_LEAVES, 27);
	}

	private static void logs(TreeSkeleton.Species species, Block... blocks) { for (Block block : blocks) LOGS.put(block, species); }

	private final Map<Key, Grown> grown = Collections.synchronizedMap(new LinkedHashMap<>(256, .75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<Key, Grown> eldest) { return size() > 768; }
	});

	public static void beginSection() { SECTION.set(new HashMap<>()); }
	public static void endSection() { SECTION.remove(); }

	/** Player-placed leaves count like natural ones. Nether fungi stay vanilla. */
	static boolean handles(BlockState state) { return LOGS.containsKey(state.getBlock()) || LEAVES.containsKey(state.getBlock()) || state.is(PROP); }

	/** The tree this block belongs to, or null when it is not part of a recognisable tree. */
	Hit find(BlockGetter level, BlockPos pos, BlockState state) {
		Map<Long, Hit> memo = SECTION.get();
		if (memo != null) {
			Hit hit = memo.get(pos.asLong());
			if (hit != null) return hit == NONE ? null : hit;
		}
		// Sodium only copies two blocks around a section, so trees are read from the client world itself;
		// every section then sees the same whole tree.
		BlockGetter world = Minecraft.getInstance().level;
		Hit hit = world != null && world.getBlockState(pos) == state ? scan(world, pos, state, memo) : null;
		if (hit == null && memo != null) memo.put(pos.asLong(), NONE);
		return hit;
	}

	private Hit scan(BlockGetter level, BlockPos pos, BlockState state, Map<Long, Hit> memo) {
		BlockPos start = LOGS.containsKey(state.getBlock()) ? pos : state.is(PROP) ? supporterThrough(level, pos, ROOTS) : descend(level, pos);
		if (start == null) return null;
		// The world can change on the main thread while this runs; any block that no longer fits ends the scan.
		TreeSkeleton.Species species = LOGS.get(level.getBlockState(start).getBlock());
		if (species == null) return null;
		List<BlockPos> logs = floodLogs(level, start, species);
		if (logs == null) return null;
		// A tree touching a building is conservatively left vanilla, rather than reshaping its timber.
		for (BlockPos log : logs) for (Direction direction : DIRECTIONS) {
			BlockState adjacent = level.getBlockState(log.relative(direction));
			Block block = adjacent.getBlock();
			if (adjacent.is(BlockTags.PLANKS) || block instanceof net.minecraft.world.level.block.StairBlock
					|| block instanceof net.minecraft.world.level.block.SlabBlock
					|| block instanceof net.minecraft.world.level.block.DoorBlock
					|| block instanceof net.minecraft.world.level.block.TrapDoorBlock
					|| block instanceof net.minecraft.world.level.block.FenceBlock
					|| block instanceof net.minecraft.world.level.block.WallBlock) return null;
		}
		BlockPos root = logs.getFirst();
		for (BlockPos log : logs) {
			if (log.getY() < root.getY() || log.getY() == root.getY() && (log.getX() < root.getX() || log.getX() == root.getX() && log.getZ() < root.getZ())) root = log;
		}
		BlockState rootState = level.getBlockState(root);
		if (rootState.hasProperty(BlockStateProperties.AXIS) && rootState.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) return null;
		List<BlockPos> leaves = collectLeaves(level, logs);
		if (leaves == null || leaves.size() < 4) return null;
		int[] logKeys = local(logs, root);
		List<BlockPos> props = collectThrough(level, logs, ROOTS, ROOTS, MAX_PROPS);
		if (props == null) return null;
		int[] propKeys = local(props, root);
		// Leaves sorted by position together with their textures.
		long[] leafEntries = new long[leaves.size()];
		for (int i = 0; i < leafEntries.length; i++) {
			BlockPos p = leaves.get(i);
			int key = TreeSkeleton.pack(p.getX() - root.getX(), p.getY() - root.getY(), p.getZ() - root.getZ());
			Integer tile = LEAVES.get(level.getBlockState(p).getBlock());
			if (tile == null) return null;
			leafEntries[i] = (long) key << 8 | tile;
		}
		Arrays.sort(leafEntries);
		int[] leafKeys = new int[leafEntries.length], leafTiles = new int[leafEntries.length];
		for (int i = 0; i < leafEntries.length; i++) { leafKeys[i] = (int) (leafEntries[i] >> 8); leafTiles[i] = (int) (leafEntries[i] & 255); }
		int own = TreeSkeleton.pack(pos.getX() - root.getX(), pos.getY() - root.getY(), pos.getZ() - root.getZ());
		if (Arrays.binarySearch(logKeys, own) < 0 && Arrays.binarySearch(leafKeys, own) < 0 && Arrays.binarySearch(propKeys, own) < 0) return null;
		long hash = logKeys.length * 31L + leafKeys.length + species.bark();
		for (int k : logKeys) hash = (hash ^ k) * 0x100000001B3L;
		for (long e : leafEntries) hash = (hash ^ ~e) * 0x100000001B3L;
		for (int k : propKeys) hash = (hash ^ k ^ 0x5bd1e995) * 0x100000001B3L;
		Key key = new Key(root.asLong(), hash);
		Grown tree = grown.get(key);
		if (tree == null) {
			tree = new Grown(TreeSkeleton.build(logKeys, leafKeys, leafTiles, propKeys, root.asLong() * 0x9E3779B97F4A7C15L, species));
			grown.put(key, tree);
		}
		if (memo != null) {
			for (int[] keys : new int[][]{logKeys, leafKeys, propKeys}) for (int k : keys) {
				memo.put(root.offset(TreeSkeleton.x(k), TreeSkeleton.y(k), TreeSkeleton.z(k)).asLong(), new Hit(tree, k));
			}
		}
		return new Hit(tree, own);
	}

	/** Follows decreasing leaf distance to the log that keeps this leaf alive. */
	private static BlockPos descend(BlockGetter level, BlockPos pos) {
		BlockPos current = pos;
		for (int step = 0; step < 7; step++) {
			BlockState leaf = level.getBlockState(current);
			if (!leaf.hasProperty(BlockStateProperties.DISTANCE)) return null;
			int distance = leaf.getValue(BlockStateProperties.DISTANCE);
			BlockPos next = supporter(level, current, distance);
			if (next == null) return null;
			BlockState nextState = level.getBlockState(next);
			if (distance == 1) return LOGS.containsKey(nextState.getBlock()) ? next : null;
			if (!LEAVES.containsKey(nextState.getBlock())) return null;
			current = next;
		}
		return null;
	}

	/** The first neighbour, in a fixed order, that gives this leaf its distance; the same rule decides membership. */
	private static BlockPos supporter(BlockGetter level, BlockPos pos, int distance) {
		if (distance >= 7) return null;
		for (Direction direction : DIRECTIONS) {
			BlockPos neighbor = pos.relative(direction);
			BlockState state = level.getBlockState(neighbor);
			if (distance == 1 ? state.is(BlockTags.PREVENTS_NEARBY_LEAF_DECAY)
				: state.hasProperty(BlockStateProperties.DISTANCE) && state.getValue(BlockStateProperties.DISTANCE) == distance - 1) return neighbor;
		}
		return null;
	}

	/** The log reached first from a root block, searching outward through connected blocks in a fixed order. */
	private static BlockPos supporterThrough(BlockGetter level, BlockPos pos, Predicate<BlockState> passable) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(pos);
		seen.add(pos.asLong());
		for (int visited = 0; !queue.isEmpty() && visited < MAX_PROPS; visited++) {
			BlockPos from = queue.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos next = from.offset(dx, dy, dz);
				if (!seen.add(next.asLong())) continue;
				BlockState state = level.getBlockState(next);
				if (LOGS.containsKey(state.getBlock())) return next;
				if (passable.test(state)) queue.add(next);
			}
		}
		return null;
	}

	/**
	 * Member blocks connected to these logs whose first-reached log is one of them. Only this tree's blocks are
	 * followed, so a swamp's shared root network is not searched whole.
	 */
	private static List<BlockPos> collectThrough(BlockGetter level, List<BlockPos> logs, Predicate<BlockState> member, Predicate<BlockState> passable, int limit) {
		Set<Long> tree = new HashSet<>(), seen = new HashSet<>();
		for (BlockPos log : logs) { tree.add(log.asLong()); seen.add(log.asLong()); }
		List<BlockPos> found = new ArrayList<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(logs);
		for (int visited = 0; !queue.isEmpty(); visited++) {
			if (visited > limit * 2) return null;
			BlockPos from = queue.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos next = from.offset(dx, dy, dz);
				if (!seen.add(next.asLong())) continue;
				BlockState state = level.getBlockState(next);
				if (member.test(state)) {
					BlockPos supporter = supporterThrough(level, next, passable);
					if (supporter == null || !tree.contains(supporter.asLong())) continue;
					found.add(next);
					if (found.size() > limit) return null;
					queue.add(next);
				} else if (passable.test(state)) {
					queue.add(next);
				}
			}
		}
		return found;
	}

	/** All logs of this species connected to the start (diagonals included); null when too large to be a tree. */
	private static List<BlockPos> floodLogs(BlockGetter level, BlockPos start, TreeSkeleton.Species species) {
		List<BlockPos> logs = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start.asLong());
		while (!queue.isEmpty()) {
			BlockPos log = queue.poll();
			logs.add(log);
			if (logs.size() > MAX_LOGS) return null;
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos next = log.offset(dx, dy, dz);
				if (Math.abs(next.getX() - start.getX()) > REACH || Math.abs(next.getZ() - start.getZ()) > REACH || Math.abs(next.getY() - start.getY()) > HEIGHT) continue;
				if (!seen.add(next.asLong()) || LOGS.get(level.getBlockState(next).getBlock()) != species) continue;
				queue.add(next);
			}
		}
		return logs;
	}

	/** Leaves whose distance leads back to these logs, collected outward one distance step at a time. */
	private static List<BlockPos> collectLeaves(BlockGetter level, List<BlockPos> logs) {
		Set<Long> tree = new HashSet<>(), seen = new HashSet<>();
		for (BlockPos log : logs) { tree.add(log.asLong()); seen.add(log.asLong()); }
		List<BlockPos> leaves = new ArrayList<>(), frontier = logs;
		for (int distance = 1; distance < 7 && !frontier.isEmpty(); distance++) {
			List<BlockPos> next = new ArrayList<>();
			for (BlockPos from : frontier) for (Direction direction : DIRECTIONS) {
				BlockPos leaf = from.relative(direction);
				if (!seen.add(leaf.asLong())) continue;
				BlockState state = level.getBlockState(leaf);
				if (!LEAVES.containsKey(state.getBlock()) || state.getValue(BlockStateProperties.DISTANCE) != distance) continue;
				BlockPos supporter = supporter(level, leaf, distance);
				if (supporter == null || !tree.contains(supporter.asLong())) continue;
				tree.add(leaf.asLong());
				next.add(leaf);
				leaves.add(leaf);
			}
			if (leaves.size() > MAX_LEAVES) return null;
			frontier = next;
		}
		return leaves;
	}

	private static int[] local(List<BlockPos> blocks, BlockPos root) {
		int[] keys = new int[blocks.size()];
		for (int i = 0; i < keys.length; i++) {
			BlockPos p = blocks.get(i);
			keys[i] = TreeSkeleton.pack(p.getX() - root.getX(), p.getY() - root.getY(), p.getZ() - root.getZ());
		}
		Arrays.sort(keys);
		return keys;
	}
}
