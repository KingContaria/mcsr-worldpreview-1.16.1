package me.voidxwalker.worldpreview.mixin.client.render;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.mixin.access.BuiltChunkStorageAccessor;
import me.voidxwalker.worldpreview.mixin.access.ChunkInfoAccessor;
import me.voidxwalker.worldpreview.mixin.access.RenderPhaseAccessor;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderEffect;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.options.CloudRenderMode;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Vector3d;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.*;
import net.minecraft.util.profiler.Profiler;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;

@SuppressWarnings("deprecation")
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow
    @Final
    public static Direction[] DIRECTIONS;

    @Shadow
    private ClientWorld world;

    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    private BuiltChunkStorage chunks;

    @Shadow
    private ChunkBuilder chunkBuilder;

    @Shadow
    @Final
    private ObjectList<WorldRenderer.ChunkInfo> visibleChunks;

    @Shadow
    @Final
    private VertexFormat vertexFormat;

    @Shadow
    private double lastTranslucentSortY;

    @Shadow
    private double lastTranslucentSortX;

    @Shadow
    private double lastTranslucentSortZ;

    @Shadow
    private int renderDistance;

    @Shadow
    private double lastCameraChunkUpdateX;

    @Shadow
    private double lastCameraChunkUpdateY;

    @Shadow
    private double lastCameraChunkUpdateZ;

    @Shadow
    private int cameraChunkX;

    @Shadow
    private int cameraChunkY;

    @Shadow
    private int cameraChunkZ;

    @Shadow
    private boolean needsTerrainUpdate;

    @Shadow
    private double lastCameraX;

    @Shadow
    private Set<ChunkBuilder.BuiltChunk> chunksToRebuild;

    @Shadow
    private double lastCameraPitch;

    @Shadow
    private double lastCameraY;

    @Shadow
    private double lastCameraYaw;

    @Shadow
    private double lastCameraZ;

    @Shadow
    @Final
    private Set<BlockEntity> noCullingBlockEntities;

    @Shadow
    @Final
    private BufferBuilderStorage bufferBuilders;

    @Shadow
    private @Nullable ShaderEffect transparencyShader;

    @Shadow
    private @Nullable Framebuffer translucentFramebuffer;

    @Shadow
    private @Nullable Frustum capturedFrustum;

    @Shadow
    @Final
    private Vector3d capturedFrustumPosition;

    @Shadow
    protected abstract void renderLayer(RenderLayer renderLayer, MatrixStack matrixStack, double d, double e, double f);

    @Shadow
    public abstract void reload();

    @Shadow
    @Nullable
    protected abstract ChunkBuilder.BuiltChunk getAdjacentChunk(BlockPos pos, ChunkBuilder.BuiltChunk chunk, Direction direction);

    @Shadow
    protected abstract void setupTerrain(Camera camera, Frustum frustum, boolean hasForcedFrustum, int frame, boolean spectator);

    @Shadow
    protected abstract void renderChunkDebugInfo(Camera camera);

    @Shadow
    protected abstract void renderWorldBorder(Camera camera);

    @Shadow
    protected abstract void renderWeather(LightmapTextureManager manager, float f, double d, double e, double g);

    @Shadow
    public abstract void renderClouds(MatrixStack matrices, float tickDelta, double cameraX, double cameraY, double cameraZ);

    @Shadow
    protected abstract void checkEmpty(MatrixStack matrices);

    @Redirect(method = "renderWeather", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;", opcode = Opcodes.GETFIELD))
    private ClientWorld worldpreview_getCorrectWorld(MinecraftClient instance) {
        if (client.currentScreen instanceof LevelLoadingScreen) {
            return this.world;
        }
        return instance.world;
    }

    @Redirect(method = "tickRainSplashing", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;", opcode = Opcodes.GETFIELD))
    private ClientWorld worldpreview_getCorrectWorld2(MinecraftClient instance) {
        if (client.currentScreen instanceof LevelLoadingScreen) {
            return this.world;
        }
        return instance.world;
    }

    @Redirect(method = "reload", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;getCameraEntity()Lnet/minecraft/entity/Entity;"))
    private Entity worldpreview_getCameraEntity(MinecraftClient instance) {
        if (instance.getCameraEntity() == null && client.currentScreen instanceof LevelLoadingScreen) {
            return WorldPreview.player;
        }
        return instance.getCameraEntity();
    }

    @Inject(method = "reload", at = @At(value = "TAIL"))
    private void worldpreview_reload(CallbackInfo ci) {
        if (this.world != null && client.currentScreen instanceof LevelLoadingScreen) {
            this.chunks = new BuiltChunkStorage(this.chunkBuilder, this.world, this.client.options.viewDistance, (WorldRenderer) (Object) this);
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZIZ)V"))
    private void worldpreview_setupTerrain(WorldRenderer instance, Camera camera, Frustum frustum, boolean hasForcedFrustum, int frame, boolean spectator) {
        if (!(client.currentScreen instanceof LevelLoadingScreen)) {
            this.setupTerrain(camera, frustum, hasForcedFrustum, frame, spectator);
            return;
        }
        Vec3d vec3d = camera.getPos();
        if (this.client.options.viewDistance != this.renderDistance) {
            this.reload();
        }

        this.world.getProfiler().push("camera");
        double d = WorldPreview.player.getX() - this.lastCameraChunkUpdateX;
        double e = WorldPreview.player.getY() - this.lastCameraChunkUpdateY;
        double f = WorldPreview.player.getZ() - this.lastCameraChunkUpdateZ;
        if (this.cameraChunkX != WorldPreview.player.chunkX
                || this.cameraChunkY != WorldPreview.player.chunkY
                || this.cameraChunkZ != WorldPreview.player.chunkZ
                || d * d + e * e + f * f > 16.0) {
            this.lastCameraChunkUpdateX = WorldPreview.player.getX();
            this.lastCameraChunkUpdateY = WorldPreview.player.getY();
            this.lastCameraChunkUpdateZ = WorldPreview.player.getZ();
            this.cameraChunkX = WorldPreview.player.chunkX;
            this.cameraChunkY = WorldPreview.player.chunkY;
            this.cameraChunkZ = WorldPreview.player.chunkZ;
            this.chunks.updateCameraPosition(WorldPreview.player.getX(), WorldPreview.player.getZ());
        }

        this.chunkBuilder.setCameraPosition(vec3d);
        this.world.getProfiler().swap("cull");
        this.client.getProfiler().swap("culling");
        BlockPos blockPos = camera.getBlockPos();
        ChunkBuilder.BuiltChunk builtChunk = ((BuiltChunkStorageAccessor) this.chunks).callGetRenderedChunk(blockPos);
        BlockPos blockPos2 = new BlockPos(MathHelper.floor(vec3d.x / 16.0) * 16, MathHelper.floor(vec3d.y / 16.0) * 16, MathHelper.floor(vec3d.z / 16.0) * 16);
        float g = camera.getPitch();
        float h = camera.getYaw();
        this.needsTerrainUpdate = this.needsTerrainUpdate
                || !this.chunksToRebuild.isEmpty()
                || vec3d.x != this.lastCameraX
                || vec3d.y != this.lastCameraY
                || vec3d.z != this.lastCameraZ
                || (double) g != this.lastCameraPitch
                || (double) h != this.lastCameraYaw;
        this.lastCameraX = vec3d.x;
        this.lastCameraY = vec3d.y;
        this.lastCameraZ = vec3d.z;
        this.lastCameraPitch = g;
        this.lastCameraYaw = h;
        this.client.getProfiler().swap("update");

        if (!hasForcedFrustum && this.needsTerrainUpdate) {
            this.needsTerrainUpdate = false;
            this.visibleChunks.clear();
            Queue<WorldRenderer.ChunkInfo> queue = Queues.newArrayDeque();
            Entity.setRenderDistanceMultiplier(
                    MathHelper.clamp((double) this.client.options.viewDistance / 8.0, 1.0, 2.5) * (double) this.client.options.entityDistanceScaling
            );
            boolean bl = this.client.chunkCullingEnabled;
            if (builtChunk != null) {
                if (spectator && this.world.getBlockState(blockPos).isOpaqueFullCube(this.world, blockPos)) {
                    bl = false;
                }
                builtChunk.setRebuildFrame(frame);
                queue.add(((WorldRenderer) (Object) (this)).new ChunkInfo(builtChunk, null, 0));
            } else {
                int j = blockPos.getY() > 0 ? 248 : 8;
                int k = MathHelper.floor(vec3d.x / 16.0) * 16;
                int l = MathHelper.floor(vec3d.z / 16.0) * 16;
                List<WorldRenderer.ChunkInfo> list = Lists.newArrayList();

                for (int m = -this.renderDistance; m <= this.renderDistance; ++m) {
                    for (int n = -this.renderDistance; n <= this.renderDistance; ++n) {
                        ChunkBuilder.BuiltChunk builtChunk2 = ((BuiltChunkStorageAccessor) this.chunks).callGetRenderedChunk(new BlockPos(k + (m << 4) + 8, j, l + (n << 4) + 8));
                        if (builtChunk2 != null && frustum.isVisible(builtChunk2.boundingBox)) {
                            builtChunk2.setRebuildFrame(frame);
                            list.add(((WorldRenderer) (Object) (this)).new ChunkInfo(builtChunk2, null, 0));
                        }
                    }
                }

                list.sort(Comparator.comparingDouble(chunkInfox -> blockPos.getSquaredDistance((((ChunkInfoAccessor) chunkInfox).getChunk().getOrigin().add(8, 8, 8)))));
                queue.addAll(list);
            }

            this.client.getProfiler().push("iteration");

            while (!queue.isEmpty()) {
                WorldRenderer.ChunkInfo chunkInfo = queue.poll();
                ChunkBuilder.BuiltChunk builtChunk3 = ((ChunkInfoAccessor) chunkInfo).getChunk();
                Direction direction = ((ChunkInfoAccessor) chunkInfo).getDirection();
                this.visibleChunks.add(chunkInfo);

                for (Direction direction2 : DIRECTIONS) {
                    ChunkBuilder.BuiltChunk builtChunk4 = this.getAdjacentChunk(blockPos2, builtChunk3, direction2);
                    if ((!bl || !chunkInfo.canCull(direction2.getOpposite()))
                            && (!bl || direction == null || builtChunk3.getData().isVisibleThrough(direction.getOpposite(), direction2))
                            && builtChunk4 != null
                            && builtChunk4.shouldBuild()
                            && builtChunk4.setRebuildFrame(frame)
                            && frustum.isVisible(builtChunk4.boundingBox)) {
                        WorldRenderer.ChunkInfo chunkInfo2 = ((WorldRenderer) (Object) (this)).new ChunkInfo(builtChunk4, direction2, ((ChunkInfoAccessor) chunkInfo).getPropagationLevel() + 1);
                        chunkInfo2.updateCullingState(((ChunkInfoAccessor) chunkInfo).getCullingState(), direction2);
                        queue.add(chunkInfo2);
                    }
                }
            }

            this.client.getProfiler().pop();
        }

        this.client.getProfiler().swap("rebuildNear");
        Set<ChunkBuilder.BuiltChunk> set = this.chunksToRebuild;
        this.chunksToRebuild = Sets.newLinkedHashSet();

        for (WorldRenderer.ChunkInfo chunkInfo : this.visibleChunks) {
            ChunkBuilder.BuiltChunk builtChunk3 = ((ChunkInfoAccessor) chunkInfo).getChunk();
            if (builtChunk3.needsRebuild() || set.contains(builtChunk3)) {
                this.needsTerrainUpdate = true;
                BlockPos blockPos3 = builtChunk3.getOrigin().add(8, 8, 8);
                boolean bl2 = blockPos3.getSquaredDistance(blockPos) < 768.0;
                if (!builtChunk3.needsImportantRebuild() && !bl2) {
                    this.chunksToRebuild.add(builtChunk3);
                } else {
                    this.client.getProfiler().push("build near");
                    this.chunkBuilder.rebuild(builtChunk3);
                    this.client.getProfiler().pop();
                }
            }
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/BufferBuilderStorage;getEntityVertexConsumers()Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;"), cancellable = true)
    private void worldpreview_render(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo ci) {
        if (client.currentScreen instanceof LevelLoadingScreen) {
            worldpreview_renderSafe(matrices, tickDelta, limitTime, renderBlockOutline, camera, gameRenderer, lightmapTextureManager, matrix4f);
            ci.cancel();
        }
    }

    @Unique
    private void worldpreview_renderSafe(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f) {
        Profiler profiler = this.world.getProfiler();
        Vec3d vec3d = camera.getPos();
        double d = vec3d.getX();
        double e = vec3d.getY();
        double f = vec3d.getZ();
        Matrix4f matrix4f2 = matrices.peek().getModel();
        boolean bl = this.capturedFrustum != null;
        Frustum frustum;
        if (bl) {
            frustum = this.capturedFrustum;
            frustum.setPosition(this.capturedFrustumPosition.x, this.capturedFrustumPosition.y, this.capturedFrustumPosition.z);
        } else {
            frustum = new Frustum(matrix4f2, matrix4f);
            frustum.setPosition(d, e, f);
        }

        if (this.world.getSkyProperties().isDarkened()) {
            DiffuseLighting.enableForLevel(matrices.peek().getModel());
        } else {
            DiffuseLighting.method_27869(matrices.peek().getModel());
        }

        VertexConsumerProvider.Immediate immediate = this.bufferBuilders.getEntityVertexConsumers();

        this.checkEmpty(matrices);
        immediate.draw(RenderLayer.getEntitySolid(SpriteAtlasTexture.BLOCK_ATLAS_TEX));
        immediate.draw(RenderLayer.getEntityCutout(SpriteAtlasTexture.BLOCK_ATLAS_TEX));
        immediate.draw(RenderLayer.getEntityCutoutNoCull(SpriteAtlasTexture.BLOCK_ATLAS_TEX));
        immediate.draw(RenderLayer.getEntitySmoothCutout(SpriteAtlasTexture.BLOCK_ATLAS_TEX));

        for (WorldRenderer.ChunkInfo chunkInfo : this.visibleChunks) {
            List<BlockEntity> list = ((ChunkInfoAccessor) chunkInfo).getChunk().getData().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getPos();
                    matrices.push();
                    matrices.translate((double) blockPos.getX() - d, (double) blockPos.getY() - e, (double) blockPos.getZ() - f);
                    BlockEntityRenderDispatcher.INSTANCE.render(blockEntity, tickDelta, matrices, immediate);
                    matrices.pop();
                }
            }
        }

        //noinspection SynchronizeOnNonFinalField
        synchronized (this.noCullingBlockEntities) {
            for (BlockEntity blockEntity2 : this.noCullingBlockEntities) {
                BlockPos blockPos2 = blockEntity2.getPos();
                matrices.push();
                matrices.translate((double) blockPos2.getX() - d, (double) blockPos2.getY() - e, (double) blockPos2.getZ() - f);
                BlockEntityRenderDispatcher.INSTANCE.render(blockEntity2, tickDelta, matrices, immediate);
                matrices.pop();
            }
        }

        this.checkEmpty(matrices);
        immediate.draw(RenderLayer.getSolid());
        immediate.draw(TexturedRenderLayers.getEntitySolid());
        immediate.draw(TexturedRenderLayers.getEntityCutout());
        immediate.draw(TexturedRenderLayers.getBeds());
        immediate.draw(TexturedRenderLayers.getShulkerBoxes());
        immediate.draw(TexturedRenderLayers.getSign());
        immediate.draw(TexturedRenderLayers.getChest());
        this.bufferBuilders.getOutlineVertexConsumers().draw();

        this.checkEmpty(matrices);
        profiler.pop();

        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(matrices.peek().getModel());
        RenderSystem.popMatrix();
        immediate.draw(TexturedRenderLayers.getEntityTranslucentCull());
        immediate.draw(TexturedRenderLayers.getBannerPatterns());
        immediate.draw(TexturedRenderLayers.getShieldPatterns());
        immediate.draw(RenderLayer.getArmorGlint());
        immediate.draw(RenderLayer.getArmorEntityGlint());
        immediate.draw(RenderLayer.getGlint());
        immediate.draw(RenderLayer.getEntityGlint());
        immediate.draw(RenderLayer.getWaterMask());
        this.bufferBuilders.getEffectVertexConsumers().draw();
        immediate.draw(RenderLayer.getLines());
        immediate.draw();
        if (this.transparencyShader != null) {
            assert this.translucentFramebuffer != null;
            this.translucentFramebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
            this.translucentFramebuffer.copyDepthFrom(this.client.getFramebuffer());
        }
        profiler.swap("translucent");
        this.worldpreview_renderLayerSafe(RenderLayer.getTranslucent(), matrices, d, e, f);
        profiler.swap("string");
        this.worldpreview_renderLayerSafe(RenderLayer.getTripwire(), matrices, d, e, f);

        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(matrices.peek().getModel());
        if (this.client.options.getCloudRenderMode() != CloudRenderMode.OFF) {
            if (this.transparencyShader != null) {
                RenderPhaseAccessor.getCLOUDS_TARGET().startDrawing();
                profiler.swap("clouds");
                this.renderClouds(matrices, tickDelta, d, e, f);
                RenderPhaseAccessor.getCLOUDS_TARGET().endDrawing();
            } else {
                profiler.swap("clouds");
                this.renderClouds(matrices, tickDelta, d, e, f);
            }
        }

        if (this.transparencyShader != null) {
            RenderPhaseAccessor.getWEATHER_TARGET().startDrawing();
            profiler.swap("weather");
            this.renderWeather(lightmapTextureManager, tickDelta, d, e, f);
            this.renderWorldBorder(camera);
            RenderPhaseAccessor.getWEATHER_TARGET().endDrawing();
            this.transparencyShader.render(tickDelta);
            this.client.getFramebuffer().beginWrite(false);
        } else {
            RenderSystem.depthMask(false);
            profiler.swap("weather");
            this.renderWeather(lightmapTextureManager, tickDelta, d, e, f);
            this.renderWorldBorder(camera);
            RenderSystem.depthMask(true);
        }

        this.renderChunkDebugInfo(camera);
        RenderSystem.shadeModel(7424);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.popMatrix();
        BackgroundRenderer.method_23792();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;renderLayer(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/util/math/MatrixStack;DDD)V"))
    private void worldpreview_renderLayer(WorldRenderer instance, RenderLayer renderLayer, MatrixStack matrixStack, double d, double e, double f) {
        if (!(client.currentScreen instanceof LevelLoadingScreen)) {
            this.renderLayer(renderLayer, matrixStack, d, e, f);
            return;
        }
        this.worldpreview_renderLayerSafe(renderLayer, matrixStack, d, e, f);
    }

    @Unique
    private void worldpreview_renderLayerSafe(RenderLayer renderLayer, MatrixStack matrixStack, double d, double e, double f) {
        renderLayer.startDrawing();
        if (renderLayer == RenderLayer.getTranslucent()) {
            this.client.getProfiler().push("translucent_sort");
            double g = d - this.lastTranslucentSortX;
            double h = e - this.lastTranslucentSortY;
            double i = f - this.lastTranslucentSortZ;
            if (g * g + h * h + i * i > 1.0D) {
                this.lastTranslucentSortX = d;
                this.lastTranslucentSortY = e;
                this.lastTranslucentSortZ = f;
                int j = 0;

                for (WorldRenderer.ChunkInfo chunkInfo : this.visibleChunks) {
                    if (j < 15 && ((ChunkInfoAccessor) chunkInfo).getChunk().scheduleSort(renderLayer, this.chunkBuilder)) {
                        ++j;
                    }
                }
            }

            this.client.getProfiler().pop();
        }

        this.client.getProfiler().push("filterempty");
        this.client.getProfiler().swap(() -> "render_" + renderLayer);
        boolean bl = renderLayer != RenderLayer.getTranslucent();
        ObjectListIterator<WorldRenderer.ChunkInfo> objectListIterator = this.visibleChunks.listIterator(bl ? 0 : this.visibleChunks.size());

        while (bl ? objectListIterator.hasNext() : objectListIterator.hasPrevious()) {
            WorldRenderer.ChunkInfo chunkInfo2 = bl ? objectListIterator.next() : objectListIterator.previous();
            ChunkBuilder.BuiltChunk builtChunk = ((ChunkInfoAccessor) chunkInfo2).getChunk();
            if (!builtChunk.getData().isEmpty(renderLayer)) {
                VertexBuffer vertexBuffer = builtChunk.getBuffer(renderLayer);
                matrixStack.push();
                BlockPos blockPos = builtChunk.getOrigin();
                matrixStack.translate((double) blockPos.getX() - d, (double) blockPos.getY() - e, (double) blockPos.getZ() - f);
                vertexBuffer.bind();
                this.vertexFormat.startDrawing(0L);
                vertexBuffer.draw(matrixStack.peek().getModel(), 7);
                matrixStack.pop();
            }
        }

        VertexBuffer.unbind();
        RenderSystem.clearCurrentColor();
        this.vertexFormat.endDrawing();
        this.client.getProfiler().pop();
        renderLayer.endDrawing();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getViewDistance()F"))
    private float worldpreview_getViewDistance(GameRenderer instance) {
        if (client.currentScreen instanceof LevelLoadingScreen) {
            return client.options.viewDistance * 16;
        }

        return instance.getViewDistance();
    }

    @Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;targetedEntity:Lnet/minecraft/entity/Entity;", opcode = Opcodes.GETFIELD))
    private Entity worldpreview_getCorrectTargetedPlayerEntity(MinecraftClient instance) {
        if (instance.player == null && client.currentScreen instanceof LevelLoadingScreen) {
            return WorldPreview.player;
        }
        return instance.targetedEntity;
    }

    @Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;", opcode = Opcodes.GETFIELD))
    private ClientWorld worldpreview_getCorrectWorld3(MinecraftClient instance) {
        if (instance.currentScreen instanceof LevelLoadingScreen) {
            return this.world;
        }
        return instance.world;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/SkyProperties;useThickFog(II)Z"))
    private boolean worldpreview_shouldThickenFog(SkyProperties instance, int i, int j) {
        if (client.gameRenderer == null && client.currentScreen instanceof LevelLoadingScreen) {
            return false;
        }
        return instance.useThickFog(i, j);
    }

    @Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;player:Lnet/minecraft/client/network/ClientPlayerEntity;", ordinal = 1, opcode = Opcodes.GETFIELD))
    private ClientPlayerEntity worldpreview_getCorrectPlayer2(MinecraftClient instance) {
        if (instance.player == null && client.currentScreen instanceof LevelLoadingScreen) {
            return WorldPreview.player;
        }
        return instance.player;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isSpectator()Z"))
    private boolean worldpreview_spectator(ClientPlayerEntity instance) {
        if (client.currentScreen instanceof LevelLoadingScreen && instance == null) {
            return false;
        }
        assert instance != null;
        return instance.isSpectator();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/debug/DebugRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;DDD)V"))
    private void worldpreview_stopDebugRenderer(DebugRenderer instance, MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, double cameraX, double cameraY, double cameraZ) {
        if (client.currentScreen instanceof LevelLoadingScreen) {
            return;
        }
        instance.render(matrices, vertexConsumers, cameraX, cameraY, cameraZ);
    }

    @Redirect(method = "renderSky", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;", opcode = Opcodes.GETFIELD))
    private ClientWorld worldpreview_getCorrectWorld4(MinecraftClient instance) {
        if (instance.world == null && client.currentScreen instanceof LevelLoadingScreen) {
            return this.world;
        }
        return instance.world;
    }

    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getCamera()Lnet/minecraft/client/render/Camera;"))
    private Camera worldpreview_getCamera(GameRenderer instance) {
        if (instance.getCamera() == null && client.currentScreen instanceof LevelLoadingScreen) {
            return WorldPreview.camera;
        }
        return instance.getCamera();
    }

    @Redirect(method = "renderSky", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;player:Lnet/minecraft/client/network/ClientPlayerEntity;", opcode = Opcodes.GETFIELD))
    private ClientPlayerEntity worldpreview_getCorrectPlayer3(MinecraftClient instance) {
        if (instance.player == null && client.currentScreen instanceof LevelLoadingScreen) {
            return WorldPreview.player;
        }
        return instance.player;
    }
}
