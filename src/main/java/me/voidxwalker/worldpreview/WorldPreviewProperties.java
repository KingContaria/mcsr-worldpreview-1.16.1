package me.voidxwalker.worldpreview;

import com.mojang.blaze3d.platform.GlStateManager;
import me.voidxwalker.worldpreview.mixin.access.EntityAccessor;
import me.voidxwalker.worldpreview.mixin.access.GameRendererAccessor;
import me.voidxwalker.worldpreview.mixin.access.MinecraftClientAccessor;
import me.voidxwalker.worldpreview.mixin.access.PlayerEntityAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.MobSpawnS2CPacket;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

public class WorldPreviewProperties extends DrawableHelper {
    private static final ButtonWidget.PressAction NO_OP = button -> {
    };

    public final ClientWorld world;
    public final ClientPlayerEntity player;
    public final ClientPlayerInteractionManager interactionManager;
    public final Camera camera;
    public final Queue<Packet<?>> packetQueue;

    private boolean initialized;

    public WorldPreviewProperties(ClientWorld world, ClientPlayerEntity player, ClientPlayerInteractionManager interactionManager, Camera camera, Queue<Packet<?>> packetQueue) {
        this.world = Objects.requireNonNull(world);
        this.player = Objects.requireNonNull(player);
        this.interactionManager = Objects.requireNonNull(interactionManager);
        this.camera = Objects.requireNonNull(camera);
        this.packetQueue = Objects.requireNonNull(packetQueue);
    }

    public void initialize() {
        if (!this.initialized) {
            WorldPreview.worldRenderer.setWorld(this.world);
            this.initialized = true;
        }
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    /**
     * Sets {@link WorldPreview} properties to the values stored in this {@link WorldPreviewProperties}.
     *
     * @see WorldPreview#set
     */
    public void run(Consumer<WorldPreviewProperties> consumer) {
        MinecraftClient client = MinecraftClient.getInstance();

        WorldRenderer mcWorldRenderer = client.worldRenderer;
        ClientPlayerEntity mcPlayer = client.player;
        ClientWorld mcWorld = client.world;
        Entity mcCameraEntity = client.cameraEntity;
        ClientPlayerInteractionManager mcInteractionManager = client.interactionManager;
        Camera mcCamera = ((GameRendererAccessor) client.gameRenderer).worldpreview$getCamera();

        try {
            WorldPreview.renderingPreview = true;

            ((MinecraftClientAccessor) client).worldpreview$setWorldRenderer(WorldPreview.worldRenderer);
            client.player = this.player;
            client.world = this.world;
            client.cameraEntity = this.player;
            client.interactionManager = this.interactionManager;
            ((GameRendererAccessor) client.gameRenderer).worldpreview$setCamera(this.camera);

            consumer.accept(this);
        } finally {
            WorldPreview.renderingPreview = false;

            ((MinecraftClientAccessor) client).worldpreview$setWorldRenderer(mcWorldRenderer);
            client.player = mcPlayer;
            client.world = mcWorld;
            client.cameraEntity = mcCameraEntity;
            client.interactionManager = mcInteractionManager;
            ((GameRendererAccessor) client.gameRenderer).worldpreview$setCamera(mcCamera);
        }
    }

    public void render(int mouseX, int mouseY, float delta, List<ButtonWidget> buttons, int width, int height, boolean showMenu) {
        this.tickPackets();
        this.tickEntities();
        this.renderWorld();
        this.renderHud();
        this.renderMenu(mouseX, mouseY, delta, buttons, width, height, showMenu);
    }

    public void tickPackets() {
        Profiler profiler = MinecraftClient.getInstance().getProfiler();
        int dataLimit = this.getDataLimit();
        int applied = 0;

        profiler.swap("tick_packets");
        while (this.shouldApplyPacket(this.packetQueue.peek(), dataLimit, applied++)) {
            //noinspection unchecked
            Packet<ClientPlayPacketListener> packet = (Packet<ClientPlayPacketListener>) Objects.requireNonNull(this.packetQueue.poll());
            profiler.push(() -> packet.getClass().getSimpleName());
            packet.apply(this.player.networkHandler);
            profiler.pop();
        }
    }

    protected boolean shouldApplyPacket(Packet<?> packet, int dataLimit, int applied) {
        return packet != null && (dataLimit >= 100 || dataLimit > applied || !this.canStopAtPacket(packet));
    }

    protected boolean canStopAtPacket(Packet<?> packet) {
        return packet instanceof ChunkDataS2CPacket || packet instanceof MobSpawnS2CPacket || packet instanceof EntitySpawnS2CPacket;
    }

    protected int getDataLimit() {
        return WorldPreview.config.dataLimit;
    }

    public void tickEntities() {
        Profiler profiler = MinecraftClient.getInstance().getProfiler();

        profiler.swap("update_player_size");
        // clip the player into swimming/crawling mode if necessary
        ((PlayerEntityAccessor) this.player).worldpreview$updateSize();

        profiler.swap("tick_new_entities");
        for (Entity entity : this.world.getEntities()) {
            if (!((EntityAccessor) entity).worldpreview$isFirstUpdate() || entity.getVehicle() != null && ((EntityAccessor) entity.getVehicle()).worldpreview$isFirstUpdate()) {
                continue;
            }
            tickEntity(entity);
            for (Entity passenger : entity.getPassengersDeep()) {
                tickEntity(passenger);
            }
        }
    }

    private void tickEntity(Entity entity) {
        Profiler profiler = MinecraftClient.getInstance().getProfiler();
        profiler.push(() -> Registry.ENTITY_TYPE.getId(entity.getType()).toString());

        if (entity.getVehicle() != null) {
            entity.getVehicle().updatePassengerPosition(entity);
            entity.calculateDimensions();
            entity.updatePositionAndAngles(entity.x, entity.y, entity.z, entity.yaw, entity.pitch);
        }
        entity.baseTick();

        profiler.pop();
    }

    public void renderWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        Profiler profiler = client.getProfiler();
        Window window = client.window;

        profiler.swap("render_preview");

        GlStateManager.clear(256, MinecraftClient.IS_SYSTEM_MAC);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, window.getFramebufferWidth(), window.getFramebufferHeight(), 0.0, 1000.0, 3000.0);
        GlStateManager.loadIdentity();
        GlStateManager.translatef(0.0F, 0.0F, 0.0F);

        profiler.push("light_map");
        //noinspection resource
        ((GameRendererAccessor) client.gameRenderer).worldpreview$getLightmapTextureManager().tick();
        profiler.swap("render_world");
        client.gameRenderer.renderWorld(0.0F, Long.MAX_VALUE);
        profiler.swap("entity_outlines");
        client.worldRenderer.drawEntityOutlinesFramebuffer();
        profiler.pop();

        GlStateManager.clear(256, MinecraftClient.IS_SYSTEM_MAC);
    }

    public void renderHud() {
        MinecraftClient client = MinecraftClient.getInstance();
        Profiler profiler = client.getProfiler();
        Window window = client.window;

        GlStateManager.clear(256, MinecraftClient.IS_SYSTEM_MAC);
        GlStateManager.alphaFunc(516, 0.1f);
        window.method_4493(MinecraftClient.IS_SYSTEM_MAC);

        profiler.push("ingame_hud");
        client.inGameHud.render(0.0F);
        profiler.pop();

        GlStateManager.clear(256, MinecraftClient.IS_SYSTEM_MAC);
    }

    public void renderMenu(int mouseX, int mouseY, float delta, List<ButtonWidget> buttons, int width, int height, boolean showMenu) {
        if (showMenu) {
            this.fillGradient(0, 0, width, height + 1, -1072689136, -804253680);
            for (ButtonWidget button : buttons) {
                button.render(mouseX, mouseY, delta);
            }
        } else {
            this.drawCenteredString(MinecraftClient.getInstance().textRenderer, I18n.translate("menu.paused"), width / 2, 10, 16777215);
        }
    }

    public static List<ButtonWidget> createMenu(int width, int height, Runnable returnToGame, Runnable kill) {
        List<ButtonWidget> buttons = new ArrayList<>();
        buttons.add(new ButtonWidget(width / 2 - 102, height / 4 + 24 - 16, 204, 20, I18n.translate("menu.returnToGame"), button -> returnToGame.run()));
        buttons.add(new ButtonWidget(width / 2 - 102, height / 4 + 48 - 16, 98, 20, I18n.translate("gui.advancements"), NO_OP));
        buttons.add(new ButtonWidget(width / 2 + 4, height / 4 + 48 - 16, 98, 20, I18n.translate("gui.stats"), NO_OP));
        buttons.add(new ButtonWidget(width / 2 - 102, height / 4 + 72 - 16, 98, 20, I18n.translate("menu.sendFeedback"), NO_OP));
        buttons.add(new ButtonWidget(width / 2 + 4, height / 4 + 72 - 16, 98, 20, I18n.translate("menu.reportBugs"), NO_OP));
        buttons.add(new ButtonWidget(width / 2 - 102, height / 4 + 96 - 16, 98, 20, I18n.translate("menu.options"), NO_OP));
        buttons.add(new ButtonWidget(width / 2 + 4, height / 4 + 96 - 16, 98, 20, I18n.translate("menu.shareToLan"), NO_OP));
        buttons.add(new ButtonWidget(width / 2 - 102, height / 4 + 120 - 16, 204, 20, I18n.translate("menu.returnToMenu"), button -> {
            kill.run();
            button.active = false;
        }));
        return buttons;
    }
}
