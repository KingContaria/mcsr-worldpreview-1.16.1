package me.voidxwalker.worldpreview.mixin.access;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerEntity.class)
public interface PlayerEntityAccessor {
    @Accessor("PLAYER_MODEL_PARTS")
    static TrackedData<Byte> worldpreview$getPLAYER_MODEL_PARTS() {
        throw new UnsupportedOperationException();
    }

    @Invoker("updateSize")
    void worldpreview$updateSize();
}
