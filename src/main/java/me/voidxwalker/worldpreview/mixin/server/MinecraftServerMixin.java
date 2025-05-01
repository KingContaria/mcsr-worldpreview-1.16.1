package me.voidxwalker.worldpreview.mixin.server;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.voidxwalker.worldpreview.WorldPreview;
import me.voidxwalker.worldpreview.interfaces.WPMinecraftServer;
import me.voidxwalker.worldpreview.interfaces.WPServerChunkLoadingManager;
import me.voidxwalker.worldpreview.mixin.access.ServerWorldAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ApiServices;
import net.minecraft.util.Unit;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Debug(export = true)
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements WPMinecraftServer {

    @Shadow
    @Final
    private static Logger LOGGER;
    @Shadow
    @Final
    protected ApiServices apiServices;
    @Shadow
    private boolean stopped;

    @Shadow
    public abstract void shutdown();
    @Shadow
    public abstract void exit();

    @Unique
    protected volatile boolean killed;
    @Unique
    private volatile boolean tooLateToKill;
    @Unique
    private boolean shouldConfigurePreview;

    @ModifyExpressionValue(
            method = "createWorlds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/ServerWorldProperties;isInitialized()Z"
            )
    )
    private boolean setShouldConfigurePreview(boolean isInitialized) {
        this.shouldConfigurePreview = !isInitialized || WorldPreview.START_ON_OLD_WORLDS;
        return isInitialized;
    }

    @ModifyVariable(
            method = "prepareStartRegion",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkManager;getTotalChunksLoadedCount()I"
            )
    )
    private ServerWorld configureWorldPreview(ServerWorld serverWorld) {
        if (this.shouldConfigurePreview && !this.killed) {
            if (WorldPreview.configure(serverWorld)) {
                this.shouldConfigurePreview = false;
                ((WPServerChunkLoadingManager) serverWorld.getChunkManager().chunkLoadingManager).worldpreview$sendData();
            }
        }
        return serverWorld;
    }

    @Inject(
            method = "prepareStartRegion",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkManager;getTotalChunksLoadedCount()I",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void killWorldGen(WorldGenerationProgressListener worldGenerationProgressListener, CallbackInfo ci, @Local ServerWorld serverWorld) {
        if (this.killed) {
            serverWorld.getChunkManager().removeTicket(ChunkTicketType.START, new ChunkPos(serverWorld.getSpawnPos()), ((ServerWorldAccessor) serverWorld).worldpreview$getSpawnChunkRadius(), Unit.INSTANCE);
            worldGenerationProgressListener.stop();
            ci.cancel();
        }
    }

    @ModifyReturnValue(
            method = "shouldKeepTicking",
            at = @At("RETURN")
    )
    private boolean killRunningTasks(boolean shouldKeepTicking) {
        return shouldKeepTicking && !this.killed;
    }

    @Inject(
            method = "runServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;setupServer()Z",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private synchronized void killServer(CallbackInfo ci) {
        this.tooLateToKill = true;
        if (this.killed) {
            ci.cancel();

            // the try-finally block doesn't run after cancelling,
            // so we have to copy it here
            try {
                this.stopped = true;
                this.shutdown();
            } catch (Throwable var42) {
                LOGGER.error("Exception stopping the server", var42);
            } finally {
                if (this.apiServices.userCache() != null) {
                    this.apiServices.userCache().clearExecutor();
                }
                this.exit();
            }
        }
    }

    @WrapWithCondition(
            method = "runServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;setCrashReport(Lnet/minecraft/util/crash/CrashReport;)V",
                    ordinal = 0
            )
    )
    private boolean doNotSetCrashReport(MinecraftServer server, CrashReport report) {
        return !this.killed;
    }

    @ModifyExpressionValue(
            method = "shutdown",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Stream;anyMatch(Ljava/util/function/Predicate;)Z"
            )
    )
    private boolean doNotDelayShutdown(boolean shouldDelayShutdown) {
        return shouldDelayShutdown && !this.killed;
    }

    @WrapWithCondition(
            method = "shutdown",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;save(ZZZ)Z"
            )
    )
    private boolean doNotSave(MinecraftServer server, boolean suppressLogs, boolean flush, boolean force) {
        return !this.killed;
    }

    @WrapOperation(
            method = "shutdown",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;close()V"
            )
    )
    private void doNotCloseWorld(ServerWorld world, Operation<Void> original) throws IOException {
        if (this.killed) {
            world.getChunkManager().chunkLoadingManager.close();
        } else {
            original.call(world);
        }
    }

    @Override
    public synchronized boolean worldpreview$kill() {
        if (this.tooLateToKill) {
            return false;
        }
        return this.killed = true;
    }
}
