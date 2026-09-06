package com.micheanl.libmmd.mixin.client;

import com.micheanl.libmmd.player.PlayerModels;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD")
    )
    private void libmmd$syncPlayerModel(
        LivingEntityRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        CameraRenderState camera,
        CallbackInfo callback
    ) {
        if (state instanceof AvatarRenderState avatar && PlayerModels.INSTANCE.isReplaced(avatar.id)) {
            PlayerModels.INSTANCE.updateRenderTransform(avatar);
        }
    }

    @Inject(
        method = "getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void libmmd$hidePlayerBody(
        LivingEntityRenderState state,
        boolean bodyVisible,
        boolean translucent,
        boolean glowing,
        CallbackInfoReturnable<RenderType> callback
    ) {
        if (state instanceof AvatarRenderState avatar && PlayerModels.INSTANCE.isReplaced(avatar.id)) {
            callback.setReturnValue(null);
        }
    }
}
