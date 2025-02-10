package me.voidxwalker.worldpreview.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.texture.PlayerSkinProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ExecutorService;

@Mixin(PlayerSkinProvider.class)
public abstract class PlayerSkinProviderMixin {

    @WrapOperation(
            method = "loadSkin(Lcom/mojang/authlib/GameProfile;Lnet/minecraft/client/texture/PlayerSkinProvider$SkinTextureAvailableCallback;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/ExecutorService;execute(Ljava/lang/Runnable;)V"
            )
    )
    private void immediatelyGetSkinInPreview(ExecutorService serverWorkerExecutor, Runnable runnable, Operation<Void> original) {
        if (WorldPreview.renderingPreview) {
            runnable.run();
        } else {
            original.call(serverWorkerExecutor, runnable);
        }
    }
}
