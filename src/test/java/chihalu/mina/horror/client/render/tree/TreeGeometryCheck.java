package chihalu.mina.horror.client.render.tree;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.joml.Vector3f;

/** Run with gradlew checkTreeGeometry. No game window or test framework required. */
public final class TreeGeometryCheck {
	public static void main(String[] args) throws Exception {
		int checked = 0;
		for (int axis = 0; axis < 3; axis++) for (int mask = 0; mask < 64; mask++) {
			checked += check(TreeGeometry.trunk(0, axis, mask, 0, 0, true));
		}
		for (int corner = 1; corner <= 4; corner++) checked += check(TreeGeometry.trunk(1, 1, 3, corner, 0, false));
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (Math.abs(dx)+Math.abs(dy)+Math.abs(dz) >= 2) checked += check(TreeGeometry.diagonalBranch(0, dx, dy, dz));
		}
		require(TreeGeometry.trunk(0, 1, 0, 0, 0, true).size() > TreeGeometry.trunk(0, 1, 0, 0, 0, false).size(), "Roots should only grow at ground level");
		for (int tile = 12; tile < 24; tile++) for (int variant = 0; variant < 4; variant++) {
			List<TreeGeometry.Face> leaves = TreeGeometry.foliage(tile, 0, variant);
			checked += check(leaves);
			require(leaves.stream().filter(TreeGeometry.Face::foliage).count() == 20, "Foliage must be double-sided");
		}
		for (int variant = 0; variant < 4; variant++) checked += check(TreeGeometry.roots(variant));
		checked += check(TreeGeometry.trunk(24, 1, 3, 0, 0, true));
		for (int tile = 25; tile < 28; tile++) checked += check(TreeGeometry.foliage(tile, 24, 0));
		// Adjacent vertical blocks must share exactly the same ring at their boundary.
		List<TreeGeometry.Face> lower = TreeGeometry.trunk(0, 1, 3, 0, 0, false);
		List<TreeGeometry.Face> upper = TreeGeometry.trunk(0, 1, 3, 0, 3, false);
		for (int i = 0; i < 24; i++) {
			Vector3f top = lower.get(48+i).positions()[1];
			Vector3f bottom = new Vector3f(upper.get(i).positions()[0]).add(0,1,0);
			require(top.distance(bottom) < .00001f, "Crack between trunk blocks");
		}
		// Every corner of a 2x2 bole meets its neighbor on the same world-space circle.
		for (int corner = 1; corner <= 4; corner++) {
			List<TreeGeometry.Face> wide = TreeGeometry.trunk(1, 1, 3, corner, 0, false);
			float ox = ((corner-1)&1) == 0 ? 1 : 0, oz = ((corner-1)&2) == 0 ? 1 : 0;
			for (int i = 0; i < 12; i++) {
				Vector3f p = wide.get(i).positions()[0];
				float distance = (float)Math.hypot(p.x+ox-1, p.z+oz-1);
				require(distance > .91f && distance < .97f, "2x2 trunk does not share one center");
			}
		}
		// Limbs: every single connection and every pair (face and diagonal), with and without ground roots.
		for (int a = 0; a < 26; a++) {
			checked += check(TreeGeometry.limb(7, 1, 1 << a, 0, false, 0, .30f, .23f));
			for (int b = a + 1; b < 26; b++) checked += check(TreeGeometry.limb(7, 1, 1 << a | 1 << b, 0, b % 3 == 0, 0, .30f, .23f));
		}
		checked += check(TreeGeometry.limb(0, 0, 0, 0, false, 0, .40f, .23f));
		checked += check(TreeGeometry.limb(0, 1, 0, 0, true, 2, .40f, .23f));
		// Neighbouring limbs share the very same ring where they meet, whatever else each one connects to.
		for (int k = 0; k < 26; k++) {
			int[] d = TreeGeometry.NEIGHBORS[k];
			int back = TreeGeometry.neighborIndex(-d[0], -d[1], -d[2]);
			int thick = d[0] == 0 && d[2] == 0 ? 1 << k : 0, thickBack = thick != 0 ? 1 << back : 0;
			List<TreeGeometry.Face> here = TreeGeometry.limb(7, 1, 1 << k | 1 << ((k + 5) % 26), thick, false, 0, .30f, .23f);
			List<TreeGeometry.Face> there = TreeGeometry.limb(7, 1, 1 << back | 1 << ((back + 11) % 26), thickBack, false, 0, .30f, .23f);
			Vector3f shared = new Vector3f(.5f + d[0] * .5f, .5f + d[1] * .5f, .5f + d[2] * .5f);
			Vector3f axis = new Vector3f(d[0], d[1], d[2]).normalize();
			for (Vector3f p : sharedRing(here, shared, axis, new Vector3f())) {
				boolean found = false;
				for (Vector3f q : sharedRing(there, new Vector3f(shared).sub(d[0], d[1], d[2]), axis, new Vector3f(d[0], d[1], d[2]))) {
					if (p.distance(q) < 1e-4f) { found = true; break; }
				}
				require(found, "Crack between limb blocks along " + d[0] + "," + d[1] + "," + d[2]);
			}
		}
		checked += checkWholeTree(vanillaOak(), "oak", TreeSkeleton.OAK);
		checked += checkWholeTree(fancyOak(), "fancy oak", TreeSkeleton.OAK);
		checked += checkWholeTree(darkOak(), "dark oak (2x2)", TreeSkeleton.DARK_OAK);
		checked += checkWholeTree(spruce(), "spruce", TreeSkeleton.SPRUCE);
		checked += checkWholeTree(mangrove(), "mangrove", TreeSkeleton.MANGROVE);
		int[][] wide = darkOak();
		float trunk = 0;
		for (TreeGeometry.Face face : TreeSkeleton.build(wide[0], wide[1], new int[wide[1].length], new int[0], 1L, TreeSkeleton.DARK_OAK).get(TreeSkeleton.pack(0, 0, 0))) {
			if (!face.foliage()) for (Vector3f p : face.positions()) trunk = Math.max(trunk, (float) Math.hypot(p.x - 1, p.z - 1));
		}
		require(trunk > .65f, "2x2 logs should form one broad trunk around their shared corner");
		// Logs beside the trunk join it instead of sticking out as separate thin branches: one node per trunk level.
		require(TreeSkeleton.woodNodeCount(wide[0]) == 1 + 7, "Logs beside the 2x2 trunk became separate branches");
		BufferedImage atlas = ImageIO.read(Path.of("src/main/resources/assets/mina-horror/textures/block/tree_atlas.png").toFile());
		require(atlas.getWidth()*3 == atlas.getHeight()*2 && atlas.getColorModel().hasAlpha(), "Invalid 4x6 atlas");
		int size = atlas.getWidth()/4;
		for (int tile = 0; tile < 24; tile++) {
			int transparent = 0, opaque = 0;
			for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
				int alpha = atlas.getRGB(tile%4*size+x, tile/4*size+y) >>> 24;
				if (alpha < 64) transparent++;
				if (alpha > 192) opaque++;
			}
			require(opaque > size*size*.05, "Empty texture tile " + tile);
			if (tile >= 12) require(transparent > size*size*.20, "Foliage background is not transparent: " + tile);
			else require(opaque > size*size*.95, "Bark must cover its entire tile: " + tile);
		}
		BufferedImage poplar = ImageIO.read(Path.of("src/main/resources/assets/mina-horror/textures/tree/poplar_atlas.png").toFile());
		require(poplar.getWidth() == poplar.getHeight() && poplar.getColorModel().hasAlpha(), "Invalid poplar atlas");
		int ps = poplar.getWidth()/2;
		for (int tile = 1; tile < 4; tile++) {
			int clear = 0, opaque = 0;
			for (int y = 0; y < ps; y++) for (int x = 0; x < ps; x++) {
				int alpha = poplar.getRGB(tile%2*ps+x, tile/2*ps+y) >>> 24;
				if (alpha < 64) clear++;
				if (alpha > 192) opaque++;
			}
			require(clear > ps*ps*.20 && opaque > ps*ps*.05, "Invalid autumn leaf cutout");
		}
		System.out.println("Tree geometry OK: " + checked + " faces; all axes, 64 connections, diagonal branches, limbs and their seams, 2x2 seams, both atlases.");
	}
	/** Logs [0] and leaves [1] of a vanilla-shaped oak: five logs, two wide leaf layers and two narrow ones. */
	private static int[][] vanillaOak() {
		List<Integer> logs = new ArrayList<>(), leaves = new ArrayList<>();
		for (int y = 0; y < 5; y++) logs.add(TreeSkeleton.pack(0, y, 0));
		for (int y = 2; y <= 5; y++) {
			int r = y < 4 ? 2 : 1;
			for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
				if (Math.abs(x) == r && Math.abs(z) == r && (y == 5 || (x + z + y) % 2 == 0)) continue;
				if (x == 0 && z == 0 && y < 5) continue;
				leaves.add(TreeSkeleton.pack(x, y, z));
			}
		}
		return new int[][]{sorted(logs), sorted(leaves)};
	}

	/** A tall trunk with diagonal limbs ending in round leaf clusters, like vanilla's fancy oak. */
	private static int[][] fancyOak() {
		List<Integer> logs = new ArrayList<>(), leaves = new ArrayList<>();
		for (int y = 0; y < 11; y++) logs.add(TreeSkeleton.pack(0, y, 0));
		int[][] limbs = {{1, 0}, {-1, 1}, {0, -1}, {-1, -1}};
		List<int[]> clusters = new ArrayList<>();
		for (int i = 0; i < limbs.length; i++) {
			int y = 5 + i;
			for (int s = 1; s <= 3; s++) logs.add(TreeSkeleton.pack(limbs[i][0] * s, y + s, limbs[i][1] * s));
			clusters.add(new int[]{limbs[i][0] * 3, y + 3, limbs[i][1] * 3});
		}
		clusters.add(new int[]{0, 10, 0});
		for (int[] c : clusters) for (int x = -2; x <= 2; x++) for (int y = 0; y <= 1; y++) for (int z = -2; z <= 2; z++) {
			if (x * x + z * z > (y == 0 ? 5 : 2)) continue;
			int k = TreeSkeleton.pack(c[0] + x, c[1] + y, c[2] + z);
			if (!logs.contains(k) && !leaves.contains(k)) leaves.add(k);
		}
		return new int[][]{sorted(logs), sorted(leaves)};
	}

	/** A 2x2 trunk that shifts a block partway up, with branch stubs beside it and a broad canopy. */
	private static int[][] darkOak() {
		List<Integer> logs = new ArrayList<>(), leaves = new ArrayList<>();
		for (int y = 0; y < 7; y++) for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) logs.add(TreeSkeleton.pack(x + (y >= 4 ? 1 : 0), y, z));
		// Logs beside the trunk (they form 2x2 squares with trunk logs) and a short log column at the top, as vanilla
		// dark oak places them: all part of the one trunk.
		logs.add(TreeSkeleton.pack(-1, 2, 0));
		logs.add(TreeSkeleton.pack(-1, 2, 1));
		for (int y = 4; y < 7; y++) logs.add(TreeSkeleton.pack(3, y, 1));
		for (int y = 5; y <= 7; y++) for (int x = -3; x <= 4; x++) for (int z = -3; z <= 4; z++) {
			int r = y == 7 ? 2 : 3;
			if (x < 1 - r || x > r || z < 1 - r || z > r || (x == 1 - r || x == r) && (z == 1 - r || z == r)) continue;
			int k = TreeSkeleton.pack(x, y, z);
			if (!logs.contains(k)) leaves.add(k);
		}
		return new int[][]{sorted(logs), sorted(leaves)};
	}

	/** A cone of leaf rings around a tall single trunk. */
	private static int[][] spruce() {
		List<Integer> logs = new ArrayList<>(), leaves = new ArrayList<>();
		for (int y = 0; y < 9; y++) logs.add(TreeSkeleton.pack(0, y, 0));
		for (int y = 2; y <= 9; y++) {
			int r = (9 - y) % 3 == 0 ? 1 : 2 - (y > 6 ? 1 : 0);
			for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
				if (Math.abs(x) + Math.abs(z) > r + (r > 1 ? 1 : 0) || x == 0 && z == 0 && y < 9) continue;
				leaves.add(TreeSkeleton.pack(x, y, z));
			}
		}
		return new int[][]{sorted(logs), sorted(leaves)};
	}

	/** A trunk raised on arched roots: logs [0], leaves [1], roots [2]. */
	private static int[][] mangrove() {
		List<Integer> logs = new ArrayList<>(), leaves = new ArrayList<>(), roots = new ArrayList<>();
		for (int y = 0; y < 7; y++) logs.add(TreeSkeleton.pack(0, y, 0));
		for (int y = -3; y < 0; y++) roots.add(TreeSkeleton.pack(0, y, 0));
		for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}}) {
			for (int s = 1; s <= 3; s++) roots.add(TreeSkeleton.pack(d[0] * Math.min(s, 2), -s, d[1] * Math.min(s, 2)));
		}
		for (int y = 4; y <= 7; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			if (x * x + z * z > (y == 7 ? 2 : 5) || x == 0 && z == 0 && y < 7) continue;
			leaves.add(TreeSkeleton.pack(x, y, z));
		}
		return new int[][]{sorted(logs), sorted(leaves), sorted(roots.stream().distinct().toList())};
	}

	private static int[] sorted(List<Integer> keys) {
		int[] result = keys.stream().mapToInt(Integer::intValue).toArray();
		Arrays.sort(result);
		return result;
	}

	private static int checkWholeTree(int[][] tree, String name, TreeSkeleton.Species species) {
		int[] tiles = new int[tree[1].length];
		Arrays.fill(tiles, species.leaf());
		long started = System.nanoTime();
		int[] roots = tree.length > 2 ? tree[2] : new int[0];
		Map<Integer, List<TreeGeometry.Face>> faces = TreeSkeleton.build(tree[0], tree[1], tiles, roots, 12345L, species);
		long millis = (System.nanoTime() - started) / 1_000_000;
		Map<Integer, List<TreeGeometry.Face>> again = TreeSkeleton.build(tree[0], tree[1], tiles, roots, 12345L, species);
		int total = 0, bark = 0, foliage = 0;
		for (Map.Entry<Integer, List<TreeGeometry.Face>> entry : faces.entrySet()) {
			require(Arrays.binarySearch(tree[0], entry.getKey()) >= 0 || Arrays.binarySearch(tree[1], entry.getKey()) >= 0
				|| Arrays.binarySearch(roots, entry.getKey()) >= 0, "Faces owned by a block outside the tree");
			List<TreeGeometry.Face> other = again.get(entry.getKey());
			require(other != null && other.size() == entry.getValue().size(), "Whole tree is not deterministic");
			for (int i = 0; i < other.size(); i++) require(other.get(i).positions()[0].equals(entry.getValue().get(i).positions()[0]), "Whole tree is not deterministic");
			for (TreeGeometry.Face face : entry.getValue()) {
				// Geometry stays near its block so section vertex formats (Sodium) can hold it.
				for (Vector3f p : face.positions()) require(p.x > -4 && p.x < 5 && p.y > -4 && p.y < 5 && p.z > -4 && p.z < 5, "Geometry too far from its block");
				if (face.foliage()) foliage++; else bark++;
				// Thin twigs bend sharply, so only the averaged vertex normal has to agree with the winding.
				Vector3f[] p = face.positions();
				Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
				Vector3f normal = new Vector3f();
				for (Vector3f n : face.normals()) normal.add(n);
				require(cross.lengthSquared() > 1e-12f && cross.dot(normal) > 0, "Inward winding");
				for (float uv : face.uv()) require(Float.isFinite(uv) && uv >= -.001f && uv <= 1.001f, "UV outside tile");
				for (int i = 0; i < 4; i++) require(p[i].isFinite() && face.normals()[i].isFinite(), "Non-finite geometry");
			}
			total += entry.getValue().size();
		}
		require(faces.size() == again.size(), "Whole tree is not deterministic");
		for (int leaf : tree[1]) {
			boolean leafy = false;
			for (TreeGeometry.Face face : faces.getOrDefault(leaf, List.of())) leafy |= face.foliage();
			require(leafy, "Leaf block without leaves");
		}
		require(faces.containsKey(TreeSkeleton.pack(0, 0, 0)), "No trunk at the root");
		for (int root : roots) {
			// A prop root passes through every root block (faces may be drawn by a neighbouring block).
			boolean reached = false;
			for (Map.Entry<Integer, List<TreeGeometry.Face>> entry : faces.entrySet()) for (TreeGeometry.Face face : entry.getValue()) {
				if (face.foliage()) continue;
				for (Vector3f p : face.positions()) {
					float x = p.x + TreeSkeleton.x(entry.getKey()) - TreeSkeleton.x(root), y = p.y + TreeSkeleton.y(entry.getKey()) - TreeSkeleton.y(root);
					float z = p.z + TreeSkeleton.z(entry.getKey()) - TreeSkeleton.z(root);
					reached |= x > 0 && x < 1 && y > 0 && y < 1 && z > 0 && z < 1;
				}
			}
			require(reached, "Root block without a root");
		}
		System.out.println("Whole " + name + ": " + tree[0].length + " logs, " + tree[1].length + " leaves -> " + bark + " bark + " + foliage + " leaf quads in " + millis + " ms");
		return total;
	}

	private static int check(List<TreeGeometry.Face> faces) {
		for (TreeGeometry.Face face : faces) {
			require(face.tile() >= 0 && face.tile() < 28, "Invalid atlas tile");
			for (float uv : face.uv()) require(Float.isFinite(uv) && uv >= -.001f && uv <= 1.001f, "UV outside tile");
			Vector3f[] p = face.positions();
			Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
			require(cross.lengthSquared() > 1e-12f, "Zero-area face");
			for (int i = 0; i < 4; i++) {
				require(p[i].isFinite() && face.normals()[i].isFinite(), "Non-finite geometry");
				require(cross.dot(face.normals()[i]) > 0, "Inward winding / mismatched normal");
			}
		}
		return faces.size();
	}
	/** Vertices lying on the plane through the shared point, moved by offset (into the other block's frame). */
	private static List<Vector3f> sharedRing(List<TreeGeometry.Face> faces, Vector3f shared, Vector3f axis, Vector3f offset) {
		List<Vector3f> ring = new java.util.ArrayList<>();
		for (TreeGeometry.Face face : faces) for (Vector3f p : face.positions()) {
			if (Math.abs(new Vector3f(p).sub(shared).dot(axis)) < 1e-5f && p.distance(shared) < .5f && p.distance(shared) > .1f) {
				ring.add(new Vector3f(p).add(offset));
			}
		}
		require(!ring.isEmpty(), "Limb does not reach the shared point");
		return ring;
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
