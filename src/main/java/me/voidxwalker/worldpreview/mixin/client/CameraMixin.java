package me.voidxwalker.worldpreview.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @ModifyExpressionValue(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/MathHelper;lerp(FFF)F"
            )
    )
    private float modifyCameraY(float original, @Local(argsOnly = true) Entity entity) {
        if (WorldPreview.renderingPreview) {
            return entity.getStandingEyeHeight();
        }
        return original;
    }
}
