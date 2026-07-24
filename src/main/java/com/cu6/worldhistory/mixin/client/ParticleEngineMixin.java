package com.cu6.worldhistory.mixin.client;

import com.cu6.worldhistory.client.WorldHistoryClient;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/** Prevents live-world particles from leaking into an immutable historical view. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At("HEAD"), cancellable = true)
    private void worldhistory$hideLiveParticles(LightTexture lightTexture, Camera camera, float partialTick, Frustum frustum,
                                                Predicate<ParticleRenderType> renderTypePredicate, CallbackInfo callback) {
        if (WorldHistoryClient.isViewingHistory()) callback.cancel();
    }
}
