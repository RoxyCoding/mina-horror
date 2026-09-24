package chihalu.mina.horror.client;

import chihalu.mina.horror.client.render.BlackCatRenderer;
import chihalu.mina.horror.client.render.GirlRenderer;
import chihalu.mina.horror.client.render.terrain.SmoothTerrain;
import chihalu.mina.horror.client.render.tree.NaturalTreeModels;
import chihalu.mina.horror.client.shader.BundledShaderPack;
import chihalu.mina.horror.registry.MinaHorrorEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class MinaHorrorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NaturalTreeModels.register();
		SmoothTerrain.register();
		BundledShaderPack.install();
		EntityRenderers.register(MinaHorrorEntities.BLACK_CAT, BlackCatRenderer::new);
		EntityRenderers.register(MinaHorrorEntities.GIRL, GirlRenderer::new);
	}
}
