package me.voidxwalker.worldpreview.mixin.server;

import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.interfaces.WPChunkHolder;
import me.voidxwalker.worldpreview.mixin.access.ThreadedAnvilChunkStorageAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin {
    @Shadow
    @Final
    private ServerWorld world;
    @Shadow
    @Final
    public ThreadedAnvilChunkStorage threadedAnvilChunkStorage;

    @Shadow
    @Nullable
    public abstract WorldChunk getWorldChunk(int chunkX, int chunkZ);

    @Unique
    private final Set<Long> sentChunks = new HashSet<>();
    @Unique
    private final Set<Long> sentEmptyChunks = new HashSet<>();
    @Unique
    private final Set<Long> culledChunks = new HashSet<>();
    @Unique
    private final Set<Integer> sentEntities = new HashSet<>();
    @Unique
    private final Set<Integer> culledEntities = new HashSet<>();

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

    @Unique
    private final Object CAMERA_LOCK = new Object();

    @Inject(
            method = "tick()Z",
            at = @At("RETURN")
    )
    private void getChunks(CallbackInfoReturnable<Boolean> cir) {
        if (this.world.getServer().getTicks() > 0) {
            return;
        }

        ClientWorld world;
        ClientPlayerEntity player;
        Camera camera;
        Queue<Packet<?>> packetQueue;
        synchronized (WorldPreview.LOCK) {
            world = WorldPreview.world;
            player = WorldPreview.player;
            camera = WorldPreview.camera;
            packetQueue = WorldPreview.packetQueue;
        }
        if (world == null || player == null || camera == null || packetQueue == null) {
            return;
        }

        if (!this.world.getRegistryKey().equals(world.getRegistryKey())) {
            return;
        }

        this.updateFrustum(player, camera);

        for (ChunkHolder holder : ((ThreadedAnvilChunkStorageAccessor) this.threadedAnvilChunkStorage).getChunkHolders().values()) {
            this.processChunk(player, packetQueue, holder);
        }

        ((ThreadedAnvilChunkStorageAccessor) this.threadedAnvilChunkStorage).getEntityTrackers().forEach((id, tracker) -> this.processEntity(packetQueue, id, (ThreadedAnvilChunkStorageAccessor.EntityTrackerAccessor) tracker));
    }

    @Unique
    private void updateFrustum(ClientPlayerEntity player, Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        double fov = Math.min(client.options.fov * Math.min(Math.max(player.getSpeed(), 0.1f), 1.5f), 180.0);
        double aspectRatio = (double) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
        Vec3d cameraPos;
        float pitch;
        float yaw;
        synchronized (CAMERA_LOCK) {
            cameraPos = camera.getPos();
            pitch = camera.getPitch();
            yaw = camera.getYaw();
        }
        if (this.frustum == null || !cameraPos.equals(this.cameraPos) || this.yaw != yaw || this.pitch != pitch || this.fov != fov || this.aspectRatio != aspectRatio) {
            // see GameRenderer#renderWorld
            Matrix4f rotationMatrix = new Matrix4f();
            rotationMatrix.loadIdentity();
            rotationMatrix.multiply(Vector3f.POSITIVE_X.getDegreesQuaternion(pitch));
            rotationMatrix.multiply(Vector3f.POSITIVE_Y.getDegreesQuaternion(yaw + 180.0f));

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
    private void processChunk(ClientPlayerEntity player, Queue<Packet<?>> packetQueue, ChunkHolder holder) {
        ChunkPos pos = holder.getPos();
        if (this.sentChunks.contains(pos.toLong()) || this.culledChunks.contains(pos.toLong())) {
            return;
        }

        // ChunkHolder#getWorldChunk only returns the chunk once it's ready to be ticked,
        // so instead we use ThreadedAnvilChunkStorage#getWorldChunk which returns the chunk once its fully generated
        WorldChunk chunk = this.getWorldChunk(pos.x, pos.z);
        if (chunk == null) {
            return;
        }

        ChunkPos centerPos = new ChunkPos(player.getBlockPos());
        if (centerPos.getChebyshevDistance(pos) > WorldPreview.config.chunkDistance) {
            return;
        }

        if (this.shouldCullChunk(chunk)) {
            this.culledChunks.add(pos.toLong());
            for (ChunkPos neighbor : this.getNeighborChunks(pos)) {
                if (this.sentChunks.contains(neighbor.toLong())) {
                    packetQueue.add(this.createEmptyChunkPacket(chunk));
                    this.sentEmptyChunks.add(pos.toLong());
                    break;
                }
            }
            return;
        }

        List<Packet<?>> chunkPackets = new ArrayList<>();

        chunkPackets.add(new ChunkDataS2CPacket(chunk, 65535, true));
        ((WPChunkHolder) holder).worldpreview$flushUpdates();
        chunkPackets.add(new LightUpdateS2CPacket(chunk.getPos(), chunk.getLightingProvider(), true));

        for (ChunkPos neighbor : this.getNeighborChunks(pos)) {
            ChunkHolder neighborHolder = this.getChunkHolder(neighbor);
            WorldChunk neighborChunk = this.getWorldChunk(neighbor.x, neighbor.z);
            if (neighborHolder == null || neighborChunk == null) {
                continue;
            }

            if (this.sentChunks.contains(neighbor.toLong())) {
                int[] lightUpdates = ((WPChunkHolder) neighborHolder).worldpreview$flushUpdates();
                if (lightUpdates[0] != 0 || lightUpdates[1] != 0) {
                    chunkPackets.add(new LightUpdateS2CPacket(neighbor, neighborChunk.getLightingProvider(), lightUpdates[0], lightUpdates[1], false));
                }
            } else if (this.culledChunks.contains(neighbor.toLong()) && !this.sentEmptyChunks.contains(neighbor.toLong())) {
                chunkPackets.add(this.createEmptyChunkPacket(neighborChunk));
                this.sentEmptyChunks.add(neighbor.toLong());
            }
        }

        packetQueue.addAll(chunkPackets);

        this.sentChunks.add(pos.toLong());
    }

    @Unique
    private boolean shouldCullChunk(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return !this.frustum.isVisible(new Box(pos.getStartX(), 0, pos.getStartZ(), pos.getStartX() + 16, chunk.getHighestNonEmptySectionYOffset() + 16, pos.getStartZ() + 16));
    }

    @Unique
    private void processEntity(Queue<Packet<?>> packetQueue, int id, ThreadedAnvilChunkStorageAccessor.EntityTrackerAccessor tracker) {
        if (this.sentEntities.contains(id) || this.culledEntities.contains(id)) {
            return;
        }

        Entity entity = tracker.getEntity();

        if (!this.shouldSendEntity(entity)) {
            return;
        }

        if (this.shouldCullEntity(entity)) {
            this.culledEntities.add(entity.getEntityId());
            return;
        }

        List<Packet<?>> entityPackets = new ArrayList<>();

        ChunkPos chunkPos = new ChunkPos(entity.chunkX, entity.chunkZ);
        if (!this.sentChunks.contains(chunkPos.toLong()) && !this.sentEmptyChunks.contains(chunkPos.toLong())) {
            WorldChunk chunk = this.getWorldChunk(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                return;
            }
            entityPackets.add(this.createEmptyChunkPacket(chunk));
            this.sentEmptyChunks.add(chunkPos.toLong());
        }

        tracker.getEntry().sendPackets(entityPackets::add);
        // see EntityTrackerEntry#tick
        entityPackets.add(new EntityS2CPacket.Rotate(id, (byte) MathHelper.floor(entity.yaw * 256.0f / 360.0f), (byte) MathHelper.floor(entity.pitch * 256.0f / 360.0f), entity.isOnGround()));
        entityPackets.add(new EntitySetHeadYawS2CPacket(entity, (byte) MathHelper.floor(entity.getHeadYaw() * 256.0f / 360.0f)));

        packetQueue.addAll(entityPackets);

        this.sentEntities.add(id);
    }

    @Unique
    private boolean shouldSendEntity(Entity entity) {
        // prevent passengers being sent before their vehicle
        Entity vehicle = entity.getVehicle();
        if (vehicle != null && !this.sentEntities.contains(vehicle.getEntityId())) {
            return false;
        }
        long chunkPos = ChunkPos.toLong(entity.chunkX, entity.chunkZ);
        return this.sentChunks.contains(chunkPos) || this.culledChunks.contains(chunkPos);
    }

    @Unique
    private boolean shouldCullEntity(Entity entity) {
        // Do not try to cull entities that are vehicles or passengers, supporting that would cause unnecessary complexity
        return !entity.hasVehicle() && !entity.hasPassengers() && !entity.ignoreCameraFrustum && !this.frustum.isVisible(entity.getVisibilityBoundingBox());
    }

    @Unique
    private ChunkHolder getChunkHolder(ChunkPos pos) {
        return ((ThreadedAnvilChunkStorageAccessor) this.threadedAnvilChunkStorage).getChunkHolders().get(pos.toLong());
    }

    @Unique
    private Set<ChunkPos> getNeighborChunks(ChunkPos pos) {
        Set<ChunkPos> neighbors = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                neighbors.add(new ChunkPos(pos.x + x, pos.z + z));
            }
        }
        return neighbors;
    }

    @Unique
    private ChunkDataS2CPacket createEmptyChunkPacket(WorldChunk chunk) {
        return new ChunkDataS2CPacket(new WorldChunk(chunk.getWorld(), chunk.getPos(), chunk.getBiomeArray()), 65535, true);
    }
}
