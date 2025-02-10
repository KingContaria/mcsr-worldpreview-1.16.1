package me.voidxwalker.worldpreview.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerMixin {

    @ModifyExpressionValue(
            method = "schedulePendingUpdates",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/ChunkAdjacencyMap;hasNeighbors(II)Z"
            ),
            remap = false
    )
    private boolean doNotWaitForNeighbourChunksOnPreview(boolean hasNeighbors, RenderSection section) {
        if (!section.isBuilt() && WorldPreview.renderingPreview && Math.max(Math.abs(section.getChunkX() - ChunkSectionPos.getSectionCoord(WorldPreview.properties.player.getX())), Math.abs(section.getChunkZ() - ChunkSectionPos.getSectionCoord(WorldPreview.properties.player.getZ()))) < WorldPreview.config.instantRenderDistance) {
            return true;
        }
        return hasNeighbors;
    }
}
