package me.voidxwalker.worldpreview;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import me.contaria.speedrunapi.util.TextUtil;
import me.voidxwalker.worldpreview.mixin.access.EntityAccessor;
import me.voidxwalker.worldpreview.mixin.access.GameRendererAccessor;
import me.voidxwalker.worldpreview.mixin.access.MinecraftClientAccessor;
import me.voidxwalker.worldpreview.mixin.access.PlayerEntityAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.GridWidget;
import net.minecraft.client.gui.widget.SimplePositioningWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Util;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;

import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

public class WorldPreviewProperties {
    private static final ButtonWidget.PressAction NO_OP = button -> {};

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

    public void render(DrawContext context, int mouseX, int mouseY, float delta, GridWidget gridWidget, int width, int height, boolean showMenu) {
        this.tickPackets();
        this.tickEntities();
        this.renderWorld();
        this.renderHud(context);
        this.renderMenu(context, mouseX, mouseY, delta, gridWidget, width, height, showMenu);
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
        return packet instanceof ChunkDataS2CPacket || packet instanceof EntitySpawnS2CPacket;
    }

    protected int getDataLimit() {
        return WorldPreview.config.dataLimit;
    }

    public void tickEntities() {
        Profiler profiler = MinecraftClient.getInstance().getProfiler();

        profiler.swap("update_player_size");
        // clip the player into swimming/crawling mode if necessary
        ((PlayerEntityAccessor) this.player).worldpreview$updatePose();

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
        profiler.push(() -> Registries.ENTITY_TYPE.getId(entity.getType()).toString());

        if (entity.getVehicle() != null) {
            entity.getVehicle().updatePassengerPosition(entity);
            entity.calculateDimensions();
            entity.updatePositionAndAngles(entity.getX(), entity.getY(), entity.getZ(), entity.getYaw(), entity.getPitch());
        }
        entity.baseTick();

        profiler.pop();
    }

    public void renderWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        Profiler profiler = client.getProfiler();
        Window window = client.getWindow();

        profiler.swap("render_preview");

        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(
                0.0f,
                window.getFramebufferWidth(),
                window.getFramebufferHeight(),
                0.0f,
                0.1f,
                1000.0f
        ), VertexSorter.BY_DISTANCE);
        MatrixStack matrixStack = RenderSystem.getModelViewStack();
        matrixStack.loadIdentity();
        matrixStack.translate(0.0, 0.0, 0.0);
        RenderSystem.applyModelViewMatrix();
        DiffuseLighting.disableGuiDepthLighting();

        profiler.push("light_map");
        client.gameRenderer.getLightmapTextureManager().tick();
        profiler.swap("render_world");
        client.gameRenderer.renderWorld(0.0F, Util.getMeasuringTimeNano(), new MatrixStack());
        profiler.swap("entity_outlines");
        client.worldRenderer.drawEntityOutlinesFramebuffer();
        profiler.pop();

        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    }

    public void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        Profiler profiler = client.getProfiler();
        Window window = client.getWindow();

        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(
                0.0f,
                (float) (window.getFramebufferWidth() / window.getScaleFactor()),
                (float) (window.getFramebufferHeight() / window.getScaleFactor()),
                0.0f,
                1000.0f,
                21000.0f
        ), VertexSorter.BY_Z);
        MatrixStack matrixStack = RenderSystem.getModelViewStack();
        matrixStack.loadIdentity();
        matrixStack.translate(0.0, 0.0, -2000.0);
        RenderSystem.applyModelViewMatrix();
        DiffuseLighting.enableGuiDepthLighting();

        profiler.push("ingame_hud");
        client.inGameHud.render(context, 0.0F);
        profiler.pop();

        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    }

    public void renderMenu(DrawContext context, int mouseX, int mouseY, float delta, GridWidget gridWidget, int width, int height, boolean showMenu) {
        if (showMenu) {
            context.fillGradient(0, 0, width, height + 1, -1072689136, -804253680);
            gridWidget.forEachChild(widget -> widget.render(context, mouseX, mouseY, delta));
        } else {
            context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, TextUtil.translatable("menu.paused"), width / 2, 10, 16777215);
        }
    }

    public static GridWidget createMenu(int width, int height, Runnable returnToGame, Runnable kill) {
        GridWidget gridWidget = new GridWidget();
        gridWidget.getMainPositioner().margin(4, 4, 4, 0);
        GridWidget.Adder adder = gridWidget.createAdder(2);

        adder.add(ButtonWidget.builder(TextUtil.translatable("menu.returnToGame"), button -> returnToGame.run()).width(204).build(), 2, gridWidget.copyPositioner().marginTop(50));
        adder.add(ButtonWidget.builder(TextUtil.translatable("gui.advancements"), NO_OP).width(98).build());
        adder.add(ButtonWidget.builder(TextUtil.translatable("gui.stats"), NO_OP).width(98).build());
        adder.add(ButtonWidget.builder(TextUtil.translatable("menu.sendFeedback"), NO_OP).width(98).build());
        adder.add(ButtonWidget.builder(TextUtil.translatable("menu.reportBugs"), NO_OP).width(98).build());
        adder.add(ButtonWidget.builder(TextUtil.translatable("menu.options"), NO_OP).width(98).build());
        adder.add(ButtonWidget.builder(TextUtil.translatable("menu.shareToLan"), NO_OP).width(98).build());
        adder.add(ButtonWidget.builder(TextUtil.translatable("menu.returnToMenu"), button -> {
            kill.run();
            button.active = false;
        }).width(204).build(), 2);
        
        gridWidget.refreshPositions();
        SimplePositioningWidget.setPos(gridWidget, 0, 0, width, height, 0.5F, 0.25F);
        
        return gridWidget;
    }
}
