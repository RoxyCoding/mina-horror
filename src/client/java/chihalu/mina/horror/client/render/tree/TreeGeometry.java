package chihalu.mina.horror.client.render.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Vector3f;

/** Geometry in block coordinates; independent of the game so seams and winding can be checked. */
public final class TreeGeometry {
	public record Face(Vector3f[] positions, Vector3f[] normals, float[] uv, int tile, boolean foliage, int color) { }
	private static final float TAU = (float) (Math.PI * 2);
	private TreeGeometry() { }

	/** 24-sided trunks, shared end rings, and broad quarter sections for vanilla 2x2 trunks. */
	public static List<Face> trunk(int tile, int axis, int connections, int corner, int variant, boolean rooted) {
		List<Face> faces = new ArrayList<>();
		boolean wide = corner != 0;
		float cx = wide ? (((corner - 1) & 1) != 0 ? 1 : 0) : .5f;
		float cz = wide ? (((corner - 1) & 2) != 0 ? 1 : 0) : .5f;
		// Acacia (4) and cherry (7) zigzag, so vertical logs are often part of a limb; keep them close to limb size.
		float vertical = tile == 4 || tile == 7 ? .30f : .43f;
		float radius = wide ? .94f : (axis == 1 ? vertical : .23f);
		float start = wide ? (cx == 1 ? (cz == 1 ? TAU / 2 : TAU / 4) : (cz == 1 ? -TAU / 4 : 0)) : 0;
		int segments = wide ? 12 : 24;
		float arc = wide ? TAU / 4 : TAU;
		int negative = axis == 1 ? 0 : axis == 0 ? 4 : 2;
		int positive = negative + 1;
		boolean bottom = (connections & (1 << negative)) == 0;
		boolean top = (connections & (1 << positive)) == 0;
		float[] heights = {0, .18f, .62f, 1};
		// An unconnected end usually continues diagonally (cherry/acacia) or hides under leaves: narrow slightly and
		// end in a flat cut instead of a spike.
		float[] radii = {radius * (rooted && bottom && axis == 1 ? 1.09f : 1), radius * 1.015f, radius * (top && !wide ? .93f : .995f), radius * (top && !wide ? .86f : 1)};
		for (int ring = 0; ring < heights.length - 1; ring++) {
			for (int s = 0; s < segments; s++) {
				float a = start + arc * s / segments, b = start + arc * (s + 1) / segments;
				Vector3f[] p = {ring(cx, cz, heights[ring], radii[ring], a), ring(cx, cz, heights[ring + 1], radii[ring + 1], a),
					ring(cx, cz, heights[ring + 1], radii[ring + 1], b), ring(cx, cz, heights[ring], radii[ring], b)};
				Vector3f[] n = {radial(a), radial(a), radial(b), radial(b)};
				for (int i = 0; i < 4; i++) { orient(p[i], axis, true); orient(n[i], axis, false); }
				float u0 = (float) s / segments, u1 = (float) (s + 1) / segments;
				faces.add(new Face(p, n, new float[]{u0, 1-heights[ring], u0, 1-heights[ring+1], u1, 1-heights[ring+1], u1, 1-heights[ring]}, tile, false, -1));
			}
		}
		// Endgrain only on exposed ends; radial segments also close the quarter-circle caps.
		for (int end = 0; end < 2; end++) {
			if (end == 0 ? !bottom : !top) continue;
			float y = end, r = radii[end == 0 ? 0 : 3];
			for (int s = 0; s < segments; s++) {
				float a = start + arc * s / segments, b = start + arc * (s + 1) / segments;
				Vector3f center = new Vector3f(cx, y, cz), p1 = ring(cx, cz, y, r, a), p2 = ring(cx, cz, y, r, b);
				Vector3f[] p = end == 0 ? new Vector3f[]{center, p1, p2, new Vector3f(center)} : new Vector3f[]{center, p2, p1, new Vector3f(center)};
				float[] uv = new float[8];
				Vector3f[] normals = new Vector3f[4];
				for (int i = 0; i < 4; i++) {
					uv[i*2] = .5f + (p[i].x-cx)/(2.04f*r); uv[i*2+1] = .5f + (p[i].z-cz)/(2.04f*r);
					orient(p[i], axis, true); normals[i] = new Vector3f(0, end == 0 ? -1 : 1, 0); orient(normals[i], axis, false);
				}
				faces.add(new Face(p, normals, uv, 11, false, -1));
			}
		}
		// Branch sockets meet neighboring block centers, including horizontal acacia/cherry limbs.
		if (!wide) for (int dir = 0; dir < 6; dir++) {
			if (dir / 2 == (axis == 1 ? 0 : axis == 2 ? 1 : 2) || (connections & (1 << dir)) == 0) continue;
			Vector3f tip = new Vector3f(.5f, .5f, .5f).add(direction(dir).mul(.51f));
			tube(faces, new Vector3f(.5f, .43f, .5f), tip, radius * .82f, .23f, 12, tile);
		}
		if (rooted && bottom && axis == 1 && !wide) for (int i = 0; i < 5; i++) {
			float angle = i * TAU / 5 + variant * .37f;
			Vector3f root = new Vector3f(.5f + .57f*(float)Math.cos(angle), .025f, .5f + .57f*(float)Math.sin(angle));
			// Rounded root flares that fade into the ground instead of thin spikes.
			tube(faces, new Vector3f(.5f, .28f, .5f), root, .17f, .08f, 8, tile);
		}
		return faces;
	}

	/** Each half meets its counterpart at the shared edge/corner between diagonal logs. */
	public static List<Face> diagonalBranch(int tile, int dx, int dy, int dz) {
		List<Face> faces = new ArrayList<>();
		Vector3f center = new Vector3f(.5f, .5f, .5f);
		tube(faces, center, new Vector3f(center).add(dx*.5f, dy*.5f, dz*.5f), .24f, .18f, 12, tile);
		return faces;
	}

	/** Deterministic, inclined leaf sprays: no enclosing leaf cube or camera-facing billboards. */
	public static List<Face> foliage(int tile, int bark, int variant) {
		List<Face> faces = new ArrayList<>();
		Random random = new Random(72819L + variant * 919L + tile * 173L);
		for (int i = 0; i < 10; i++) {
			float angle = i * 2.399963f + variant * .51f;
			float height = .12f + random.nextFloat() * .80f;
			Vector3f stem = new Vector3f(.5f, .42f, .5f);
			Vector3f tip = new Vector3f(.5f + .52f*(float)Math.cos(angle), height, .5f + .52f*(float)Math.sin(angle));
			if (i < 3) tube(faces, stem, tip, .022f, .006f, 5, bark);
			Vector3f center = stem.lerp(tip, .65f, new Vector3f());
			float size = .62f + random.nextFloat() * .23f;
			Vector3f right = new Vector3f((float)Math.cos(angle), .10f, (float)Math.sin(angle)).normalize().mul(size*.5f);
			Vector3f up = new Vector3f(-(float)Math.sin(angle)*.55f, .55f+random.nextFloat()*.5f, (float)Math.cos(angle)*.55f).normalize().mul(size*.5f);
			Vector3f[] p = {new Vector3f(center).sub(right).add(up), new Vector3f(center).sub(right).sub(up),
				new Vector3f(center).add(right).sub(up), new Vector3f(center).add(right).add(up)};
			int shade = 218 + random.nextInt(38);
			int color = 0xff000000 | shade << 16 | shade << 8 | shade;
			quad(faces, p, tile, true, color);
			quad(faces, new Vector3f[]{p[3], p[2], p[1], p[0]}, tile, true, color);
		}
		return faces;
	}

	public static List<Face> roots(int variant) {
		List<Face> faces = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			float a = i*TAU/5;
			Vector3f mid = new Vector3f(.5f+.38f*(float)Math.cos(a+variant*.12f), .55f, .5f+.38f*(float)Math.sin(a+variant*.12f));
			Vector3f end = new Vector3f(.5f+.24f*(float)Math.cos(a), 0, .5f+.24f*(float)Math.sin(a));
			tube(faces, new Vector3f(end).add(0,1,0), mid, .07f, .085f, 8, 6);
			tube(faces, mid, end, .085f, .07f, 8, 6);
		}
		return faces;
	}

	// ---- limbs: logs as a skeleton of smooth tubes between block centres ----

	/** The 26 neighbour offsets; bit k of a limb edge mask refers to NEIGHBORS[k]. */
	public static final int[][] NEIGHBORS = buildNeighbors();
	private static final int LIMB_SIDES = 16;
	private static final int LIMB_STEPS = 8;

	private static int[][] buildNeighbors() {
		int[][] result = new int[26][];
		int i = 0;
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (dx != 0 || dy != 0 || dz != 0) result[i++] = new int[]{dx, dy, dz};
		}
		return result;
	}

	public static int neighborIndex(int dx, int dy, int dz) {
		int index = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
		return index > 13 ? index - 1 : index;
	}

	/**
	 * A log as part of a limb. Every connection is a tube from the block centre to the point shared with the
	 * neighbour (the middle of the face, edge or corner between them). The two most opposite connections form
	 * one smoothly curved tube; further connections meet in a rounded knot; a single connection ends in a
	 * rounded tip. Rings at shared points only depend on the connection itself, so neighbours meet without seams.
	 *
	 * @param edges bit mask over {@link #NEIGHBORS}
	 * @param thick edges drawn with the trunk radius (both sides agree on this), the rest use the limb radius
	 */
	public static List<Face> limb(int tile, int axis, int edges, int thick, boolean rooted, int variant, float trunkRadius, float limbRadius) {
		List<Face> faces = new ArrayList<>();
		Vector3f center = new Vector3f(.5f, .5f, .5f);
		List<Vector3f> dirs = new ArrayList<>();
		List<Float> radii = new ArrayList<>();
		for (int k = 0; k < 26; k++) {
			if ((edges >> k & 1) == 0) continue;
			dirs.add(new Vector3f(NEIGHBORS[k][0], NEIGHBORS[k][1], NEIGHBORS[k][2]));
			radii.add((thick >> k & 1) != 0 ? trunkRadius : limbRadius);
		}
		if (rooted) {
			// Reach down to the ground and spread a few root flares there.
			dirs.add(new Vector3f(0, -1, 0));
			radii.add(trunkRadius * 1.09f);
			for (int i = 0; i < 5; i++) {
				float angle = i * TAU / 5 + variant * .37f;
				Vector3f root = new Vector3f(.5f + (trunkRadius + .14f) * (float) Math.cos(angle), .025f, .5f + (trunkRadius + .14f) * (float) Math.sin(angle));
				tube(faces, new Vector3f(.5f, .28f, .5f), root, trunkRadius * .4f, .08f, 8, tile);
			}
		}
		if (dirs.isEmpty()) {
			// A lone log: straight along its own axis with cut ends.
			Vector3f along = new Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
			float r = axis == 1 ? trunkRadius : limbRadius;
			Vector3f a = new Vector3f(center).fma(-.5f, along), b = new Vector3f(center).fma(.5f, along);
			sweep(faces, a, center, b, r, r, tile);
			disc(faces, a, new Vector3f(along).negate(), r);
			disc(faces, b, along, r);
			return faces;
		}
		if (dirs.size() == 1) {
			// Continue a little past the centre and end in a rounded tip.
			Vector3f d = dirs.get(0);
			float r = radii.get(0);
			Vector3f shared = new Vector3f(center).fma(.5f, d);
			Vector3f tip = new Vector3f(center).fma(-.3f, new Vector3f(d).normalize());
			sweep(faces, shared, new Vector3f(shared).add(tip).mul(.5f), tip, r, r * .85f, tile);
			sphere(faces, tip, r * .85f, tile);
			return faces;
		}
		// The most opposite pair becomes the main, curved limb.
		int bi = 0, bj = 1;
		float best = Float.MAX_VALUE;
		for (int i = 0; i < dirs.size(); i++) for (int j = i + 1; j < dirs.size(); j++) {
			float dot = new Vector3f(dirs.get(i)).normalize().dot(new Vector3f(dirs.get(j)).normalize());
			if (dot < best) { best = dot; bi = i; bj = j; }
		}
		float knot = 0;
		for (int i = 0; i < dirs.size(); i++) {
			Vector3f shared = new Vector3f(center).fma(.5f, dirs.get(i));
			if (best < -.2f && (i == bi || i == bj)) continue;
			sweep(faces, center, new Vector3f(center).add(shared).mul(.5f), shared, radii.get(i), radii.get(i), tile);
			knot = Math.max(knot, radii.get(i));
		}
		if (best < -.2f) {
			Vector3f a = new Vector3f(center).fma(.5f, dirs.get(bi)), b = new Vector3f(center).fma(.5f, dirs.get(bj));
			sweep(faces, a, center, b, radii.get(bi), radii.get(bj), tile);
		}
		if (knot > 0) sphere(faces, center, knot * 1.04f, tile);
		return faces;
	}

	/** Frame for rings on a connection; the sign of the direction is ignored so both neighbours agree. */
	private static Vector3f[] canonicalFrame(Vector3f direction) {
		Vector3f t = new Vector3f(direction).normalize();
		float lead = Math.abs(t.x) > 1e-4f ? t.x : Math.abs(t.y) > 1e-4f ? t.y : t.z;
		if (lead < 0) t.negate();
		Vector3f u = t.cross(Math.abs(t.y) < .9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0), new Vector3f()).normalize();
		return new Vector3f[]{u, t.cross(u, new Vector3f()).normalize()};
	}

	/** Tube along a quadratic Bezier curve; both end rings lie in the canonical frame of the curve direction there. */
	private static void sweep(List<Face> faces, Vector3f p0, Vector3f p1, Vector3f p2, float r0, float r1, int tile) {
		Vector3f[] path = new Vector3f[LIMB_STEPS + 1];
		Vector3f[] tangent = new Vector3f[LIMB_STEPS + 1];
		for (int s = 0; s <= LIMB_STEPS; s++) {
			float t = (float) s / LIMB_STEPS;
			path[s] = new Vector3f(p0).mul((1 - t) * (1 - t)).fma(2 * (1 - t) * t, p1).fma(t * t, p2);
			tangent[s] = new Vector3f(p1).sub(p0).mul(2 * (1 - t)).fma(2 * t, new Vector3f(p2).sub(p1)).normalize();
		}
		// Carry the start frame along the curve (parallel transport), then pair its ring with the end frame's ring
		// (direction and rotation) so that blending towards the end frame does not twist.
		Vector3f[] start = canonicalFrame(tangent[0]);
		Vector3f[] end = canonicalFrame(tangent[LIMB_STEPS]);
		Vector3f[] us = new Vector3f[LIMB_STEPS + 1], vs = new Vector3f[LIMB_STEPS + 1];
		us[0] = start[0]; vs[0] = start[1];
		for (int s = 1; s <= LIMB_STEPS; s++) {
			us[s] = new Vector3f(us[s - 1]).fma(-us[s - 1].dot(tangent[s]), tangent[s]).normalize();
			vs[s] = new Vector3f(vs[s - 1]).fma(-vs[s - 1].dot(tangent[s]), tangent[s]);
			vs[s].fma(-vs[s].dot(us[s]), us[s]).normalize();
		}
		int bestSign = 1, bestShift = 0;
		float bestCost = Float.MAX_VALUE;
		for (int sign = -1; sign <= 1; sign += 2) for (int shift = 0; shift < LIMB_SIDES; shift++) {
			float cost = 0;
			for (int k = 0; k < LIMB_SIDES; k++) {
				cost += radialOf(us[LIMB_STEPS], vs[LIMB_STEPS], k).distanceSquared(radialOf(end[0], end[1], Math.floorMod(sign * k + shift, LIMB_SIDES)));
			}
			if (cost < bestCost) { bestCost = cost; bestSign = sign; bestShift = shift; }
		}
		Vector3f[][] ring = new Vector3f[LIMB_STEPS + 1][LIMB_SIDES];
		Vector3f[][] normal = new Vector3f[LIMB_STEPS + 1][LIMB_SIDES];
		for (int s = 0; s <= LIMB_STEPS; s++) {
			float w = (float) s / LIMB_STEPS, r = r0 + (r1 - r0) * w;
			for (int k = 0; k < LIMB_SIDES; k++) {
				Vector3f radial = s == 0 ? radialOf(start[0], start[1], k)
					: radialOf(us[s], vs[s], k).mul(1 - w).fma(w, radialOf(end[0], end[1], Math.floorMod(bestSign * k + bestShift, LIMB_SIDES)));
				radial.fma(-radial.dot(tangent[s]), tangent[s]).normalize();
				normal[s][k] = radial;
				ring[s][k] = new Vector3f(path[s]).fma(r, radial);
			}
		}
		for (int s = 0; s < LIMB_STEPS; s++) for (int k = 0; k < LIMB_SIDES; k++) {
			int n = (k + 1) % LIMB_SIDES;
			float u0 = (float) k / LIMB_SIDES, u1 = (float) (k + 1) / LIMB_SIDES, v0 = (float) s / LIMB_STEPS, v1 = (float) (s + 1) / LIMB_STEPS;
			addFace(faces, new Vector3f[]{ring[s][k], ring[s + 1][k], ring[s + 1][n], ring[s][n]},
				new Vector3f[]{normal[s][k], normal[s + 1][k], normal[s + 1][n], normal[s][n]},
				new float[]{u0, v0, u0, v1, u1, v1, u1, v0}, tile);
		}
	}

	private static Vector3f radialOf(Vector3f u, Vector3f v, int k) {
		float a = k * TAU / LIMB_SIDES;
		return new Vector3f(u).mul((float) Math.cos(a)).fma((float) Math.sin(a), v);
	}

	private static void sphere(List<Face> faces, Vector3f center, float radius, int tile) {
		int bands = 6;
		for (int i = 0; i < bands; i++) for (int k = 0; k < LIMB_SIDES; k++) {
			float t0 = TAU / 2 * i / bands, t1 = TAU / 2 * (i + 1) / bands;
			float a0 = TAU * k / LIMB_SIDES, a1 = TAU * (k + 1) / LIMB_SIDES;
			Vector3f n00 = spherical(t0, a0), n01 = spherical(t0, a1), n10 = spherical(t1, a0), n11 = spherical(t1, a1);
			// Pole bands collapse to a point; drop the duplicate so the quad keeps a non-zero area.
			Vector3f[] n = i == 0 ? new Vector3f[]{n00, n10, n11, n11} : i == bands - 1 ? new Vector3f[]{n00, n10, n01, n01} : new Vector3f[]{n00, n10, n11, n01};
			Vector3f[] p = new Vector3f[4];
			for (int j = 0; j < 4; j++) p[j] = new Vector3f(center).fma(radius, n[j]);
			float u0 = (float) k / LIMB_SIDES, u1 = (float) (k + 1) / LIMB_SIDES, v0 = (float) i / bands, v1 = (float) (i + 1) / bands;
			addFace(faces, p, n, new float[]{u0, v0, u0, v1, u1, v1, u1, v0}, tile);
		}
	}

	private static Vector3f spherical(float polar, float azimuth) {
		return new Vector3f((float) (Math.sin(polar) * Math.cos(azimuth)), (float) Math.cos(polar), (float) (Math.sin(polar) * Math.sin(azimuth)));
	}

	/** Cut end grain (tile 11) facing along the given normal. */
	private static void disc(List<Face> faces, Vector3f center, Vector3f facing, float radius) {
		Vector3f[] frame = canonicalFrame(facing);
		Vector3f n = new Vector3f(facing).normalize();
		for (int k = 0; k < LIMB_SIDES; k++) {
			Vector3f a = radialOf(frame[0], frame[1], k), b = radialOf(frame[0], frame[1], k + 1);
			Vector3f[] p = {new Vector3f(center), new Vector3f(center).fma(radius, a), new Vector3f(center).fma(radius, b), new Vector3f(center)};
			float[] uv = {.5f, .5f, .5f + a.dot(frame[0]) * .49f, .5f + a.dot(frame[1]) * .49f, .5f + b.dot(frame[0]) * .49f, .5f + b.dot(frame[1]) * .49f, .5f, .5f};
			addFace(faces, p, new Vector3f[]{n, n, n, n}, uv, 11);
		}
	}

	/** Adds a quad, flipping its winding when it disagrees with the averaged normals. */
	static void addFace(List<Face> faces, Vector3f[] p, Vector3f[] n, float[] uv, int tile) {
		Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
		if (cross.lengthSquared() < 1e-12f) return;
		if (cross.dot(new Vector3f(n[0]).add(n[1]).add(n[2]).add(n[3])) < 0) {
			p = new Vector3f[]{p[0], p[3], p[2], p[1]};
			n = new Vector3f[]{n[0], n[3], n[2], n[1]};
			uv = new float[]{uv[0], uv[1], uv[6], uv[7], uv[4], uv[5], uv[2], uv[3]};
		}
		// Triangles are quads with a repeated corner; rotate so the first three corners span the face.
		for (int r = 0; r < 4; r++) {
			if (new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0])).lengthSquared() >= 1e-12f) break;
			p = new Vector3f[]{p[1], p[2], p[3], p[0]};
			n = new Vector3f[]{n[1], n[2], n[3], n[0]};
			uv = new float[]{uv[2], uv[3], uv[4], uv[5], uv[6], uv[7], uv[0], uv[1]};
		}
		faces.add(new Face(p, n, uv, tile, false, -1));
	}

	private static Vector3f ring(float x, float z, float y, float radius, float angle) {
		float ridge = 1 + .018f*(float)Math.cos(angle*12);
		return new Vector3f(x + radius*ridge*(float)Math.cos(angle), y, z + radius*ridge*(float)Math.sin(angle));
	}
	private static Vector3f radial(float angle) { return new Vector3f((float)Math.cos(angle), 0, (float)Math.sin(angle)); }
	private static void orient(Vector3f v, int axis, boolean position) {
		if (axis == 0) v.set(v.y, position ? 1-v.x : -v.x, v.z);
		if (axis == 2) v.set(v.x, position ? 1-v.z : -v.z, v.y);
	}
	private static Vector3f direction(int dir) {
		return switch (dir) {
			case 0 -> new Vector3f(0,-1,0); case 1 -> new Vector3f(0,1,0);
			case 2 -> new Vector3f(0,0,-1); case 3 -> new Vector3f(0,0,1);
			case 4 -> new Vector3f(-1,0,0); default -> new Vector3f(1,0,0);
		};
	}
	private static void tube(List<Face> faces, Vector3f a, Vector3f b, float r0, float r1, int segments, int tile) {
		Vector3f axis = new Vector3f(b).sub(a).normalize();
		Vector3f u = axis.cross(Math.abs(axis.y) < .9f ? new Vector3f(0,1,0) : new Vector3f(1,0,0), new Vector3f()).normalize();
		Vector3f v = axis.cross(u, new Vector3f());
		for (int i = 0; i < segments; i++) {
			Vector3f n0 = new Vector3f(u).mul((float)Math.cos(i*TAU/segments)).fma((float)Math.sin(i*TAU/segments), v);
			Vector3f n1 = new Vector3f(u).mul((float)Math.cos((i+1)*TAU/segments)).fma((float)Math.sin((i+1)*TAU/segments), v);
			Vector3f[] p = {new Vector3f(a).fma(r0,n0), new Vector3f(a).fma(r0,n1), new Vector3f(b).fma(r1,n1), new Vector3f(b).fma(r1,n0)};
			faces.add(new Face(p, new Vector3f[]{n0,n1,n1,n0}, new float[]{0,1,1,1,1,0,0,0}, tile, false, -1));
		}
	}
	private static void quad(List<Face> faces, Vector3f[] p, int tile, boolean foliage, int color) {
		Vector3f normal = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0])).normalize();
		faces.add(new Face(p, new Vector3f[]{normal,normal,normal,normal}, new float[]{0,0,0,1,1,1,1,0}, tile, foliage, color));
	}
}
