package me.voidxwalker.worldpreview.mixin.access;

import net.minecraft.client.network.ClientDynamicRegistryType;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.CombinedDynamicRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayNetworkHandler.class)
public interface ClientPlayNetworkHandlerAccessor {
    @Accessor("world")
    void worldpreview$setWorld(ClientWorld world);

    @Accessor("combinedDynamicRegistries")
    CombinedDynamicRegistries<ClientDynamicRegistryType> standardsettings$getCombinedDynamicRegistries();

    @Accessor("combinedDynamicRegistries")
    void standardsettings$setCombinedDynamicRegistries(CombinedDynamicRegistries<ClientDynamicRegistryType> registries);
}
