package me.voidxwalker.worldpreview;

import com.google.common.collect.Sets;
import me.voidxwalker.worldpreview.mixin.access.ClientPlayNetworkHandlerAccessor;
import me.voidxwalker.worldpreview.mixin.access.PlayerEntityAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.PlayerModelPart;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
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

    public static boolean configure(ServerWorld serverWorld) {
        WPFakeServerPlayerEntity fakePlayer;
        try {
            CALCULATING_SPAWN.set(true);
            fakePlayer = new WPFakeServerPlayerEntity(serverWorld.getServer(), serverWorld, MinecraftClient.getInstance().getSession().getProfile());
        } catch (WorldPreviewMissingChunkException e) {
            return false;
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
                new ClientWorld.Properties(serverWorld.getDifficulty(), serverWorld.getServer().isHardcore(), serverWorld.isFlat()),
                serverWorld.getRegistryKey(),
                serverWorld.getDimension(),
                // WorldPreviews Chunk Distance is one lower than Minecraft's chunkLoadDistance,
                // when it's at 1 only the chunk the player is in gets sent
                config.chunkDistance - 1,
                MinecraftClient.getInstance()::getProfiler,
                WorldPreview.worldRenderer,
                serverWorld.isDebugWorld(),
                serverWorld.getSeed()
        );
        ClientPlayerEntity player = interactionManager.createPlayer(
                world,
                null,
                null
        );

        player.copyPositionAndRotation(fakePlayer);
        // avoid the ClientPlayer being removed from previews on id collisions by setting its entityId
        // to the ServerPlayer's as would be done in the ClientPlayNetworkHandler
        player.setId(fakePlayer.getId());
        // copy the inventory from the server player, for mods like icarus to render given items on preview
        player.getInventory().readNbt(fakePlayer.getInventory().writeNbt(new NbtList()));
        player.getInventory().selectedSlot = fakePlayer.getInventory().selectedSlot;
        // reset the randomness introduced to the yaw in LivingEntity#<init>
        player.headYaw = 0.0F;
        player.setYaw(0.0F);
        // the end result of the elytra lerp, is applied at the beginning because
        // otherwise seedqueue would have to take it into account when caching the framebuffer
        player.elytraPitch = (float) (Math.PI / 12);
        player.elytraRoll = (float) (-Math.PI / 12);

        GameMode gameMode = GameMode.DEFAULT;

        // This part is not actually relevant for previewing new worlds,
        // I just personally like the idea of worldpreview principally being able to work on old worlds as well
        // same with sending world info and scoreboard data
        NbtCompound playerData = serverWorld.getServer().getSaveProperties().getPlayerData();
        if (playerData != null) {
            player.readNbt(playerData);

            // see ServerPlayerEntity#readCustomDataFromTag
            if (playerData.contains("playerGameType", 99)) {
                gameMode = GameMode.byId(playerData.getInt("playerGameType"));
            }

            // see LivingEntity#readCustomDataFromTag, only gets read on server worlds
            if (playerData.contains("Attributes", 9)) {
                player.getAttributes().readNbt(playerData.getList("Attributes", 10));
            }

            // see PlayerManager#onPlayerConnect
            if (playerData.contains("RootVehicle", 10)) {
                NbtCompound vehicleData = playerData.getCompound("RootVehicle");
                UUID uUID = vehicleData.containsUuid("Attach") ? vehicleData.getUuid("Attach") : null;
                EntityType.loadEntityWithPassengers(vehicleData.getCompound("Entity"), serverWorld, entity -> {
                    entity.world = world;
                    world.addEntity(entity.getId(), entity);
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
        packetQueue.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED, (gameMode != GameMode.DEFAULT ? gameMode : serverWorld.getServer().getDefaultGameMode()).getId()));

        // see PlayerManager#sendWorldInfo
        packetQueue.add(new WorldBorderInitializeS2CPacket(serverWorld.getWorldBorder()));
        packetQueue.add(new WorldTimeUpdateS2CPacket(serverWorld.getTime(), serverWorld.getTimeOfDay(), serverWorld.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)));
        packetQueue.add(new PlayerSpawnPositionS2CPacket(serverWorld.getSpawnPos(), serverWorld.getSpawnAngle()));
        if (serverWorld.isRaining()) {
            packetQueue.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STARTED, 0.0f));
            packetQueue.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, serverWorld.getRainGradient(1.0f)));
            packetQueue.add(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, serverWorld.getThunderGradient(1.0f)));
        }

        // see PlayerManager#sendScoreboard
        ServerScoreboard scoreboard = serverWorld.getScoreboard();
        HashSet<ScoreboardObjective> set = Sets.newHashSet();
        for (Team team : scoreboard.getTeams()) {
            packetQueue.add(TeamS2CPacket.updateTeam(team, true));
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
        for (PlayerModelPart playerModelPart : PlayerModelPart.values()) {
            if (MinecraftClient.getInstance().options.isPlayerModelPartEnabled(playerModelPart)) {
                playerModelPartsBitMask |= playerModelPart.getBitFlag();
            }
        }
        player.getDataTracker().set(PlayerEntityAccessor.worldpreview$getPLAYER_MODEL_PARTS(), (byte) playerModelPartsBitMask);

        // set cape to player position
        player.prevCapeX = player.capeX = player.getX();
        player.prevCapeY = player.capeY = player.getY();
        player.prevCapeZ = player.capeZ = player.getZ();

        world.addPlayer(player.getId(), player);
        world.getChunkManager().setChunkMapCenter(player.getChunkPos().x, player.getChunkPos().z);

        ((ClientPlayNetworkHandlerAccessor) player.networkHandler).worldpreview$setWorld(world);

        // camera has to be updated early for chunk/entity data culling to work
        // we pass the fake player, so we know the call comes from here in CameraMixin#modifyCameraY
        Perspective perspective = MinecraftClient.getInstance().options.getPerspective();
        camera.update(world, fakePlayer, !perspective.isFirstPerson(), perspective.isFrontView(), 1.0f);

        set(world, player, interactionManager, camera, packetQueue);
        return true;
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
