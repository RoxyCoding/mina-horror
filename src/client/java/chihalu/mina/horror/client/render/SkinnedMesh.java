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
 * Triangle mesh with simple skinning: every vertex follows at most one bone, blended with the body by a weight.
 * Generated from a reference sheet; positions are in model space (blocks, y down, feet at y = 1.5, front towards -z).
 * File layout (big endian): magic, vertex count, one pivot per bone, then per vertex
 * position(3) normal(3) uv(2) bone index weight. Bone 0 is the body and is never transformed.
 */
public final class SkinnedMesh {
	private static final Vector3f UP = new Vector3f(0.0F, -1.0F, 0.0F);
	private final int vertexCount;
	private final float[] pos;
	private final float[] normal;
	private final float[] uv;
	private final int[] bone;
	private final float[] weight;
	private final Vector3f[] pivots;
	private final float flatShading;

	private SkinnedMesh(final DataInputStream in, final int magic, final int boneCount, final float flatShading) throws IOException {
		this.flatShading = flatShading;
		if (in.readInt() != magic) {
			throw new IOException("Unexpected mesh format");
		}

		this.vertexCount = in.readInt();
		this.pivots = new Vector3f[boneCount + 1];
		this.pivots[0] = new Vector3f();
		for (int b = 1; b <= boneCount; b++) {
			this.pivots[b] = new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
		}

		this.pos = new float[this.vertexCount * 3];
		this.normal = new float[this.vertexCount * 3];
		this.uv = new float[this.vertexCount * 2];
		this.bone = new int[this.vertexCount];
		this.weight = new float[this.vertexCount];
		for (int i = 0; i < this.vertexCount; i++) {
			for (int k = 0; k < 3; k++) {
				this.pos[i * 3 + k] = in.readFloat();
			}

			for (int k = 0; k < 3; k++) {
				this.normal[i * 3 + k] = in.readFloat();
			}

			this.uv[i * 2] = in.readFloat();
			this.uv[i * 2 + 1] = in.readFloat();
			this.bone[i] = Math.round(in.readFloat());
			this.weight[i] = in.readFloat();
		}
	}

	/**
	 * @param flatShading 0..1, how far normals are bent towards up; the sheets are drawn with flat anime-style
	 *                    colours, so full directional shading makes them look grey
	 */
	public static SkinnedMesh load(final String name, final int magic, final int boneCount, final float flatShading) {
		String path = "/assets/mina-horror/models/entity/" + name;
		try (InputStream raw = SkinnedMesh.class.getResourceAsStream(path)) {
			if (raw == null) {
				throw new IOException("Missing " + path);
			}

			return new SkinnedMesh(new DataInputStream(new BufferedInputStream(raw)), magic, boneCount, flatShading);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load mesh " + name, e);
		}
	}

	/**
	 * Emits the mesh with one rotation per bone (index 0 unused). Triangles are written as quads with the last
	 * vertex repeated, since entity render types draw quads.
	 */
	public void render(final PoseStack.Pose pose, final VertexConsumer buffer, final int light, final int overlay, final Matrix3f[] bones) {
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		Vector3f rp = new Vector3f();
		Vector3f rn = new Vector3f();
		for (int tri = 0; tri < this.vertexCount; tri += 3) {
			for (int corner = 0; corner < 4; corner++) {
				int i = tri + Math.min(corner, 2);
				p.set(this.pos[i * 3], this.pos[i * 3 + 1], this.pos[i * 3 + 2]);
				n.set(this.normal[i * 3], this.normal[i * 3 + 1], this.normal[i * 3 + 2]);
				int b = this.bone[i];
				float w = this.weight[i];
				if (b > 0 && w > 0.0F) {
					Vector3f pivot = this.pivots[b];
					p.sub(pivot, rp).mul(bones[b]).add(pivot);
					n.mul(bones[b], rn);
					p.lerp(rp, w);
					n.lerp(rn, w).normalize();
				}

				if (this.flatShading > 0.0F) {
					n.lerp(UP, this.flatShading).normalize();
				}

				buffer.addVertex(pose, p.x, p.y, p.z)
					.setColor(-1)
					.setUv(this.uv[i * 2], this.uv[i * 2 + 1])
					.setOverlay(overlay)
					.setLight(light)
					.setNormal(pose, n.x, n.y, n.z);
			}
		}
	}
}
