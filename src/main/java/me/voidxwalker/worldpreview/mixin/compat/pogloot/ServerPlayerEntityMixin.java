package me.voidxwalker.worldpreview.mixin.compat.pogloot;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.voidxwalker.worldpreview.WPFakeServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @TargetHandler(mixin = "me.voidxwalker.pogloot.mixin.entity.ServerPlayerEntityMixin", name = "init")
    @WrapMethod(method = "@MixinSquared:Handler")
    private void cancelOnFakePlayer(CallbackInfo ci, Operation<Void> operation) {
        if ((Object) this instanceof WPFakeServerPlayerEntity) {
            return;
        }
        operation.call(ci);
    }
}
