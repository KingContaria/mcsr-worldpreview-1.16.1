package me.voidxwalker.worldpreview.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import me.voidxwalker.worldpreview.WPFakeServerPlayerEntity;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
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
        if (WorldPreview.renderingPreview || entity instanceof WPFakeServerPlayerEntity) {
            return entity.getStandingEyeHeight();
        }
        return original;
    }

    // since we have to get the camera state on the server thread,
    // it has to be synchronized to ensure that it is correct
    // see ServerChunkManagerMixin#updateFrustum
    @WrapMethod(method = "update")
    public synchronized void synchronizeCameraUpdate(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, Operation<Void> original) {
        original.call(area, focusedEntity, thirdPerson, inverseView, tickDelta);
    }
}
