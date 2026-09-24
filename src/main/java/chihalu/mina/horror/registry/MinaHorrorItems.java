package chihalu.mina.horror.registry;

import chihalu.mina.horror.MinaHorror;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

public final class MinaHorrorItems {
	public static final ResourceKey<Item> BLACK_CAT_SPAWN_EGG_KEY = ResourceKey.create(Registries.ITEM, MinaHorror.id("black_cat_spawn_egg"));

	public static final Item BLACK_CAT_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		BLACK_CAT_SPAWN_EGG_KEY,
		new SpawnEggItem(new Item.Properties().spawnEgg(MinaHorrorEntities.BLACK_CAT).setId(BLACK_CAT_SPAWN_EGG_KEY))
	);

	public static final ResourceKey<Item> GIRL_SPAWN_EGG_KEY = ResourceKey.create(Registries.ITEM, MinaHorror.id("girl_spawn_egg"));

	public static final Item GIRL_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		GIRL_SPAWN_EGG_KEY,
		new SpawnEggItem(new Item.Properties().spawnEgg(MinaHorrorEntities.GIRL).setId(GIRL_SPAWN_EGG_KEY))
	);

	private MinaHorrorItems() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS)
			.register(output -> {
				output.insertAfter(Items.CAT_SPAWN_EGG, BLACK_CAT_SPAWN_EGG);
				output.accept(GIRL_SPAWN_EGG);
			});
	}
}
