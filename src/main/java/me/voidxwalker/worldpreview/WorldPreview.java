package me.voidxwalker.worldpreview;

import com.google.common.collect.Sets;
import me.voidxwalker.worldpreview.mixin.access.ClientPlayNetworkHandlerAccessor;
import me.voidxwalker.worldpreview.mixin.access.PlayerEntityAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.PlayerModelPart;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.profiler.DummyProfiler;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.level.LevelInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public class WorldPreview {
    public static final boolean START_ON_OLD_WORLDS = false;

    public static final Logger LOGGER = LogManager.getLogger();
    public static final boolean HAS_STATEOUTPUT = FabricLoader.getInstance().isModLoaded("state-output");

    public static final ThreadLocal<Boolean> CALCULATING_SPAWN = new ThreadLocal<>();

    public static WorldPreviewConfig config;

    public static WorldRenderer worldRenderer;
    public static WorldPreviewProperties properties;

    public static boolean renderingPreview;
    private static boolean logPreviewStart;
    private static boolean kill;

    public static void set(ClientWorld world, ClientPlayerEntity player, ClientPlayerInteractionManager interactionManager, Camera camera, Queue<Packet<?>> packetQueue) {
        WorldPreview.properties = new WorldPreviewProperties(world, player, interactionManager, camera, packetQueue);
    }

    public static void configure(ServerWorld serverWorld) {
        WPFakeServerPlayerEntity fakePlayer;
        try {
            CALCULATING_SPAWN.set(true);
            fakePlayer = new WPFakeServerPlayerEntity(serverWorld.getServer(), serverWorld, MinecraftClient.getInstance().getSession().getProfile(), new ServerPlayerInteractionManager(serverWorld));
        } finally {
            CALCULATING_SPAWN.remove();
        }

        ClientPlayNetworkHandler networkHandler = new ClientPlayNetworkHandler(
                MinecraftClient.getInstance(),
                null,
                null,
                MinecraftClient.getInstance().getSession().getProfile()
        );
        ClientPlayerInteractionManager interactionManager = new ClientPlayerInteractionManager(
                MinecraftClient.getInstance(),
                networkHandler
        );

        ClientWorld world = new ClientWorld(
                networkHandler,
                new LevelInfo(serverWorld.getLevelProperties()),
                serverWorld.getDimension().getType(),
                // WorldPreviews Chunk Distance is one lower than Minecraft's chunkLoadDistance,
                // when it's at 1 only the chunk the player is in gets sent
                config.chunkDistance - 1,
                DummyProfiler.INSTANCE,
                WorldPreview.worldRenderer
        );
        ClientPlayerEntity player = interactionManager.createPlayer(
                world,
                null,
                null
        );

        player.copyPositionAndRotation(fakePlayer);
        // avoid the ClientPlayer being removed from previews on id collisions by setting its entityId
        // to the ServerPlayer's as would be done in the ClientPlayNetworkHandler
        player.setEntityId(fakePlayer.getEntityId());
        // copy the inventory from the server player, for mods like icarus to render given items on preview
        player.inventory.deserialize(fakePlayer.inventory.serialize(new ListTag()));
        player.inventory.selectedSlot = fakePlayer.inventory.selectedSlot;
        // reset the randomness introduced to the yaw in LivingEntity#<init>
        player.headYaw = player.yaw = 0.0F;
        // the end result of the elytra lerp, is applied at the beginning because
        // otherwise seedqueue would have to take it into account when caching the framebuffer
        player.elytraPitch = (float) (Math.PI / 12);
        player.elytraRoll = (float) (-Math.PI / 12);

        GameMode gameMode = GameMode.NOT_SET;

        // This part is not actually relevant for previewing new worlds,
        // I just personally like the idea of worldpreview principally being able to work on old worlds as well
        // same with sending world info and scoreboard data
        CompoundTag playerData = serverWorld.getServer().getPlayerManager().getUserData();
        if (playerData != null) {
            player.fromTag(playerData);

            // see ServerPlayerEntity#readCustomDataFromTag
            if (playerData.contains("playerGameType", 99)) {
                gameMode = GameMode.byId(playerData.getInt("playerGameType"));
            }

            // see LivingEntity#readCustomDataFromTag, only gets read on server worlds
            if (playerData.contains("Attributes", 9)) {
                EntityAttributes.fromTag(player.getAttributes(), playerData.getList("Attributes", 10));
            }

            // see PlayerManager#onPlayerConnect
            if (playerData.contains("RootVehicle", 10)) {
                CompoundTag vehicleData = playerData.getCompound("RootVehicle");
                UUID uUID = vehicleData.containsUuid("Attach") ? vehicleData.getUuid("Attach") : null;
                EntityType.loadEntityWithPassengers(vehicleData.getCompound("Entity"), serverWorld, entity -> {
                    entity.world = world;
                    world.addEntity(entity.getEntityId(), entity);
                    if (entity.getUuid().equals(uUID)) {
                        player.startRiding(entity, true);
                    }
                    return entity;
                });
            }
        }

        Camera camera = new Camera();

        Queue<Packet<?>> packetQueue = new LinkedBlockingQueue<>();
        packetQueue.add(new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, fakePlayer));
        packetQueue.add(new GameStateChangeS2CPacket(3, (gameMode != GameMode.NOT_SET ? gameMode : serverWorld.getServer().getDefaultGameMode()).getId()));

        // see PlayerManager#sendWorldInfo
        packetQueue.add(new WorldBorderS2CPacket(serverWorld.getWorldBorder(), WorldBorderS2CPacket.Type.INITIALIZE));
        packetQueue.add(new WorldTimeUpdateS2CPacket(serverWorld.getTime(), serverWorld.getTimeOfDay(), serverWorld.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)));
        packetQueue.add(new PlayerSpawnPositionS2CPacket(serverWorld.getSpawnPos()));
        if (serverWorld.isRaining()) {
            packetQueue.add(new GameStateChangeS2CPacket(1, 0.0F));
            packetQueue.add(new GameStateChangeS2CPacket(7, world.getRainGradient(1.0F)));
            packetQueue.add(new GameStateChangeS2CPacket(8, world.getThunderGradient(1.0F)));
        }

        // see PlayerManager#sendScoreboard
        ServerScoreboard scoreboard = serverWorld.getScoreboard();
        HashSet<ScoreboardObjective> set = Sets.newHashSet();
        for (Team team : scoreboard.getTeams()) {
            packetQueue.add(new TeamS2CPacket(team, 0));
        }
        for (int i = 0; i < 19; ++i) {
            ScoreboardObjective scoreboardObjective = scoreboard.getObjectiveForSlot(i);
            if (scoreboardObjective == null || set.contains(scoreboardObjective)) {
                continue;
            }
            packetQueue.addAll(scoreboard.createChangePackets(scoreboardObjective));
            set.add(scoreboardObjective);
        }

        // make player model parts visible
        int playerModelPartsBitMask = 0;
        for (PlayerModelPart playerModelPart : MinecraftClient.getInstance().options.getEnabledPlayerModelParts()) {
            playerModelPartsBitMask |= playerModelPart.getBitFlag();
        }
        player.getDataTracker().set(PlayerEntityAccessor.worldpreview$getPLAYER_MODEL_PARTS(), (byte) playerModelPartsBitMask);

        // set cape to player position
        player.field_7524 = player.field_7500 = player.getX();
        player.field_7502 = player.field_7521 = player.getY();
        player.field_7522 = player.field_7499 = player.getZ();

        world.addPlayer(player.getEntityId(), player);

        // set player chunk coordinates,
        // usually these get set when adding the entity to a chunk,
        // however the chunk the player is in is not actually loaded yet
        player.chunkX = MathHelper.floor(player.getX() / 16.0);
        player.chunkY = MathHelper.clamp(MathHelper.floor(player.getY() / 16.0), 0, 16);
        player.chunkZ = MathHelper.floor(player.getZ() / 16.0);

        world.getChunkManager().setChunkMapCenter(player.chunkX, player.chunkZ);

        ((ClientPlayNetworkHandlerAccessor) player.networkHandler).worldpreview$setWorld(world);

        // camera has to be updated early for chunk/entity data culling to work
        // we pass the fake player, so we know the call comes from here in CameraMixin#modifyCameraY
        int perspective = MinecraftClient.getInstance().options.perspective;
        camera.update(world, fakePlayer, perspective > 0, perspective == 2, 1.0f);

        set(world, player, interactionManager, camera, packetQueue);
    }

    public static void clear() {
        WorldPreview.properties = null;
        WorldPreview.kill = false;
        WorldPreview.logPreviewStart = false;
        WorldPreview.worldRenderer.setWorld(null);
    }

    public static boolean inPreview() {
        WorldPreviewProperties properties = WorldPreview.properties;
        return properties != null && properties.isInitialized();
    }

    public static boolean isKilled() {
        return WorldPreview.kill;
    }

    public static void kill() {
        WorldPreview.kill = true;
    }

    public static boolean shouldLogPreviewStart() {
        return WorldPreview.logPreviewStart;
    }

    public static void setLogPreviewStart(boolean logPreviewStart) {
        WorldPreview.logPreviewStart = logPreviewStart;
    }
}
