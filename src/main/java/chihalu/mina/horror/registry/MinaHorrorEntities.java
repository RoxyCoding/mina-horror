package chihalu.mina.horror.registry;

import chihalu.mina.horror.MinaHorror;
import chihalu.mina.horror.entity.BlackCat;
import chihalu.mina.horror.entity.Broom;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class MinaHorrorEntities {
	public static final ResourceKey<EntityType<?>> BLACK_CAT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, MinaHorror.id("black_cat"));

	// Matches the model: 0.65-0.75 blocks tall (standing/sitting), about 0.6 blocks long.
	public static final EntityType<BlackCat> BLACK_CAT = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		BLACK_CAT_KEY,
		EntityType.Builder.<BlackCat>of(BlackCat::new, MobCategory.CREATURE)
			.sized(0.6F, 0.7F)
			.eyeHeight(0.52F)
			.clientTrackingRange(8)
			.build(BLACK_CAT_KEY)
	);

	public static final ResourceKey<EntityType<?>> BROOM_KEY = ResourceKey.create(Registries.ENTITY_TYPE, MinaHorror.id("broom"));

	// The 3-block broom is drawn well past its box; the box covers the seat and stays under a block wide for tunnels.
	public static final EntityType<Broom> BROOM = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		BROOM_KEY,
		EntityType.Builder.<Broom>of(Broom::new, MobCategory.MISC)
			.sized(0.9F, 0.7F)
			.clientTrackingRange(10)
			.build(BROOM_KEY)
	);

	private MinaHorrorEntities() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(BLACK_CAT, BlackCat.createAttributes());
	}
}
