package me.voidxwalker.worldpreview.mixin.parity;

import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.WorldPreviewMissingChunkException;
import me.voidxwalker.worldpreview.mixin.access.AbstractChunkHolderAccessor;
import me.voidxwalker.worldpreview.mixin.access.ServerChunkLoadingManagerAccessor;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin {

    @Shadow @Final public ServerChunkLoadingManager chunkLoadingManager;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void noGeneratingChunksDuringWorldPreviewConfigure(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<Chunk> cir) {
        if (!Boolean.TRUE.equals(WorldPreview.CALCULATING_SPAWN.get())) {
            return;
        }
        Chunk chunk = this.getChunkNow(x, z, leastStatus);
        if (create && chunk == null) {
            throw WorldPreviewMissingChunkException.INSTANCE;
        }
        cir.setReturnValue(chunk);
    }

    @Unique
    private @Nullable Chunk getChunkNow(int x, int z, ChunkStatus leastStatus) {
        ChunkHolder holder = ((ServerChunkLoadingManagerAccessor) this.chunkLoadingManager).worldpreview$getCurrentChunkHolder(ChunkPos.toLong(x, z));
        if (holder == null) {
            return null;
        }
        OptionalChunk<Chunk> optional = ((AbstractChunkHolderAccessor) holder).worldpreview$etOrCreateFuture(leastStatus).getNow(null);
        if (optional == null) {
            return null;
        }
        return optional.orElse(null);
    }
}
