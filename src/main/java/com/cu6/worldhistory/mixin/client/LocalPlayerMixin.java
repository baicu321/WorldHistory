package com.cu6.worldhistory.mixin.client;

import com.cu6.worldhistory.client.WorldHistoryClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents the live local player from moving or dropping items while the camera explores history. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void worldhistory$freezePlayerMovement(MoverType type, Vec3 movement, CallbackInfo callback) {
        if (WorldHistoryClient.isFreePreview()) callback.cancel();
    }

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventItemDrop(boolean fullStack, CallbackInfoReturnable<Boolean> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(false);
    }
}
