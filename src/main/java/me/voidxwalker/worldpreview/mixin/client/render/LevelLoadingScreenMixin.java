package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.WorldPreviewProperties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.gui.widget.GridWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.server.WorldGenerationProgressTracker;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {
    @Shadow
    @Final
    private WorldGenerationProgressTracker progressProvider;

    @Unique
    private GridWidget gridWidget;
    @Unique
    private boolean showMenu = true;

    protected LevelLoadingScreenMixin(Text title) {
        super(title);
    }

    @ModifyVariable(
            method = "render",
            at = @At("STORE"),
            ordinal = 2
    )
    private int moveChunkMapX(int i) {
        return this.progressProvider.getSize();
    }

    @ModifyVariable(
            method = "render",
            at = @At("STORE"),
            ordinal = 3
    )
    private int moveChunkMapY(int i) {
        return this.height - this.progressProvider.getSize();
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V"
            )
    )
    private void renderWorldPreview(LevelLoadingScreen screen, DrawContext context, int mouseX, int mouseY, float delta, Operation<Void> original) {
        WorldPreviewProperties properties = WorldPreview.properties;
        if (properties == null) {
            this.gridWidget.forEachChild(widget -> widget.visible = false);
            original.call(screen, context, mouseX, mouseY, delta);
            this.gridWidget.forEachChild(widget -> widget.visible = this.showMenu);
            return;
        }
        if (!properties.isInitialized()) {
            properties.initialize();
        }
        if (WorldPreview.isKilled()) {
            return;
        }
        properties.run(p -> this.renderWorldPreview(p, context, mouseX, mouseY, delta));
    }

    @Unique
    private void renderWorldPreview(WorldPreviewProperties properties, DrawContext context, int mouseX, int mouseY, float delta) {
        properties.render(context, mouseX, mouseY, delta, this.gridWidget, this.width, this.height, this.showMenu);
    }

    @Unique
    private void setShowMenu(boolean showMenu) {
        this.showMenu = showMenu;
        this.gridWidget.forEachChild(button -> button.visible = this.showMenu);
    }

    @Override
    protected void init() {
        this.gridWidget = WorldPreviewProperties.createMenu(this.width, this.height, () -> this.setShowMenu(false), WorldPreview::kill);
        this.gridWidget.forEachChild(button -> this.addDrawableChild(button).visible = this.showMenu);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (WorldPreview.properties == null || !WorldPreview.properties.isInitialized()) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.showMenu) {
                if (InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_F3)) {
                    this.setShowMenu(false);
                }
            } else {
                this.setShowMenu(true);
            }
            return true;
        }
        return false;
    }

    @Override
    public void removed() {
        WorldPreview.clear();
    }
}
