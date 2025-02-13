package me.voidxwalker.worldpreview.mixin.server;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.WorldPreviewProperties;
import me.voidxwalker.worldpreview.interfaces.WPChunkHolder;
import me.voidxwalker.worldpreview.interfaces.WPThreadedAnvilChunkStorage;
import me.voidxwalker.worldpreview.mixin.access.EntityTrackerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin implements WPThreadedAnvilChunkStorage {
    @Shadow
    @Final
    private ServerWorld world;

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

    @ModifyReturnValue(
            method = "method_17227",
            at = @At("RETURN")
    )
    private Chunk getChunks(Chunk chunk) {
        // it's possible to optimize this by only sending the data for the new chunk
        // however that needs more careful thought and since this now only gets called 529 times
        // per world it isn't hugely impactful
        // stuff to consider:
        // - check all chunks on frustum update / initial sendData
        // - entities spawning in neighbouring chunks
        this.worldpreview$sendData();
        return chunk;
    }

    @Unique
    private void updateFrustum(ClientPlayerEntity player, Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        double fov = Math.min(client.options.fov * Math.min(Math.max(player.getSpeed(), 0.1f), 1.5f), 180.0);
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
            rotationMatrix.loadIdentity();
            rotationMatrix.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(pitch));
            rotationMatrix.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(yaw + 180.0f));

            // see GameRenderer#getBasicProjectionMatrix
            Matrix4f projectionMatrix = new Matrix4f();
            projectionMatrix.loadIdentity();
            projectionMatrix.multiply(Matrix4f.viewboxMatrix(fov, (float) aspectRatio, 0.05f, 32 * 16 * 4.0f));

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

        chunkPackets.add(new ChunkDataS2CPacket(chunk, 65535));
        ((WPChunkHolder) holder).worldpreview$flushUpdates();
        chunkPackets.add(new LightUpdateS2CPacket(chunk.getPos(), chunk.getLightingProvider(), true));
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
                    return Collections.singletonList(this.createEmptyChunkPacket(chunk));
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
                    int[] lightUpdates = ((WPChunkHolder) neighborHolder).worldpreview$flushUpdates();
                    if (lightUpdates[0] != 0 || lightUpdates[1] != 0) {
                        packets.add(new LightUpdateS2CPacket(new ChunkPos(neighbor), neighborChunk.getLightingProvider(), lightUpdates[0], lightUpdates[1], false));
                    }
                } else if (this.culledChunks.contains(neighbor) && !this.sentEmptyChunks.contains(neighbor)) {
                    packets.add(this.createEmptyChunkPacket(neighborChunk));
                    this.sentEmptyChunks.add(neighbor);
                }
            }
        }
        return packets;
    }

    @Unique
    private void sendData(Queue<Packet<?>> packetQueue, ClientPlayerEntity player, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (pos.method_24022(new ChunkPos(player.getBlockPos())) > WorldPreview.config.chunkDistance) {
            return;
        }

        List<Packet<?>> chunkPackets = this.processChunk(chunk);

        List<Packet<?>> entityPackets = new ArrayList<>();
        for (TypeFilterableList<Entity> entities : chunk.getEntitySectionArray()) {
            for (Entity entity : entities) {
                entityPackets.addAll(this.processEntity(entity));
            }
        }

        if (!entityPackets.isEmpty() && chunkPackets.isEmpty()) {
            if (!this.sentChunks.contains(pos.toLong()) && this.sentEmptyChunks.add(pos.toLong())) {
                chunkPackets = Collections.singletonList(this.createEmptyChunkPacket(chunk));
            }
        }

        packetQueue.addAll(chunkPackets);
        packetQueue.addAll(entityPackets);
    }

    @Unique
    private boolean shouldCullChunk(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return chunk.isEmpty() || !this.frustum.isVisible(new Box(pos.getStartX(), 0, pos.getStartZ(), pos.getStartX() + 16, chunk.getHighestNonEmptySectionYOffset() + 16, pos.getStartZ() + 16));
    }

    @Unique
    private List<Packet<?>> processEntity(Entity entity) {
        int id = entity.getEntityId();
        if (this.sentEntities.contains(id) || this.culledEntities.contains(id)) {
            return Collections.emptyList();
        }

        if (this.shouldCullEntity(entity)) {
            this.culledEntities.add(entity.getEntityId());
            return Collections.emptyList();
        }

        List<Packet<?>> entityPackets = new ArrayList<>();

        // ensure vehicles are processed before their passengers
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            if (entity.chunkX != vehicle.chunkX || entity.chunkZ != vehicle.chunkZ) {
                WorldPreview.LOGGER.warn("Failed to send entity to preview! Entity and its vehicle are in different chunks.");
                return Collections.emptyList();
            }
            entityPackets.addAll(this.processEntity(vehicle));
        }

        this.entityTrackers.get(id).getEntry().sendPackets(entityPackets::add);
        // see EntityTrackerEntry#tick
        entityPackets.add(new EntityS2CPacket.Rotate(id, (byte) MathHelper.floor(entity.yaw * 256.0f / 360.0f), (byte) MathHelper.floor(entity.pitch * 256.0f / 360.0f), entity.isOnGround()));
        entityPackets.add(new EntitySetHeadYawS2CPacket(entity, (byte) MathHelper.floor(entity.getHeadYaw() * 256.0f / 360.0f)));

        this.sentEntities.add(id);
        return entityPackets;
    }

    @Unique
    private boolean shouldCullEntity(Entity entity) {
        // Do not try to cull entities that are vehicles or passengers, supporting that would cause unnecessary complexity
        return !entity.hasVehicle() && !entity.hasPassengers() && !entity.ignoreCameraFrustum && !this.frustum.isVisible(entity.getVisibilityBoundingBox());
    }

    @Unique
    private WorldChunk getWorldChunk(ChunkHolder holder) {
        Either<Chunk, ChunkHolder.Unloaded> either = holder.getFutureFor(ChunkStatus.FULL).getNow(null);
        if (either == null) {
            return null;
        }
        Chunk chunk = either.left().orElse(null);
        if (chunk instanceof WorldChunk) {
            return (WorldChunk) chunk;
        }
        return null;
    }

    @Unique
    private ChunkDataS2CPacket createEmptyChunkPacket(WorldChunk chunk) {
        return new ChunkDataS2CPacket(new WorldChunk(chunk.getWorld(), chunk.getPos(), chunk.getBiomeArray()), 65535);
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
            Either<Chunk, ChunkHolder.Unloaded> either = holder.getFutureFor(ChunkStatus.FULL).getNow(null);
            if (either == null) {
                continue;
            }
            WorldChunk worldChunk = (WorldChunk) either.left().orElse(null);
            if (worldChunk == null) {
                continue;
            }
            this.sendData(properties.packetQueue, properties.player, worldChunk);
        }
    }
}
