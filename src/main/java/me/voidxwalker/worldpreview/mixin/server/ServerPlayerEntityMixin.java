package me.voidxwalker.worldpreview.mixin.server;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.voidxwalker.worldpreview.WPFakeServerPlayerEntity;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.ServerStatHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Unique
    private static final ThreadLocal<Integer> PREVIEW_SPAWNPOS = new ThreadLocal<>();

    @ModifyExpressionValue(
            method = "method_14245",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Random;nextInt(I)I"
            )
    )
    private int setPreviewSpawnPos(int original) {
        if (this.isWorldPreviewFakePlayer()) {
            PREVIEW_SPAWNPOS.set(original);
            return original;
        }
        Integer spawnPos = PREVIEW_SPAWNPOS.get();
        if (spawnPos != null) {
            PREVIEW_SPAWNPOS.remove();
            return spawnPos;
        }
        return original;
    }

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/PlayerManager;createStatHandler(Lnet/minecraft/entity/player/PlayerEntity;)Lnet/minecraft/stat/ServerStatHandler;"
            )
    )
    private ServerStatHandler doNotCreateStatHandler(PlayerManager playerManager, PlayerEntity player, Operation<ServerStatHandler> original) {
        if (this.isWorldPreviewFakePlayer()) {
            return null;
        }
        return original.call(playerManager, player);
    }

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/PlayerManager;getAdvancementTracker(Lnet/minecraft/server/network/ServerPlayerEntity;)Lnet/minecraft/advancement/PlayerAdvancementTracker;"
            )
    )
    private PlayerAdvancementTracker doNotCreateAdvancementsTracker(PlayerManager playerManager, ServerPlayerEntity player, Operation<PlayerAdvancementTracker> original) {
        if (this.isWorldPreviewFakePlayer()) {
            return null;
        }
        return original.call(playerManager, player);
    }

    @Unique
    private boolean isWorldPreviewFakePlayer() {
        return (Object) this instanceof WPFakeServerPlayerEntity;
    }
}