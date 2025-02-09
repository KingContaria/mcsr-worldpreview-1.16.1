package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
            method = "onResized",
            at = @At("TAIL")
    )
    private void resizeWorldRenderer(int i, int j, CallbackInfo ci) {
        WorldPreview.worldRenderer.onResized(i, j);
    }

    @ModifyExpressionValue(
            method = "getFov",
            at = {
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/render/GameRenderer;lastMovementFovMultiplier:F"
                    ),
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/render/GameRenderer;movementFovMultiplier:F"
                    )
            },
            require = 2
    )
    private float modifyMovementFovMultiplier(float movementFovMultiplier) {
        if (WorldPreview.renderingPreview) {
            return Math.min(Math.max(WorldPreview.properties.player.getSpeed(), 0.1f), 1.5f);
        }
        return movementFovMultiplier;
    }
}
