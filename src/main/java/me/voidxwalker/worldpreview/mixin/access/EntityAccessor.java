package me.voidxwalker.worldpreview.mixin.access;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("firstUpdate")
    boolean worldpreview$isFirstUpdate();

    @Invoker("setWorld")
    void worldpreview$setWorld(World world);
}
