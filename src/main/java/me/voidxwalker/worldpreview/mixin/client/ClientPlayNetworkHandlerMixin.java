package me.voidxwalker.worldpreview.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @WrapWithCondition(
            method = "onEntitySpawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;playSpawnSound(Lnet/minecraft/entity/Entity;)V"
            )
    )
    private boolean suppressSoundsOnPreview(ClientPlayNetworkHandler handler, Entity entity) {
        return !WorldPreview.renderingPreview;
    }
}
