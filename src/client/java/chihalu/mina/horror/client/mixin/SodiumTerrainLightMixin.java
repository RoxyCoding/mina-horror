package chihalu.mina.horror.client.mixin;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve our continuous light field instead of taking max(field, vanilla face light). */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext")
public abstract class SodiumTerrainLightMixin {
    @Unique private int[] mina$light;
    @Unique private boolean mina$restore;

    @Inject(method = "shadeQuad", at = @At("HEAD"))
    private void mina$saveLight(@Coerce Object raw, @Coerce Object mode, boolean emissive,
            @Coerce Object shadeMode, CallbackInfo ci) {
        mina$restore = false;
        MutableQuadView quad = mina$quad(raw);
        if (!emissive
                && (quad.tag() == 0x4D4154 || quad.tag() == 0x4D4C54)) {
            if (mina$light == null) mina$light = new int[4];
            for (int i=0;i<4;i++) mina$light[i]=quad.lightmap(i);
            mina$restore=true;
        }
    }

    @Inject(method = "shadeQuad", at = @At("RETURN"))
    private void mina$restoreLight(@Coerce Object raw, @Coerce Object mode, boolean emissive,
            @Coerce Object shadeMode, CallbackInfo ci) {
        if (mina$restore) {
            MutableQuadView quad=mina$quad(raw);
            for(int i=0;i<4;i++) quad.lightmap(i,mina$light[i]);
            mina$restore=false;
        }
    }

    @Unique
    private static MutableQuadView mina$quad(Object raw) {
        if (raw instanceof MutableQuadView quad) return quad;
        try {
            return (MutableQuadView) mina$wrapper.invoke(raw);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access Sodium terrain light data", failure);
        }
    }

    @Unique private static final java.lang.reflect.Method mina$wrapper = mina$wrapperMethod();

    @Unique
    private static java.lang.reflect.Method mina$wrapperMethod() {
        try {
            return Class.forName("net.caffeinemc.mods.sodium.client.render.frapi.wrapper.ExtendedMutableQuadViewImpl")
                .getMethod("getWrapper");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Sodium terrain wrapper", failure);
        }
    }
}
