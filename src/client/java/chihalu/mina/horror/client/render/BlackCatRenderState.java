package chihalu.mina.horror.client.render;

import chihalu.mina.horror.entity.Broom;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.jspecify.annotations.Nullable;

public class BlackCatRenderState extends LivingEntityRenderState {
	public boolean isSitting;
	/** The broom it rides as a familiar, and the frame's partial tick to follow its motion. */
	public @Nullable Broom broom;
	public float partialTick;
}
