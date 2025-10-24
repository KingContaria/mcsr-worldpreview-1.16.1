package me.voidxwalker.worldpreview.mixin.compat.fabric_networking_api_v1;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin extends ClientCommonNetworkHandler {

    protected ClientPlayNetworkHandlerMixin(MinecraftClient client, ClientConnection connection, ClientConnectionState connectionState) {
        super(client, connection, connectionState);
    }

    @Dynamic
    @TargetHandler(
            mixin = "net.fabricmc.fabric.mixin.networking.client.ClientPlayNetworkHandlerMixin",
            name = "initAddon"
    )
    @WrapMethod(method = "@MixinSquared:Handler")
    private void cancelOnFakePlayer(CallbackInfo ci, Operation<Void> operation) {
        if (this.connection != null) {
            operation.call(ci);
        }
    }
}
