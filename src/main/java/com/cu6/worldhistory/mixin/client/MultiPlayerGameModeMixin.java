package com.cu6.worldhistory.mixin.client;

import com.cu6.worldhistory.client.WorldHistoryClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks live-world actions at the client game-mode boundary during historical free preview. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventDestroyBlock(BlockPos position, CallbackInfoReturnable<Boolean> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(false);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventStartDestroyBlock(BlockPos position, Direction direction,
                                                        CallbackInfoReturnable<Boolean> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventContinueDestroyBlock(BlockPos position, Direction direction,
                                                           CallbackInfoReturnable<Boolean> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(false);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult result,
                                                CallbackInfoReturnable<InteractionResult> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(InteractionResult.PASS);
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventUseItem(Player player, InteractionHand hand,
                                              CallbackInfoReturnable<InteractionResult> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(InteractionResult.PASS);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventAttack(Player player, Entity target, CallbackInfo callback) {
        if (WorldHistoryClient.isFreePreview()) callback.cancel();
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventEntityInteraction(Player player, Entity target, InteractionHand hand,
                                                        CallbackInfoReturnable<InteractionResult> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(InteractionResult.PASS);
    }

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventEntityInteractionAt(Player player, Entity target, EntityHitResult result,
                                                          InteractionHand hand,
                                                          CallbackInfoReturnable<InteractionResult> callback) {
        if (WorldHistoryClient.isFreePreview()) callback.setReturnValue(InteractionResult.PASS);
    }

    @Inject(method = "handlePickItem", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventPickItem(int slot, CallbackInfo callback) {
        if (WorldHistoryClient.isFreePreview()) callback.cancel();
    }

    @Inject(method = "handleCreativeModeItemDrop", at = @At("HEAD"), cancellable = true)
    private void worldhistory$preventCreativeItemDrop(net.minecraft.world.item.ItemStack stack, CallbackInfo callback) {
        if (WorldHistoryClient.isFreePreview()) callback.cancel();
    }
}
