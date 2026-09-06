package com.micheanl.libmmd.mixin.client;

import com.micheanl.libmmd.client.bootstrap.ClientBootstrap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
    @Unique private boolean libmmd$submittedHands;

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void libmmd$submitHands(float partialTicks, PoseStack poses, SubmitNodeCollector collector,
                                  PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                                  CallbackInfo callback) {
        libmmd$submittedHands = ClientBootstrap.submitHands(poses, collector, player, hands);
    }

    @Inject(method = "renderPlayerHand", at = @At("HEAD"), cancellable = true)
    private void libmmd$replaceHand(PoseStack poses, SubmitNodeCollector collector, int light,
                                   HumanoidArm arm, PlayerRenderState player, CallbackInfo callback) {
        if (libmmd$submittedHands) callback.cancel();
    }

    @Inject(method = "submitHandsWithItems", at = @At("RETURN"))
    private void libmmd$finishHands(float partialTicks, PoseStack poses, SubmitNodeCollector collector,
                                  PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                                  CallbackInfo callback) {
        libmmd$submittedHands = false;
    }
}
