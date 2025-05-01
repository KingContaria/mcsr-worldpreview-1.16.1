package me.voidxwalker.worldpreview;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class WPFakeServerPlayerEntity extends ServerPlayerEntity {
    public WPFakeServerPlayerEntity(MinecraftServer server, ServerWorld world, GameProfile profile, SyncedClientOptions options) {
        super(server, world, profile, options);
    }
}
