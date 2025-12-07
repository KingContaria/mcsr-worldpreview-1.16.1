package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.WorldPreviewProperties;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkRenderer.class)
public abstract class ChunkRendererMixin {
    @Shadow
    @Final
    private WorldRenderer renderer;

    @ModifyExpressionValue(
            method = "getSquaredCameraDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;getCamera()Lnet/minecraft/client/render/Camera;"
            )
    )
    private Camera useWorldPreviewCamera(Camera camera) {
        // this is called off-thread when building chunks
        if (this.renderer == WorldPreview.worldRenderer) {
            WorldPreviewProperties properties = WorldPreview.properties;
            if (properties != null) {
                return properties.camera;
            }
        }
        return camera;
    }
}
