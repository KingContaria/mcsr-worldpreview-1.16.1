package me.voidxwalker.worldpreview.mixin.server;

import com.llamalad7.mixinextras.sugar.Local;
import me.voidxwalker.worldpreview.interfaces.WPChunkHolder;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.LightType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.BitSet;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin implements WPChunkHolder {
    @Unique
    private final BitSet worldPreviewSkyLightUpdateBits = new BitSet();
    @Unique
    private final BitSet worldPreviewBlockLightUpdateBits = new BitSet();

    @ModifyArg(
            method = "markForLightUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/BitSet;set(I)V"
            )
    )
    private int captureLightUpdates(int index, @Local(argsOnly = true) LightType type) {
        if (type == LightType.SKY) {
            this.worldPreviewSkyLightUpdateBits.set(index);
        } else {
            this.worldPreviewBlockLightUpdateBits.set(index);
        }
        return index;
    }

    @Override
    public BitSet worldpreview$getSkyLightUpdateBits() {
        return this.worldPreviewSkyLightUpdateBits;
    }

    @Override
    public BitSet worldpreview$getBlockLightUpdateBits() {
        return this.worldPreviewBlockLightUpdateBits;
    }

    @Override
    public void worldpreview$flushUpdates() {
        this.worldPreviewSkyLightUpdateBits.clear();
        this.worldPreviewBlockLightUpdateBits.clear();
    }
}
