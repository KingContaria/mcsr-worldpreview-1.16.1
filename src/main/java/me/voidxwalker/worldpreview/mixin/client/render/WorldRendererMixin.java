package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.Set;

/*
    I don't understand these mixins.
    This is taken from old WorldPreview code.
    It works.
    Don't ask me why or how.
    It's just for vanilla anyway, Sodium works.

    ~ contaria
 */
@Mixin(value = WorldRenderer.class, priority = 1500)
public abstract class WorldRendererMixin {

    @WrapWithCondition(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;cancelRebuild()V"
            ),
            require = 0
    )
    private boolean fixWorldPreviewChunkRebuilding(ChunkBuilder.BuiltChunk builtChunk) {
        return !WorldPreview.renderingPreview;
    }

    @WrapWithCondition(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;addAll(Ljava/util/Collection;)Z",
                    remap = false
            ),
            require = 0
    )
    private boolean fixWorldPreviewChunkRebuilding(Set<ChunkBuilder.BuiltChunk> set, Collection<ChunkBuilder.BuiltChunk> collection) {
        return !WorldPreview.renderingPreview;
    }

    @ModifyExpressionValue(
            method = "reload*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;isFabulousGraphicsOrBetter()Z"
            )
    )
    private boolean doNotAllowFabulousGraphicsOnPreview(boolean isFabulousGraphicsOrBetter) {
        return isFabulousGraphicsOrBetter && (Object) this != WorldPreview.worldRenderer;
    }
}
