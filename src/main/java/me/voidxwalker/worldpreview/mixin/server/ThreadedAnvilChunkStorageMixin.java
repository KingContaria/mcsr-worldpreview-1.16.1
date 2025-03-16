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
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.TypeFilterableList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
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
    private final IntSet sentEntities = new IntOpenHashSet();

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
    private List<Packet<?>> processChunk(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (this.sentChunks.contains(pos.toLong())) {
            return Collections.emptyList();
        }

        ChunkHolder holder = this.chunkHolders.get(pos.toLong());

        List<Packet<?>> chunkPackets = new ArrayList<>();

        chunkPackets.add(new ChunkDataS2CPacket(chunk, 65535));
        ((WPChunkHolder) holder).worldpreview$flushUpdates();
        chunkPackets.add(new LightUpdateS2CPacket(chunk.getPos(), chunk.getLightingProvider()));
        chunkPackets.addAll(this.processNeighborChunks(pos));

        this.sentChunks.add(pos.toLong());

        return chunkPackets;
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
                        packets.add(new LightUpdateS2CPacket(new ChunkPos(neighbor), neighborChunk.getLightingProvider(), lightUpdates[0], lightUpdates[1]));
                    }
                }
            }
        }
        return packets;
    }

    @Unique
    private void sendData(Queue<Packet<?>> packetQueue, ClientPlayerEntity player, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        ChunkPos playerPos = new ChunkPos(player.getBlockPos());
        if (Math.max(Math.abs(pos.x - playerPos.x), Math.abs(pos.z - playerPos.z)) > WorldPreview.config.chunkDistance) {
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
    private List<Packet<?>> processEntity(Entity entity) {
        int id = entity.getEntityId();
        if (this.sentEntities.contains(id)) {
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
        entityPackets.add(new EntityS2CPacket.Rotate(id, (byte) MathHelper.floor(entity.yaw * 256.0f / 360.0f), (byte) MathHelper.floor(entity.pitch * 256.0f / 360.0f), entity.onGround));
        entityPackets.add(new EntitySetHeadYawS2CPacket(entity, (byte) MathHelper.floor(entity.getHeadYaw() * 256.0f / 360.0f)));

        this.sentEntities.add(id);
        return entityPackets;
    }

    @Unique
    private WorldChunk getWorldChunk(ChunkHolder holder) {
        Either<Chunk, ChunkHolder.Unloaded> either = holder.getFuture(ChunkStatus.FULL).getNow(null);
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

        if (!this.world.getDimension().getType().equals(properties.world.getDimension().getType())) {
            return;
        }

        for (ChunkHolder holder : this.chunkHolders.values()) {
            Either<Chunk, ChunkHolder.Unloaded> either = holder.getFuture(ChunkStatus.FULL).getNow(null);
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
