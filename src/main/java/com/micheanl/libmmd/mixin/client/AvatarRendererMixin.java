package com.micheanl.libmmd.mixin.client;

import com.micheanl.libmmd.player.PlayerModels;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(
        method = "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void libmmd$hidePlayerLayers(
        AvatarRenderState state,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (PlayerModels.INSTANCE.isReplaced(state.id)) {
            callback.setReturnValue(false);
        }
    }
}
