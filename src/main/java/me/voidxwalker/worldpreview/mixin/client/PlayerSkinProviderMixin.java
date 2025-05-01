package me.voidxwalker.worldpreview.mixin.client;

import me.voidxwalker.worldpreview.WorldPreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

@Mixin(targets = "net/minecraft/client/texture/PlayerSkinProvider$1")
public abstract class PlayerSkinProviderMixin {

    @ModifyArg(
            method = "load(Lnet/minecraft/client/texture/PlayerSkinProvider$Key;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            ),
            index = 1
    )
    private Executor immediatelyGetSkinInPreview(Executor executor) {
        if (WorldPreview.renderingPreview) {
            return Runnable::run;
        }
        return executor;
    }
}
