package me.voidxwalker.worldpreview.mixin.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.WorldPreviewProperties;
import me.voidxwalker.worldpreview.interfaces.WPChunkHolder;
import me.voidxwalker.worldpreview.interfaces.WPServerChunkLoadingManager;
import me.voidxwalker.worldpreview.mixin.access.EntityTrackerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin implements WPServerChunkLoadingManager {
    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> chunkHolders;

    @Shadow
    @Final
    private Int2ObjectMap<EntityTrackerAccessor> entityTrackers;

    @Unique
    private final LongSet sentChunks = new LongOpenHashSet();
    @Unique
    private final LongSet sentEmptyChunks = new LongOpenHashSet();
    @Unique
    private final LongSet culledChunks = new LongOpenHashSet();
    @Unique
    private final IntSet sentEntities = new IntOpenHashSet();
    @Unique
    private final IntSet culledEntities = new IntOpenHashSet();

    @Unique
    private Frustum frustum;
    @Unique
    private Vec3d cameraPos;
    @Unique
    private float pitch;
    @Unique
    private float yaw;
    @Unique
    private double fov;
    @Unique
    private double aspectRatio;

    @Inject(
            method = "method_61257",
            at = @At("RETURN")
    )
    private void getChunks(CallbackInfoReturnable<WorldChunk> cir) {
        // it's possible to optimize this by only sending the data for the new chunk
        // however that needs more careful thought and since this now only gets called 529 times
        // per world it isn't hugely impactful
        // stuff to consider:
        // - check all chunks on frustum update / initial sendData
        // - entities spawning in neighbouring chunks
        this.worldpreview$sendData();
    }

    @Unique
    private void updateFrustum(ClientPlayerEntity player, Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        double fov = Math.min(client.options.getFov().getValue() * Math.min(Math.max(player.getFovMultiplier(!camera.isThirdPerson(), client.options.getFovEffectScale().getValue().floatValue()), 0.1f), 1.5f), 180.0);
        double aspectRatio = (double) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
        Vec3d cameraPos;
        float pitch;
        float yaw;
        synchronized (camera) {
            cameraPos = camera.getPos();
            pitch = camera.getPitch();
            yaw = camera.getYaw();
        }
        if (this.frustum == null || !cameraPos.equals(this.cameraPos) || this.yaw != yaw || this.pitch != pitch || this.fov != fov || this.aspectRatio != aspectRatio) {
            // see GameRenderer#renderWorld
            Matrix4f rotationMatrix = new Matrix4f();
            rotationMatrix.rotate(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
            rotationMatrix.rotate(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));

            // see GameRenderer#getBasicProjectionMatrix
            Matrix4f projectionMatrix = new Matrix4f();
            projectionMatrix.mul(new Matrix4f().setPerspective((float) (fov * (Math.PI / 180.0)), (float) aspectRatio, 0.05F, 32 * 16 * 4.0f));

            this.frustum = new Frustum(rotationMatrix, projectionMatrix);
            this.frustum.setPosition(cameraPos.getX(), cameraPos.getY(), cameraPos.getZ());
            this.cameraPos = cameraPos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.fov = fov;
            this.aspectRatio = aspectRatio;

            this.culledChunks.clear();
            this.sentEmptyChunks.clear();
            this.culledEntities.clear();
        }
    }

    @Unique
    private List<Packet<?>> processChunk(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (this.sentChunks.contains(pos.toLong()) || this.culledChunks.contains(pos.toLong())) {
            return Collections.emptyList();
        }

        if (this.shouldCullChunk(chunk)) {
            this.culledChunks.add(pos.toLong());
            return this.processCulledChunk(chunk, pos);
        }

        ChunkHolder holder = this.chunkHolders.get(pos.toLong());

        List<Packet<?>> chunkPackets = new ArrayList<>();

        chunkPackets.add(new ChunkDataS2CPacket(chunk, chunk.getWorld().getLightingProvider(), null, null));
        ((WPChunkHolder) holder).worldpreview$flushUpdates();
        chunkPackets.addAll(this.processNeighborChunks(pos));

        this.sentChunks.add(pos.toLong());

        return chunkPackets;
    }

    @Unique
    private List<Packet<?>> processCulledChunk(WorldChunk chunk, ChunkPos pos) {
        if (this.sentEmptyChunks.contains(pos.toLong())) {
            return Collections.emptyList();
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                if (this.sentChunks.contains(ChunkPos.toLong(pos.x + x, pos.z + z))) {
                    this.sentEmptyChunks.add(pos.toLong());
                    return Collections.singletonList(this.createEdgeChunkPacket(chunk, this.chunkHolders.get(pos.toLong())));
                }
            }
        }
        return Collections.emptyList();
    }

    @Unique
    private List<Packet<?>> processNeighborChunks(ChunkPos pos) {
        List<Packet<?>> packets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                long neighbor = ChunkPos.toLong(pos.x + x, pos.z + z);
                ChunkHolder neighborHolder = this.chunkHolders.get(neighbor);
                if (neighborHolder == null) {
                    continue;
                }
                WorldChunk neighborChunk = this.getWorldChunk(neighborHolder);
                if (neighborChunk == null) {
                    continue;
                }

                if (this.sentChunks.contains(neighbor)) {
                    BitSet skyLight = ((WPChunkHolder) neighborHolder).worldpreview$getSkyLightUpdateBits();
                    BitSet blockLight = ((WPChunkHolder) neighborHolder).worldpreview$getBlockLightUpdateBits();
                    if (!skyLight.isEmpty() || !blockLight.isEmpty()) {
                        packets.add(new LightUpdateS2CPacket(new ChunkPos(neighbor), neighborChunk.getWorld().getLightingProvider(), skyLight, blockLight));
                        ((WPChunkHolder) neighborHolder).worldpreview$flushUpdates();
                    }
                } else if (this.culledChunks.contains(neighbor) && !this.sentEmptyChunks.contains(neighbor)) {
                    packets.add(this.createEdgeChunkPacket(neighborChunk, neighborHolder));
                    this.sentEmptyChunks.add(neighbor);
                }
            }
        }
        return packets;
    }

    @Unique
    private void sendData(Queue<Packet<?>> packetQueue, ClientPlayerEntity player, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (pos.getChebyshevDistance(new ChunkPos(player.getBlockPos())) > WorldPreview.config.chunkDistance) {
            return;
        }

        List<Packet<?>> chunkPackets = this.processChunk(chunk);

        List<Packet<?>> entityPackets = new ArrayList<>();

        for (EntityTrackerAccessor tracker : this.entityTrackers.values()) {
            if (pos.equals(tracker.worldpreview$getEntity().getChunkPos())) {
                entityPackets.addAll(this.processEntity(tracker));
            }
        }

        if (!entityPackets.isEmpty() && chunkPackets.isEmpty()) {
            if (!this.sentChunks.contains(pos.toLong()) && this.sentEmptyChunks.add(pos.toLong())) {
                chunkPackets = Collections.singletonList(this.createEdgeChunkPacket(chunk, this.chunkHolders.get(pos.toLong())));
            }
        }

        packetQueue.addAll(chunkPackets);
        packetQueue.addAll(entityPackets);
    }

    @Unique
    private boolean shouldCullChunk(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return chunk.isEmpty() || !this.frustum.isVisible(new Box(pos.getStartX(), chunk.getBottomY(), pos.getStartZ(), pos.getStartX() + 16, ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(chunk.getHighestNonEmptySection())) + 16, pos.getStartZ() + 16));
    }

    @Unique
    private List<Packet<?>> processEntity(EntityTrackerAccessor tracker) {
        Entity entity = tracker.worldpreview$getEntity();
        int id = entity.getId();
        if (this.sentEntities.contains(id) || this.culledEntities.contains(id)) {
            return Collections.emptyList();
        }

        if (this.shouldCullEntity(entity)) {
            this.culledEntities.add(id);
            return Collections.emptyList();
        }

        List<Packet<?>> entityPackets = new ArrayList<>();

        // ensure vehicles are processed before their passengers
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            if (!entity.getChunkPos().equals(vehicle.getChunkPos())) {
                WorldPreview.LOGGER.warn("Failed to send entity to preview! Entity and its vehicle are in different chunks.");
                return Collections.emptyList();
            }
            entityPackets.addAll(this.processEntity(this.entityTrackers.get(vehicle.getId())));
        }

        tracker.worldpreview$getEntry().sendPackets(null, entityPackets::add);
        // see EntityTrackerEntry#tick
        entityPackets.add(new EntityS2CPacket.Rotate(id, (byte) MathHelper.floor(entity.getYaw() * 256.0f / 360.0f), (byte) MathHelper.floor(entity.getPitch() * 256.0f / 360.0f), entity.isOnGround()));
        entityPackets.add(new EntitySetHeadYawS2CPacket(entity, (byte) MathHelper.floor(entity.getHeadYaw() * 256.0f / 360.0f)));

        this.sentEntities.add(id);
        return entityPackets;
    }

    @Unique
    private boolean shouldCullEntity(Entity entity) {
        return !entity.hasVehicle() && !entity.hasPassengers() && !MinecraftClient.getInstance().getEntityRenderDispatcher().shouldRender(entity, this.frustum, this.cameraPos.getX(), this.cameraPos.getY(), this.cameraPos.getZ());
    }

    @Unique
    private WorldChunk getWorldChunk(ChunkHolder holder) {
        OptionalChunk<WorldChunk> optional = holder.getAccessibleFuture().getNow(null);
        if (optional == null) {
            return null;
        }
        return optional.orElse(null);
    }

    @Unique
    private ChunkDataS2CPacket createEdgeChunkPacket(WorldChunk chunk, ChunkHolder holder) {
        // This is used to send biome and light data for culled chunks,
        // ideally we'd only send that data, but it's annoying to do since 1.18
        ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, chunk.getWorld().getLightingProvider(), null, null);
        ((WPChunkHolder) holder).worldpreview$flushUpdates();
        return packet;
    }

    @Override
    public void worldpreview$sendData() {
        if (this.world.getServer().getTicks() > 0) {
            return;
        }

        WorldPreviewProperties properties = WorldPreview.properties;
        if (properties == null) {
            return;
        }

        if (!this.world.getRegistryKey().equals(properties.world.getRegistryKey())) {
            return;
        }

        this.updateFrustum(properties.player, properties.camera);

        for (ChunkHolder holder : this.chunkHolders.values()) {
            OptionalChunk<WorldChunk> optional = holder.getAccessibleFuture().getNow(null);
            if (optional == null) {
                continue;
            }
            WorldChunk worldChunk = optional.orElse(null);
            if (worldChunk == null) {
                continue;
            }
            this.sendData(properties.packetQueue, properties.player, worldChunk);
        }
    }
}
