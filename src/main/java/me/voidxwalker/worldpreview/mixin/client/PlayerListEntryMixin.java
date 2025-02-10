package me.voidxwalker.worldpreview.mixin.client;

import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {

    @Shadow
    protected abstract void loadTextures();

    @Inject(
            method = "<init>*",
            at = @At("TAIL")
    )
    private void modifyModel(CallbackInfo ci) {
        if (WorldPreview.renderingPreview) {
            this.loadTextures();
        }
    }
}
