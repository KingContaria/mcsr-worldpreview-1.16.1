package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @ModifyExpressionValue(
            method = {
                    "reload(Lnet/minecraft/resource/ResourceManager;)V",
                    "reloadTransparencyShader"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;isFabulousGraphicsOrBetter()Z"
            )
    )
    private boolean doNotAllowFabulousGraphicsOnPreview(boolean isFabulousGraphicsOrBetter) {
        return isFabulousGraphicsOrBetter && (Object) this != WorldPreview.worldRenderer;
    }
}
