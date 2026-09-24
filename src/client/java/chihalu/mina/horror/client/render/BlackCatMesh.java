package chihalu.mina.horror.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Triangle mesh generated from the three-view reference sheet (black_cat.bin = sitting, black_cat_walk.bin = standing).
 * Positions are in model space (blocks, y down, feet at y = 1.5, front towards -z). Each vertex carries a
 * texture coordinate into black_cat.png plus weights for the head (look), tail (sway) and one of four legs (swing).
 */
public final class BlackCatMesh {
	private static final int MAGIC = 0x43415432;
	public static final int LEG_COUNT = 4;
	private static BlackCatMesh sitting;
	private static BlackCatMesh walking;

	private final int vertexCount;
	private final float[] pos;
	private final float[] normal;
	private final float[] uv;
	private final float[] headWeight;
	private final float[] tailWeight;
	private final int[] legIndex;
	private final float[] legWeight;
	private final Vector3f headPivot;
	private final Vector3f tailPivot;
	private final Vector3f[] legPivots = new Vector3f[LEG_COUNT];

	private BlackCatMesh(final DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("Not a black cat mesh");
		}

		this.vertexCount = in.readInt();
		this.headPivot = readVector(in);
		this.tailPivot = readVector(in);
		for (int leg = 0; leg < LEG_COUNT; leg++) {
			this.legPivots[leg] = readVector(in);
		}

		this.pos = new float[this.vertexCount * 3];
		this.normal = new float[this.vertexCount * 3];
		this.uv = new float[this.vertexCount * 2];
		this.headWeight = new float[this.vertexCount];
		this.tailWeight = new float[this.vertexCount];
		this.legIndex = new int[this.vertexCount];
		this.legWeight = new float[this.vertexCount];
		for (int i = 0; i < this.vertexCount; i++) {
			for (int k = 0; k < 3; k++) {
				this.pos[i * 3 + k] = in.readFloat();
			}

			for (int k = 0; k < 3; k++) {
				this.normal[i * 3 + k] = in.readFloat();
			}

			this.uv[i * 2] = in.readFloat();
			this.uv[i * 2 + 1] = in.readFloat();
			this.headWeight[i] = in.readFloat();
			this.tailWeight[i] = in.readFloat();
			this.legIndex[i] = Math.round(in.readFloat());
			this.legWeight[i] = in.readFloat();
		}
	}

	private static Vector3f readVector(final DataInputStream in) throws IOException {
		return new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
	}

	private static BlackCatMesh load(final String name) {
		String path = "/assets/mina-horror/models/entity/" + name;
		try (InputStream raw = BlackCatMesh.class.getResourceAsStream(path)) {
			if (raw == null) {
				throw new IOException("Missing " + path);
			}

			return new BlackCatMesh(new DataInputStream(new BufferedInputStream(raw)));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load black cat mesh " + name, e);
		}
	}

	public static BlackCatMesh sitting() {
		if (sitting == null) {
			sitting = load("black_cat.bin");
		}

		return sitting;
	}

	public static BlackCatMesh walking() {
		if (walking == null) {
			walking = load("black_cat_walk.bin");
		}

		return walking;
	}

	/**
	 * Emits the mesh. Triangles are written as quads with the last vertex repeated,
	 * since entity render types draw quads. legSwing holds one pitch angle per leg.
	 */
	public void render(
		final PoseStack.Pose pose, final VertexConsumer buffer, final int light, final int overlay,
		final float headYaw, final float headPitch, final float tailSwing, final float[] legSwing
	) {
		Matrix3f head = new Matrix3f().rotationZYX(0.0F, headYaw, headPitch);
		Matrix3f tail = new Matrix3f().rotationY(tailSwing);
		Matrix3f[] legs = new Matrix3f[LEG_COUNT];
		for (int leg = 0; leg < LEG_COUNT; leg++) {
			legs[leg] = new Matrix3f().rotationX(legSwing[leg]);
		}

		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		Vector3f rp = new Vector3f();
		Vector3f rn = new Vector3f();
		for (int tri = 0; tri < this.vertexCount; tri += 3) {
			for (int corner = 0; corner < 4; corner++) {
				int i = tri + Math.min(corner, 2);
				p.set(this.pos[i * 3], this.pos[i * 3 + 1], this.pos[i * 3 + 2]);
				n.set(this.normal[i * 3], this.normal[i * 3 + 1], this.normal[i * 3 + 2]);
				int leg = this.legIndex[i];
				if (leg >= 0) {
					applyWeighted(legs[leg], this.legPivots[leg], this.legWeight[i], p, n, rp, rn);
				}

				applyWeighted(head, this.headPivot, this.headWeight[i], p, n, rp, rn);
				applyWeighted(tail, this.tailPivot, this.tailWeight[i], p, n, rp, rn);
				buffer.addVertex(pose, p.x, p.y, p.z)
					.setColor(-1)
					.setUv(this.uv[i * 2], this.uv[i * 2 + 1])
					.setOverlay(overlay)
					.setLight(light)
					.setNormal(pose, n.x, n.y, n.z);
			}
		}
	}

	/** Rotates p/n around pivot, blended with the unrotated values by weight (0..1). */
	private static void applyWeighted(
		final Matrix3f rotation, final Vector3f pivot, final float weight,
		final Vector3f p, final Vector3f n, final Vector3f rp, final Vector3f rn
	) {
		if (weight <= 0.0F) {
			return;
		}

		p.sub(pivot, rp).mul(rotation).add(pivot);
		n.mul(rotation, rn);
		p.lerp(rp, weight);
		n.lerp(rn, weight).normalize();
	}
}
