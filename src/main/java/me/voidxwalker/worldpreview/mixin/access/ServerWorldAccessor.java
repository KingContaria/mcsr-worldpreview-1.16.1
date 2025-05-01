package me.voidxwalker.worldpreview.mixin.access;

import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {
    @Accessor("spawnChunkRadius")
    int worldpreview$getSpawnChunkRadius();
}
