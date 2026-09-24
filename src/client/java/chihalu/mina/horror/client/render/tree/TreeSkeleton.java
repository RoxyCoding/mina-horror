package chihalu.mina.horror.client.render.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.joml.Vector3f;

/**
 * Grows one whole tree from the vanilla logs and leaves it occupies: the logs become a gently swaying trunk and limbs,
 * thin branches spread through the leaf volume (space colonization), and leaf cards hang from the twigs. Every face is
 * then handed to one block of the tree, so chunk meshing (vanilla or Sodium) still renders block by block.
 * Pure geometry in coordinates relative to the lowest log; the same blocks and seed always give the same tree.
 */
public final class TreeSkeleton {
	/**
	 * How a species looks; the vanilla blocks only decide where wood and foliage go.
	 * @param upward how strongly twigs grow up (negative: drooping)
	 * @param flat   how strongly leaf sprays lie flat (spruce whorls, acacia layers)
	 */
	public record Species(int bark, int leaf, float trunkMin, float trunkMax, float limbMin, float upward, float flat,
		float cardsPerLeaf, float cardMin, float cardMax) { }
	public static final Species OAK = new Species(0, 12, .30f, .44f, .12f, .20f, .6f, 5f, .60f, .95f);
	public static final Species SPRUCE = new Species(1, 13, .26f, .40f, .10f, -.25f, 1.6f, 5f, .55f, .85f);
	public static final Species BIRCH = new Species(2, 14, .22f, .30f, .09f, .10f, .5f, 5f, .50f, .80f);
	public static final Species JUNGLE = new Species(3, 15, .30f, .44f, .12f, .10f, .7f, 4f, .70f, 1.05f);
	public static final Species ACACIA = new Species(4, 16, .24f, .34f, .11f, .02f, 1.8f, 5f, .55f, .85f);
	public static final Species DARK_OAK = new Species(5, 17, .34f, .46f, .14f, .15f, .6f, 5f, .60f, .95f);
	public static final Species MANGROVE = new Species(6, 18, .26f, .38f, .10f, .10f, .6f, 5f, .55f, .90f);
	public static final Species CHERRY = new Species(7, 19, .26f, .36f, .11f, -.05f, .8f, 6f, .55f, .90f);
	public static final Species PALE_OAK = new Species(8, 20, .34f, .46f, .14f, .10f, .6f, 5f, .60f, .95f);
	public static final Species POPLAR = new Species(24, 25, .22f, .30f, .09f, .55f, .3f, 5f, .45f, .75f);

	private static final float TAU = (float) (Math.PI * 2);
	private static final float INFLUENCE = 3f, KILL = .5f, STEP = .38f, TIP = .016f;
	private static final int ITERATIONS = 40, MAX_NODES = 3000, OWNER_REACH = 4, LARGE = 700;
	/** 2x2 trunks (dark oak, pale oak, giant spruce and jungle) become one broad trunk around the shared corner. */
	private static final float WIDE_MIN = .70f, WIDE_MAX = .96f;
	private static final int WIDE = 1 << 24;
	private static final int[][] LOG_NEIGHBORS = logNeighbors();

	private static final class Node {
		final Vector3f p;
		final int parent;
		final boolean wood;
		boolean wide, prop;
		int beside;
		final List<Integer> kids = new ArrayList<>(2);
		float r, sumSq, v;
		int main = -1, sides;
		Vector3f t, u;
		Vector3f[][] ring;
		Node(Vector3f p, int parent, boolean wood) { this.p = p; this.parent = parent; this.wood = wood; }
	}

	private final int[] logKeys, leafKeys;
	private final long seed;
	private final Species species;
	private final Set<Integer> logs = new HashSet<>();
	private final Map<Integer, Integer> leaves = new HashMap<>();
	/** Mangrove root blocks, drawn as prop roots from the trunk into the ground. */
	private final Set<Integer> props = new HashSet<>();
	/** Skeleton node of each log (or 2x2 square). */
	private final Map<Integer, Integer> nodeOf = new HashMap<>();
	/** Corner of the 2x2 trunk square at each height, for trees that stand on a 2x2 trunk. */
	private final Map<Integer, Integer> trunkSquare = new HashMap<>();
	private final List<Node> nodes = new ArrayList<>();
	private final Map<Integer, List<TreeGeometry.Face>> out = new HashMap<>();

	private TreeSkeleton(int[] logKeys, int[] leafKeys, int[] leafTiles, int[] propKeys, long seed, Species species) {
		this.logKeys = logKeys;
		this.leafKeys = leafKeys;
		this.seed = seed;
		this.species = species;
		for (int k : logKeys) logs.add(k);
		for (int i = 0; i < leafKeys.length; i++) leaves.put(leafKeys[i], leafTiles[i]);
		for (int k : propKeys) props.add(k);
	}

	/**
	 * @param logKeys  {@link #pack packed} log positions, including (0,0,0) for the lowest log, in a fixed order
	 * @param leafKeys packed leaf positions in a fixed order
	 * @param leafTiles atlas tile of each leaf block (azalea on oak logs, poplar colours)
	 * @param propKeys packed mangrove root positions in a fixed order
	 * @return faces per owning block (packed key), in coordinates relative to that block
	 */
	public static Map<Integer, List<TreeGeometry.Face>> build(int[] logKeys, int[] leafKeys, int[] leafTiles, int[] propKeys, long seed, Species species) {
		TreeSkeleton tree = new TreeSkeleton(logKeys, leafKeys, leafTiles, propKeys, seed, species);
		tree.growWood();
		tree.growProps();
		tree.colonize();
		tree.radii();
		tree.frames();
		tree.emitWood();
		tree.emitArches();
		tree.emitLeaves();
		return tree.out;
	}

	/** Number of skeleton nodes the logs form, the ground node included (for the geometry check). */
	static int woodNodeCount(int[] logKeys) {
		TreeSkeleton tree = new TreeSkeleton(logKeys, new int[0], new int[0], new int[0], 0, OAK);
		tree.growWood();
		return tree.nodes.size();
	}

	public static int pack(int x, int y, int z) { return (x & 255) << 16 | (y & 255) << 8 | (z & 255); }
	public static int x(int key) { return (byte) (key >> 16); }
	public static int y(int key) { return (byte) (key >> 8); }
	public static int z(int key) { return (byte) key; }

	// ---- skeleton ----

	/** Logs as a spanning tree from the lowest log, with one extra node where the trunk meets the ground. */
	private void growWood() {
		int root = pack(0, 0, 0);
		followTrunk();
		int rootGroup = woodNode(root);
		Vector3f rootCenter = logCenter(rootGroup);
		nodes.get(add(new Node(new Vector3f(rootCenter.x, 0, rootCenter.z), -1, true))).wide = rootGroup != root;
		nodeOf.put(rootGroup, add(new Node(rootCenter, 0, true)));
		nodes.get(nodeOf.get(rootGroup)).wide = rootGroup != root;
		if (rootGroup != root) nodes.get(nodeOf.get(rootGroup)).beside = (int) beside(rootGroup & ~WIDE)[2];
		Set<Integer> visited = new HashSet<>();
		visited.add(root);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			int k = queue.poll();
			int from = nodeOf.get(woodNode(k));
			for (int[] d : LOG_NEIGHBORS) {
				int n = pack(x(k) + d[0], y(k) + d[1], z(k) + d[2]);
				if (!logs.contains(n) || !visited.add(n)) continue;
				// The logs of a 2x2 trunk square share one node.
				int group = woodNode(n);
				if (!nodeOf.containsKey(group)) {
					nodeOf.put(group, add(new Node(logCenter(group), from, true)));
					nodes.get(nodeOf.get(group)).wide = group != n;
					if (group != n) nodes.get(nodeOf.get(group)).beside = (int) beside(group & ~WIDE)[2];
				}
				queue.add(n);
			}
		}
	}

	/** Prop roots follow the root blocks outward from the logs; the block path is softened into arches. */
	private void growProps() {
		if (props.isEmpty()) return;
		int first = nodes.size();
		Map<Integer, Integer> propNode = new HashMap<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int k : logKeys) queue.add(k);
		while (!queue.isEmpty()) {
			int k = queue.poll();
			int from = props.contains(k) ? propNode.get(k) : nodeOf.get(woodNode(k));
			for (int[] d : LOG_NEIGHBORS) {
				int n = pack(x(k) + d[0], y(k) + d[1], z(k) + d[2]);
				if (!props.contains(n) || propNode.containsKey(n)) continue;
				Random random = new Random(seed * 17 + n);
				Vector3f p = new Vector3f(x(n) + .5f + (random.nextFloat() - .5f) * .3f, y(n) + .5f + (random.nextFloat() - .5f) * .2f,
					z(n) + .5f + (random.nextFloat() - .5f) * .3f);
				int node = add(new Node(p, from, true));
				nodes.get(node).prop = true;
				propNode.put(n, node);
				queue.add(n);
			}
		}
		Vector3f[] original = new Vector3f[nodes.size() - first];
		for (int i = first; i < nodes.size(); i++) original[i - first] = new Vector3f(nodes.get(i).p);
		for (int i = first; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			if (node.kids.isEmpty()) {
				// Root ends reach down into the ground (or mud) at the bottom of their block.
				node.p.y = (float) Math.floor(node.p.y) + .02f;
				continue;
			}
			Vector3f parent = node.parent >= first ? original[node.parent - first] : nodes.get(node.parent).p;
			Vector3f mid = new Vector3f(parent).add(original[node.kids.getFirst() - first]).mul(.5f);
			node.p.lerp(mid, .4f);
		}
	}

	/**
	 * Follows a 2x2 trunk up from a full 2x2 base. Dark oak trunks may shift by a block as they rise, and the top
	 * square may miss a log; branch logs beside the trunk never start a second broad trunk.
	 */
	private void followTrunk() {
		int[][] shifts = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		if (squareLogs(0, 0, 0) < 4) return;
		int ax = 0, az = 0;
		for (int y = 0; ; y++) {
			trunkSquare.put(y, pack(ax, y, az));
			boolean found = false;
			for (int[] shift : shifts) {
				if (squareLogs(ax + shift[0], y + 1, az + shift[1]) >= 3) { ax += shift[0]; az += shift[1]; found = true; break; }
			}
			if (!found) return;
		}
	}

	private int squareLogs(int ax, int y, int az) {
		int count = 0;
		for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) if (logs.contains(pack(ax + dx, y, az + dz))) count++;
		return count;
	}

	/**
	 * The log itself, or the 2x2 trunk square it belongs to (named by the square's corner). Logs right beside the
	 * square (dark and pale oak put short log columns there) are part of the trunk as well.
	 */
	private int woodNode(int k) {
		Integer square = trunkSquare.get(y(k));
		if (square == null) return k;
		int dx = x(k) - x(square), dz = z(k) - z(square);
		return dx >= -1 && dx <= 2 && dz >= -1 && dz <= 2 ? square | WIDE : k;
	}

	/** Logs beside a trunk square at its height: their offset from the square's centre (x, z) and count. */
	private float[] beside(int square) {
		float[] result = new float[3];
		for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
			if (dx >= 0 && dx < 2 && dz >= 0 && dz < 2 || !logs.contains(pack(x(square) + dx, y(square), z(square) + dz))) continue;
			result[0] += dx - .5f;
			result[1] += dz - .5f;
			result[2]++;
		}
		return result;
	}

	/** Block (or 2x2) centre with a slow sway, so a straight column of logs becomes a naturally leaning trunk. */
	private Vector3f logCenter(int node) {
		int key = node & ~WIDE;
		float half = (node & WIDE) != 0 ? 1 : .5f;
		// A trunk swells slightly toward the logs beside it.
		float[] side = (node & WIDE) != 0 ? beside(key) : new float[3];
		float lean = side[2] > 0 ? .2f / side[2] : 0;
		float a = (seed & 1023) / 1023f * TAU, b = (seed >>> 10 & 1023) / 1023f * TAU;
		float sx = .09f * (float) Math.sin(y(key) * .8f + a + x(key) * .5f);
		float sz = .09f * (float) Math.sin(y(key) * .65f + b + z(key) * .5f);
		return new Vector3f(x(key) + half + sx + side[0] * lean, y(key) + .5f, z(key) + half + sz + side[1] * lean);
	}

	/** Space colonization: twigs grow step by step toward random points inside the leaf blocks. */
	private void colonize() {
		if (leafKeys.length == 0) return;
		int minLeafY = Integer.MAX_VALUE;
		for (int k : leafKeys) minLeafY = Math.min(minLeafY, y(k));
		float growFrom = minLeafY - 1f;
		List<Vector3f> attractors = new ArrayList<>();
		int perLeaf = leafKeys.length > LARGE ? 1 : 2;
		for (int k : leafKeys) {
			Random random = new Random(seed * 31 + k);
			for (int i = 0; i < perLeaf; i++) {
				attractors.add(new Vector3f(x(k) + .1f + .8f * random.nextFloat(), y(k) + .1f + .8f * random.nextFloat(), z(k) + .1f + .8f * random.nextFloat()));
			}
		}
		boolean[] alive = new boolean[attractors.size()];
		java.util.Arrays.fill(alive, true);
		Map<Long, List<Integer>> grid = new HashMap<>();
		// The ground node and the bare lower trunk never sprout.
		for (int i = 1; i < nodes.size(); i++) if (!nodes.get(i).prop && nodes.get(i).p.y >= growFrom) gridAdd(grid, i);
		Random random = new Random(seed ^ 0x5DEECE66DL);
		for (int iteration = 0; iteration < ITERATIONS && nodes.size() < MAX_NODES; iteration++) {
			float[] pull = new float[nodes.size() * 3];
			boolean any = false;
			for (int a = 0; a < attractors.size(); a++) {
				if (!alive[a]) continue;
				Vector3f target = attractors.get(a);
				int nearest = nearest(grid, target);
				if (nearest < 0) continue;
				float distance = nodes.get(nearest).p.distance(target);
				if (distance < KILL) { alive[a] = false; continue; }
				Vector3f d = new Vector3f(target).sub(nodes.get(nearest).p).div(distance);
				pull[nearest * 3] += d.x; pull[nearest * 3 + 1] += d.y; pull[nearest * 3 + 2] += d.z;
				any = true;
			}
			if (!any) break;
			int count = nodes.size();
			for (int i = 0; i < count; i++) {
				Vector3f dir = new Vector3f(pull[i * 3], pull[i * 3 + 1], pull[i * 3 + 2]);
				if (dir.lengthSquared() < 1e-8f) continue;
				dir.normalize().add((float) random.nextGaussian() * .12f, species.upward + (float) random.nextGaussian() * .12f, (float) random.nextGaussian() * .12f).normalize();
				Vector3f next = new Vector3f(nodes.get(i).p).fma(STEP, dir);
				// Opposite pulls would otherwise keep sprouting the same twig.
				boolean duplicate = false;
				for (int kid : nodes.get(i).kids) if (nodes.get(kid).p.distance(next) < STEP * .35f) { duplicate = true; break; }
				if (duplicate) continue;
				gridAdd(grid, add(new Node(next, i, false)));
			}
		}
	}

	/** Pipe model: a branch is as thick as all the twigs it carries; logs stay within their block. */
	private void radii() {
		for (int i = nodes.size() - 1; i >= 1; i--) {
			Node node = nodes.get(i);
			node.r = node.kids.isEmpty() ? TIP : (float) Math.sqrt(node.sumSq) + .003f;
			// Prop roots thicken toward the trunk but do not thicken the trunk itself.
			if (node.prop) node.r = Math.clamp(node.kids.isEmpty() ? 0 : (float) Math.sqrt(node.sumSq) + .02f, .09f, .22f);
			else if (node.wide) {
				float swell = .08f * Math.min(3, node.beside);
				node.r = Math.clamp(node.r, WIDE_MIN + swell, WIDE_MAX + swell);
			}
			else if (node.wood) node.r = Math.clamp(node.r, i == 1 ? species.trunkMin : species.limbMin, species.trunkMax);
			if (!node.prop || nodes.get(node.parent).prop) nodes.get(node.parent).sumSq += node.r * node.r;
		}
		for (int i = 1; i < nodes.size(); i++) {
			Node node = nodes.get(i), parent = nodes.get(node.parent);
			// The trunk continues upward; roots branch off it.
			Node main = parent.main < 0 ? null : nodes.get(parent.main);
			if (main == null || main.prop && !node.prop || main.prop == node.prop && main.r < node.r) parent.main = i;
		}
		nodes.get(0).r = nodes.get(1).r * 1.12f;
		nodes.get(0).main = 1;
		for (int i = 2; i < nodes.size(); i++) {
			Node node = nodes.get(i), parent = nodes.get(node.parent);
			// The trunk and main limbs taper gradually instead of stepping down where the pipe model drops.
			if (node.wood && !node.prop && parent.main == i && node.wide == parent.wide) node.r = Math.max(node.r, parent.r * .88f);
			// A broad trunk may swell again where logs stand beside it.
			if (!(node.wide && parent.wide)) node.r = Math.min(node.r, parent.r);
		}
	}

	/** A limb continues its parent's surface; everything else starts inside the parent and needs no joint. */
	private boolean continuous(int i) {
		Node node = nodes.get(i), parent = nodes.get(node.parent);
		return parent.main == i && node.r >= .035f && node.wide == parent.wide;
	}

	/**
	 * Ring direction at each node: halfway between the incoming direction and the limb it continues into. Where
	 * nothing continues (a broad trunk handing over to a narrow limb), the ring stays square to the incoming wood.
	 */
	private void frames() {
		for (int i = 0; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			Vector3f in = i == 0 ? null : new Vector3f(node.p).sub(nodes.get(node.parent).p).normalize();
			Vector3f outward = node.main < 0 || !continuous(node.main) ? null : new Vector3f(nodes.get(node.main).p).sub(node.p).normalize();
			node.t = in == null ? outward : outward == null ? in : new Vector3f(in).add(outward);
			if (node.t.lengthSquared() < 1e-6f) node.t = in;
			node.t.normalize();
		}
	}

	// ---- geometry ----

	private void emitWood() {
		int root = pack(0, 0, 0);
		Node base = nodes.get(0);
		base.sides = sidesFor(base.r);
		base.u = perpendicular(new Vector3f(1, 0, 0), base.t);
		for (int i = 1; i < nodes.size(); i++) {
			Node node = nodes.get(i), parent = nodes.get(node.parent);
			Vector3f along = new Vector3f(node.p).sub(parent.p);
			float length = along.length();
			boolean continuous = continuous(i);
			node.sides = continuous ? parent.sides : sidesFor(node.r);
			if (length < 1e-4f) { node.u = parent.u; continue; }
			along.div(length);
			Vector3f startU = continuous ? parent.u : perpendicular(parent.u, along);
			// Each ring frame is carried on from the segment's start, so the tube never twists.
			node.u = perpendicular(startU, node.t);
			Vector3f[][] start = continuous ? ring(parent.p, parent.t, parent.u, parent.r, node.sides)
				: ring(parent.p, along, startU, node.r * 1.05f, node.sides);
			Vector3f[][] end = ring(node.p, node.t, node.u, node.r, node.sides);
			float span = Math.min(1, length / (TAU * Math.max(node.r, .05f)));
			float v0 = continuous ? parent.v : 0;
			if (v0 + span > 1) v0 = 0;
			node.v = v0 + span;
			int owner = ownerOf(new Vector3f(parent.p).add(node.p).mul(.5f));
			if (owner == Integer.MIN_VALUE) continue;
			node.ring = end;
			tube(owner, start, end, v0, v0 + span);
		}
		// Close every end that nothing continues from: branch tips and a broad trunk's top.
		for (int i = 1; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			if (node.ring == null || node.r < .03f || node.main >= 0 && continuous(node.main)) continue;
			int owner = ownerOf(node.p);
			if (owner != Integer.MIN_VALUE) cap(owner, node.ring, new Vector3f(node.p).fma(node.r * (node.wide ? .45f : 1.2f), node.t));
		}
		// Root flares where the trunk meets the ground; mangroves stand on their prop roots instead.
		Random random = new Random(seed + 17);
		for (int i = 0; i < (props.isEmpty() ? 5 : 0); i++) {
			float angle = i * TAU / 5 + random.nextFloat() * .6f;
			Vector3f top = new Vector3f(base.p).add(0, .35f, 0);
			Vector3f foot = new Vector3f(base.p).add((base.r + .2f) * (float) Math.cos(angle), .02f, (base.r + .2f) * (float) Math.sin(angle));
			Vector3f along = new Vector3f(foot).sub(top).normalize();
			Vector3f u = perpendicular(new Vector3f(0, 1, 0), along);
			tube(root, ring(top, along, u, base.r * .38f, 6), ring(foot, along, u, .05f, 6), 0, .5f);
		}
	}

	/**
	 * Mangrove stilt roots: many thin roots leave the lower trunk, arch outward and drop almost straight into the
	 * ground, some forking near the end. The vanilla root blocks decide how far and deep they reach per direction.
	 */
	private void emitArches() {
		if (props.isEmpty()) return;
		Random random = new Random(seed ^ 0xA5A5A5A5L);
		// The trunk axis from the lowest log upward, to start roots along its lower part.
		List<Node> trunk = new ArrayList<>();
		for (int i = 1; i >= 0 && !nodes.get(i).prop && nodes.get(i).wood; i = nodes.get(i).main) {
			trunk.add(nodes.get(i));
			if (nodes.get(i).main < 0) break;
		}
		Vector3f axis = trunk.getFirst().p;
		int ground = Integer.MAX_VALUE;
		float farthest = 0;
		for (int k : props) {
			ground = Math.min(ground, y(k));
			farthest = Math.max(farthest, (float) Math.hypot(x(k) + .5f - axis.x, z(k) + .5f - axis.z));
		}
		int count = Math.clamp(Math.round(props.size() * .8f), 10, 36);
		for (int i = 0; i < count; i++) {
			float angle = (i + random.nextFloat() * .8f) * TAU / count;
			Vector3f out = new Vector3f((float) Math.cos(angle), 0, (float) Math.sin(angle));
			// Reach in this direction: the farthest root block within about 35 degrees, else most of the overall spread.
			float reach = 0;
			for (int k : props) {
				float dx = x(k) + .5f - axis.x, dz = z(k) + .5f - axis.z, d = (float) Math.hypot(dx, dz);
				if (d > .3f && (dx * out.x + dz * out.z) / d > .82f) reach = Math.max(reach, d);
			}
			reach = Math.max(1.4f, (reach > 0 ? reach + .4f : farthest * .8f) * (.75f + random.nextFloat() * .35f));
			float height = random.nextFloat() * 1.8f;
			Node at = trunk.getFirst();
			for (Node node : trunk) if (node.p.y - axis.y <= height) at = node;
			Vector3f start = new Vector3f(at.p.x, axis.y + height, at.p.z).fma(at.r * .6f, out);
			Vector3f land = new Vector3f(axis.x, ground - .15f, axis.z).fma(reach, out);
			float drop = start.y - land.y;
			Vector3f c1 = new Vector3f(start).fma(reach * .45f, out).add(0, .4f + random.nextFloat() * .6f, 0);
			Vector3f c2 = new Vector3f(land).fma(-.15f, out).add(0, drop * .9f, 0);
			float r0 = .06f + random.nextFloat() * .05f;
			arch(start, c1, c2, land, r0, r0 * .55f, 10);
			if (random.nextFloat() < .5f) {
				// A fork leaves the descending part and lands beside the main root.
				Vector3f from = bezier(start, c1, c2, land, .72f);
				Vector3f side = new Vector3f(-out.z, 0, out.x).mul((random.nextBoolean() ? 1 : -1) * (.3f + random.nextFloat() * .4f));
				Vector3f end = new Vector3f(land).add(side).fma(.2f, out);
				Vector3f mid = new Vector3f(from).add(end).mul(.5f).add(0, (from.y - end.y) * .25f, 0).fma(.2f, out);
				arch(from, mid, mid, end, r0 * .6f, r0 * .4f, 6);
			}
		}
	}

	private static Vector3f bezier(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
		float u = 1 - t;
		return new Vector3f(p0).mul(u * u * u).fma(3 * u * u * t, p1).fma(3 * u * t * t, p2).fma(t * t * t, p3);
	}

	/** A thin tube along a cubic curve, its rings carried along without twist. */
	private void arch(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float r0, float r1, int steps) {
		Vector3f[] path = new Vector3f[steps + 1];
		for (int s = 0; s <= steps; s++) path[s] = bezier(p0, p1, p2, p3, (float) s / steps);
		Vector3f[][] previous = null;
		Vector3f u = null;
		float v = 0;
		for (int s = 0; s <= steps; s++) {
			Vector3f tangent = new Vector3f(path[Math.min(s + 1, steps)]).sub(path[Math.max(s - 1, 0)]).normalize();
			u = perpendicular(u == null ? new Vector3f(0, 1, 0) : u, tangent);
			Vector3f[][] ring = ring(path[s], tangent, u, r0 + (r1 - r0) * s / steps, 5);
			if (previous != null) {
				float length = path[s].distance(path[s - 1]);
				float span = Math.min(1, length / (TAU * .08f));
				if (v + span > 1) v = 0;
				int owner = ownerOf(new Vector3f(path[s]).add(path[s - 1]).mul(.5f));
				if (owner != Integer.MIN_VALUE) tube(owner, previous, ring, v, v + span);
				v += span;
			}
			previous = ring;
		}
	}

	private void tube(int owner, Vector3f[][] start, Vector3f[][] end, float v0, float v1) {
		int sides = start[0].length;
		for (int k = 0; k < sides; k++) {
			int n = (k + 1) % sides;
			float u0 = (float) k / sides, u1 = (float) (k + 1) / sides;
			bark(owner, new Vector3f[]{start[0][k], end[0][k], end[0][n], start[0][n]}, new Vector3f[]{start[1][k], end[1][k], end[1][n], start[1][n]},
				new float[]{u0, v0, u0, v1, u1, v1, u1, v0});
		}
	}

	/** Closes a thick branch end with a short cone. */
	private void cap(int owner, Vector3f[][] end, Vector3f tip) {
		int sides = end[0].length;
		for (int k = 0; k < sides; k++) {
			int n = (k + 1) % sides;
			float u0 = (float) k / sides, u1 = (float) (k + 1) / sides;
			bark(owner, new Vector3f[]{end[0][k], end[0][n], tip, tip}, new Vector3f[]{end[1][k], end[1][n], end[1][n], end[1][k]},
				new float[]{u0, 0, u1, 0, u1, .2f, u0, .2f});
		}
	}

	/** Leaf cards at the twigs, with a few extra cards wherever a leaf block ended up without any. */
	private void emitLeaves() {
		if (leafKeys.length == 0) return;
		Vector3f center = new Vector3f();
		for (int k : leafKeys) center.add(x(k) + .5f, y(k) + .5f, z(k) + .5f);
		center.div(leafKeys.length);
		float reach = .5f;
		for (int k : leafKeys) reach = Math.max(reach, center.distance(x(k) + .5f, y(k) + .5f, z(k) + .5f) + .5f);
		List<Integer> twigs = new ArrayList<>();
		for (int i = 0; i < nodes.size(); i++) if (!nodes.get(i).wood && (nodes.get(i).kids.isEmpty() || nodes.get(i).r < .03f)) twigs.add(i);
		Random random = new Random(seed * 0x2545F4914F6CDD1DL + 7);
		Set<Integer> covered = new HashSet<>();
		// Giant trees get somewhat sparser sprays to keep the quad count in check.
		float perTwig = twigs.isEmpty() ? 0 : leafKeys.length * species.cardsPerLeaf * (leafKeys.length > LARGE ? .6f : 1) / twigs.size();
		for (int i : twigs) {
			Node node = nodes.get(i);
			int count = (int) perTwig + (random.nextFloat() < perTwig - (int) perTwig ? 1 : 0);
			Vector3f grow = new Vector3f(node.p).sub(nodes.get(node.parent).p).normalize();
			for (int c = 0; c < count; c++) card(node.p, grow, center, reach, random, covered);
		}
		for (int k : leafKeys) {
			if (covered.contains(k)) continue;
			for (int c = 0; c < 2; c++) {
				Vector3f at = new Vector3f(x(k) + .2f + .6f * random.nextFloat(), y(k) + .2f + .6f * random.nextFloat(), z(k) + .2f + .6f * random.nextFloat());
				card(at, randomUnit(random), center, reach, random, covered);
			}
		}
	}

	/** A double-sided spray hanging from the anchor at a random angle; inner foliage is darker. */
	private void card(Vector3f anchor, Vector3f grow, Vector3f canopyCenter, float reach, Random random, Set<Integer> covered) {
		float size = species.cardMin + random.nextFloat() * (species.cardMax - species.cardMin);
		Vector3f dir = new Vector3f(grow).fma(.9f, randomUnit(random));
		dir.y = (dir.y + .35f) / (1 + species.flat);
		dir.normalize();
		Vector3f facing = randomUnit(random).add(0, species.flat, 0);
		facing.fma(-facing.dot(dir), dir);
		if (facing.lengthSquared() < 1e-4f) facing = perpendicular(new Vector3f(0, 1, 0), dir);
		facing.normalize();
		plate(anchor, dir, facing, size, canopyCenter, reach, random, covered);
	}

	/** One double-sided spray starting at the anchor, running along dir and facing the given way. */
	private void plate(Vector3f anchor, Vector3f dir, Vector3f facing, float size, Vector3f canopyCenter, float reach, Random random, Set<Integer> covered) {
		Vector3f right = new Vector3f(dir).cross(facing).normalize().mul(size * .5f);
		Vector3f base = new Vector3f(anchor).fma(-size * .12f, dir), tip = new Vector3f(anchor).fma(size * .88f, dir);
		Vector3f[] p = {new Vector3f(base).sub(right), new Vector3f(tip).sub(right), new Vector3f(tip).add(right), new Vector3f(base).add(right)};
		Vector3f middle = new Vector3f(anchor).fma(size * .38f, dir);
		int owner = ownerOf(middle);
		if (owner == Integer.MIN_VALUE) return;
		int block = pack((int) Math.floor(middle.x), (int) Math.floor(middle.y), (int) Math.floor(middle.z));
		if (leaves.containsKey(block)) covered.add(block);
		float depth = Math.min(1, middle.distance(canopyCenter) / reach);
		int shade = Math.clamp((int) (255 * (.66f + .34f * depth) + (random.nextFloat() - .5f) * 20), 0, 255);
		int color = 0xff000000 | shade << 16 | shade << 8 | shade;
		float u0 = random.nextBoolean() ? 0 : 1, u1 = 1 - u0;
		Vector3f front = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0])).normalize();
		Vector3f back = new Vector3f(front).negate();
		int tile = leafTileNear(middle);
		face(owner, p, new Vector3f[]{front, front, front, front}, new float[]{u0, 1, u0, 0, u1, 0, u1, 1}, tile, true, color);
		face(owner, new Vector3f[]{p[3], p[2], p[1], p[0]}, new Vector3f[]{back, back, back, back}, new float[]{u1, 1, u1, 0, u0, 0, u0, 1}, tile, true, color);
	}

	// ---- helpers ----

	/** The tree block that renders geometry at this point: the block containing it, else the nearest one. */
	private int ownerOf(Vector3f point) {
		int bx = (int) Math.floor(point.x), by = (int) Math.floor(point.y), bz = (int) Math.floor(point.z);
		int key = pack(bx, by, bz);
		if (logs.contains(key) || leaves.containsKey(key) || props.contains(key)) return key;
		for (int reach = 1; reach <= OWNER_REACH; reach++) {
			int best = Integer.MIN_VALUE;
			float bestDistance = Float.MAX_VALUE;
			for (int dx = -reach; dx <= reach; dx++) for (int dy = -reach; dy <= reach; dy++) for (int dz = -reach; dz <= reach; dz++) {
				if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != reach) continue;
				int k = pack(bx + dx, by + dy, bz + dz);
				if (!logs.contains(k) && !leaves.containsKey(k) && !props.contains(k)) continue;
				float distance = point.distanceSquared(bx + dx + .5f, by + dy + .5f, bz + dz + .5f);
				if (distance < bestDistance) { bestDistance = distance; best = k; }
			}
			if (best != Integer.MIN_VALUE) return best;
		}
		return Integer.MIN_VALUE;
	}

	/** The texture of the leaf block at or nearest to the point, so mixed canopies keep their own leaves. */
	private int leafTileNear(Vector3f point) {
		int bx = (int) Math.floor(point.x), by = (int) Math.floor(point.y), bz = (int) Math.floor(point.z);
		Integer tile = leaves.get(pack(bx, by, bz));
		if (tile != null) return tile;
		float bestDistance = Float.MAX_VALUE;
		int best = species.leaf;
		for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
			Integer t = leaves.get(pack(bx + dx, by + dy, bz + dz));
			if (t == null) continue;
			float distance = point.distanceSquared(bx + dx + .5f, by + dy + .5f, bz + dz + .5f);
			if (distance < bestDistance) { bestDistance = distance; best = t; }
		}
		return best;
	}

	private void bark(int owner, Vector3f[] p, Vector3f[] n, float[] uv) {
		TreeGeometry.addFace(out.computeIfAbsent(owner, k -> new ArrayList<>()), relative(owner, p), n, uv, species.bark);
	}

	private void face(int owner, Vector3f[] p, Vector3f[] n, float[] uv, int tile, boolean foliage, int color) {
		out.computeIfAbsent(owner, k -> new ArrayList<>()).add(new TreeGeometry.Face(relative(owner, p), n, uv, tile, foliage, color));
	}

	private static Vector3f[] relative(int owner, Vector3f[] p) {
		Vector3f[] result = new Vector3f[p.length];
		for (int i = 0; i < p.length; i++) result[i] = new Vector3f(p[i]).sub(x(owner), y(owner), z(owner));
		return result;
	}

	/** Ring positions [0] and outward normals [1] around a centre, perpendicular to the direction. */
	private static Vector3f[][] ring(Vector3f center, Vector3f direction, Vector3f u, float radius, int sides) {
		Vector3f v = new Vector3f(direction).cross(u);
		Vector3f[][] ring = new Vector3f[2][sides];
		for (int k = 0; k < sides; k++) {
			float a = k * TAU / sides;
			Vector3f radial = new Vector3f(u).mul((float) Math.cos(a)).fma((float) Math.sin(a), v);
			ring[0][k] = new Vector3f(center).fma(radius, radial);
			ring[1][k] = radial;
		}
		return ring;
	}

	private static Vector3f perpendicular(Vector3f reference, Vector3f direction) {
		Vector3f u = new Vector3f(reference).fma(-reference.dot(direction), direction);
		if (u.lengthSquared() < 1e-6f) u = new Vector3f(direction).cross(Math.abs(direction.y) < .9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0));
		return u.normalize();
	}

	private static int sidesFor(float radius) {
		return radius >= .5f ? 20 : radius >= .26f ? 14 : radius >= .14f ? 10 : radius >= .06f ? 6 : radius >= .03f ? 4 : 3;
	}

	private static Vector3f randomUnit(Random random) {
		Vector3f v = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian());
		return v.lengthSquared() < 1e-6f ? new Vector3f(0, 1, 0) : v.normalize();
	}

	private int add(Node node) {
		nodes.add(node);
		if (node.parent >= 0) nodes.get(node.parent).kids.add(nodes.size() - 1);
		return nodes.size() - 1;
	}

	private void gridAdd(Map<Long, List<Integer>> grid, int node) {
		grid.computeIfAbsent(cell(nodes.get(node).p, 0, 0, 0), k -> new ArrayList<>()).add(node);
	}

	private int nearest(Map<Long, List<Integer>> grid, Vector3f point) {
		int best = -1;
		float bestDistance = INFLUENCE * INFLUENCE;
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			List<Integer> cell = grid.get(cell(point, dx, dy, dz));
			if (cell == null) continue;
			for (int i : cell) {
				float distance = nodes.get(i).p.distanceSquared(point);
				if (distance < bestDistance) { bestDistance = distance; best = i; }
			}
		}
		return best;
	}

	private static long cell(Vector3f p, int dx, int dy, int dz) {
		long cx = (long) Math.floor(p.x / INFLUENCE) + dx + 1024, cy = (long) Math.floor(p.y / INFLUENCE) + dy + 1024, cz = (long) Math.floor(p.z / INFLUENCE) + dz + 1024;
		return (cx * 2048 + cy) * 2048 + cz;
	}

	/** Face neighbours first, so straight trunks are followed before diagonal limbs. */
	private static int[][] logNeighbors() {
		List<int[]> result = new ArrayList<>();
		for (int order = 1; order <= 3; order++) {
			for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == order) result.add(new int[]{dx, dy, dz});
			}
		}
		return result.toArray(new int[0][]);
	}
}
