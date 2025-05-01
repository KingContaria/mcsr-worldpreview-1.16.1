package me.voidxwalker.worldpreview.mixin.access;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {
    @Invoker("getCurrentChunkHolder")
    ChunkHolder worldpreview$getCurrentChunkHolder(long pos);
}
