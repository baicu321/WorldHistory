package com.cu6.worldhistory.mixin.client;

import com.cu6.worldhistory.client.WorldHistoryClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the player-following camera only while the user explicitly enables historical free preview. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void worldhistory$applyFreePreview(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse,
                                               float partialTick, CallbackInfo callback) {
        if (!WorldHistoryClient.isFreePreview()) return;
        setRotation(WorldHistoryClient.previewYaw(), WorldHistoryClient.previewPitch(), 0.0F);
        setPosition(WorldHistoryClient.previewPosition());
    }
}
