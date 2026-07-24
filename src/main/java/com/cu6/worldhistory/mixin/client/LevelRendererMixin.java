package com.cu6.worldhistory.mixin.client;

import com.cu6.worldhistory.client.WorldHistoryClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides live chunk geometry while the historical replacement is being rendered. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void worldhistory$skipLiveVisibility(Camera camera, Frustum frustum, boolean captureFrustum, boolean spectator,
                                                  CallbackInfo callback) {
        if (WorldHistoryClient.isViewingHistory()) callback.cancel();
    }

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void worldhistory$skipLiveSectionCompilation(Camera camera, CallbackInfo callback) {
        if (WorldHistoryClient.isViewingHistory()) callback.cancel();
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void worldhistory$hideLiveSections(RenderType renderType, double cameraX, double cameraY, double cameraZ,
                                                 Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
        if (WorldHistoryClient.isViewingHistory()) callback.cancel();
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void worldhistory$hideLiveEntities(Entity entity, double cameraX, double cameraY, double cameraZ,
                                                float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                                                CallbackInfo callback) {
        if (WorldHistoryClient.isViewingHistory()) callback.cancel();
    }
}
