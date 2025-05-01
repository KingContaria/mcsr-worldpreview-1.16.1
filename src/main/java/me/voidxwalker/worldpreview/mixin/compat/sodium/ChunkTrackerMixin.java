package me.voidxwalker.worldpreview.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.voidxwalker.worldpreview.WorldPreview;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ChunkTracker.class, remap = false)
public abstract class ChunkTrackerMixin {

    @ModifyExpressionValue(
            method = "updateMerged",
            at = @At(
                    value = "CONSTANT",
                    args = "intValue=-1",
                    ordinal = 0
            )
    )
    private int doNotWaitForNeighbourChunksOnPreview(int minusOne, int x, int z) {
        if (WorldPreview.renderingPreview && Math.max(Math.abs(x - ChunkSectionPos.getSectionCoord(WorldPreview.properties.player.getX())), Math.abs(z - ChunkSectionPos.getSectionCoord(WorldPreview.properties.player.getZ()))) < WorldPreview.config.instantRenderDistance) {
            // skip checking neighbour status
            return 2;
        }
        return minusOne;
    }
}
