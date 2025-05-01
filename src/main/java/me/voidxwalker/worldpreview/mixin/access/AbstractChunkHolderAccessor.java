package me.voidxwalker.worldpreview.mixin.access;

import net.minecraft.server.world.OptionalChunk;
import net.minecraft.world.chunk.AbstractChunkHolder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

@Mixin(AbstractChunkHolder.class)
public interface AbstractChunkHolderAccessor {
    @Invoker("getOrCreateFuture")
    CompletableFuture<OptionalChunk<Chunk>> worldpreview$etOrCreateFuture(ChunkStatus status);
}
