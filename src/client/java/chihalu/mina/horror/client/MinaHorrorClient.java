package chihalu.mina.horror.client;

import chihalu.mina.horror.client.render.BlackCatRenderer;
import chihalu.mina.horror.client.render.BroomItemRenderer;
import chihalu.mina.horror.client.render.BroomRenderer;
import chihalu.mina.horror.client.render.FlashlightItemModel;
import chihalu.mina.horror.client.render.terrain.SmoothTerrain;
import chihalu.mina.horror.client.render.tree.NaturalTreeModels;
import chihalu.mina.horror.client.shader.BundledShaderPack;
import chihalu.mina.horror.registry.MinaHorrorEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class MinaHorrorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		chihalu.mina.horror.client.settings.TerrainSettings.register();
		NaturalTreeModels.register();
		SmoothTerrain.register();
		BundledShaderPack.install();
		FlashlightItemModel.register();
		BroomItemRenderer.register();
		EntityRenderers.register(MinaHorrorEntities.BLACK_CAT, BlackCatRenderer::new);
		EntityRenderers.register(MinaHorrorEntities.BROOM, BroomRenderer::new);
	}
}
