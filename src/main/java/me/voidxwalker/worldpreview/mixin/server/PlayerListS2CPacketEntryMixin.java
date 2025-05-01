package me.voidxwalker.worldpreview.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.voidxwalker.worldpreview.WPFakeServerPlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerListS2CPacket.Entry.class)
public abstract class PlayerListS2CPacketEntryMixin {

    @WrapOperation(
            method = "<init>(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;getLatency()I"
            )
    )
    private static int setFakePlayerLatency(ServerPlayNetworkHandler networkHandler, Operation<Integer> original, ServerPlayerEntity player) {
        if (player instanceof WPFakeServerPlayerEntity) {
            return 0;
        }
        return original.call(networkHandler);
    }
}
