package chihalu.mina.horror.registry;

import chihalu.mina.horror.MinaHorror;
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

public final class MinaHorrorComponents {
	/** Whether a flashlight is switched on. */
	public static final DataComponentType<Boolean> FLASHLIGHT_ON = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		MinaHorror.id("flashlight_on"),
		DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build()
	);

	private MinaHorrorComponents() {
	}

	public static void init() {
	}
}
