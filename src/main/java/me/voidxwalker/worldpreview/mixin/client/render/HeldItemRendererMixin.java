package me.voidxwalker.worldpreview.mixin.client.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.voidxwalker.worldpreview.WorldPreview;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {

    @ModifyExpressionValue(
            method = {
                    "renderFirstPersonItem(F)V",
                    "renderMapInBothHands"
            },
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;mainHand:Lnet/minecraft/item/ItemStack;",
                    opcode = Opcodes.GETFIELD
            ),
            require = 2
    )
    private ItemStack modifyMainHand(ItemStack mainHand) {
        if (WorldPreview.renderingPreview) {
            return WorldPreview.properties.player.getMainHandStack();
        }
        return mainHand;
    }

    @ModifyExpressionValue(
            method = "renderFirstPersonItem(F)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;offHand:Lnet/minecraft/item/ItemStack;",
                    opcode = Opcodes.GETFIELD
            )
    )
    private ItemStack modifyOffHand(ItemStack offHand) {
        if (WorldPreview.renderingPreview) {
            return WorldPreview.properties.player.getOffHandStack();
        }
        return offHand;
    }

    @ModifyExpressionValue(
            method = "renderFirstPersonItem(F)V",
            at = {
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;equipProgressMainHand:F",
                            opcode = Opcodes.GETFIELD
                    ),
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;prevEquipProgressMainHand:F",
                            opcode = Opcodes.GETFIELD
                    ),
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;equipProgressOffHand:F",
                            opcode = Opcodes.GETFIELD
                    ),
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;prevEquipProgressOffHand:F",
                            opcode = Opcodes.GETFIELD
                    )
            },
            require = 4
    )
    private float modifyEquipProgress(float equipProgress) {
        if (WorldPreview.renderingPreview) {
            return 1.0f;
        }
        return equipProgress;
    }
}
