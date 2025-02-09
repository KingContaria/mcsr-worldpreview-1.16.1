package me.voidxwalker.worldpreview.mixin.compat.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderContainer;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.voidxwalker.worldpreview.WorldPreview;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkRenderContainer.class, remap = false)
public abstract class ChunkRenderContainerMixin {
    @Shadow
    @Final
    private int chunkX;

    @Shadow
    @Final
    private int chunkZ;

    @Shadow
    private ChunkRenderData data;

    @Inject(
            method = "canRebuild",
            at = @At("HEAD"),
            cancellable = true
    )
    private void doNotWaitForNeighbourChunksOnWall(CallbackInfoReturnable<Boolean> cir) {
        if (this.data == ChunkRenderData.ABSENT && WorldPreview.renderingPreview && Math.max(Math.abs(this.chunkX - WorldPreview.properties.player.chunkX), Math.abs(this.chunkZ - WorldPreview.properties.player.chunkZ)) < WorldPreview.config.instantRenderDistance) {
            cir.setReturnValue(true);
        }
    }
}
