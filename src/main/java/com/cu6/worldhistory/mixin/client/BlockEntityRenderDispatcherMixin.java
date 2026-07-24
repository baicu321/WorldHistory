package com.cu6.worldhistory.mixin.client;

import com.cu6.worldhistory.client.WorldHistoryClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides live block-entity renderers, which run after the regular terrain section pass. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void worldhistory$hideLiveBlockEntities(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
                                                    MultiBufferSource buffers, CallbackInfo callback) {
        if (WorldHistoryClient.isViewingHistory()) callback.cancel();
    }
}
