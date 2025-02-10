package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.WorldPreviewProperties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {
    @Unique
    private List<ButtonWidget> buttons;
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
        return 45;
    }

    @ModifyVariable(
            method = "render",
            at = @At("STORE"),
            ordinal = 3
    )
    private int moveChunkMapY(int i) {
        return this.height - 75;
    }

    @WrapWithCondition(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/LevelLoadingScreen;renderBackground()V"
            )
    )
    private boolean renderWorldPreview(LevelLoadingScreen screen, int mouseX, int mouseY, float delta) {
        WorldPreviewProperties properties = WorldPreview.properties;
        if (properties == null) {
            return true;
        }

        if (WorldPreview.isKilled()) {
            return false;
        }

        if (!properties.isInitialized()) {
            properties.initialize();
        }
        properties.run(p -> this.renderWorldPreview(p, mouseX, mouseY, delta));
        return false;
    }

    @Unique
    private void renderWorldPreview(WorldPreviewProperties properties, int mouseX, int mouseY, float delta) {
        properties.render(mouseX, mouseY, delta, this.buttons, this.width, this.height, this.showMenu);
    }

    @Override
    protected void init() {
        this.buttons = WorldPreviewProperties.createMenu(this.width, this.height, () -> this.showMenu = false, WorldPreview::kill);
        for (ButtonWidget button : this.buttons) {
            this.addButton(button);
        }
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
                    this.showMenu = false;
                }
            } else {
                this.showMenu = true;
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
