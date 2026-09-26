package chihalu.mina.horror.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

public class BroomRenderState extends EntityRenderState {
	public float yRot;
	/** The broom's own motion (see BroomMotion): degrees, blocks, and 0..1 for the bristle stream. */
	public float pitch;
	public float roll;
	public float lift;
	public float surge;
	public float sway;
	public float stream;
	public float swingForward;
	public float swingSide;
	public float hurtTime;
	public int hurtDir;
	public float damageTime;
}
