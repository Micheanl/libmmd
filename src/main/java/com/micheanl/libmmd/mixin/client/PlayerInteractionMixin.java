package com.micheanl.libmmd.mixin.client;

import com.micheanl.libmmd.client.animation.InteractionActions;
import com.micheanl.libmmd.client.bootstrap.ClientBootstrap;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class PlayerInteractionMixin {
    @Unique private String libmmd$pendingItemAction;
    @Unique private boolean libmmd$hadDropItem;

    @Inject(method = "useItem", at = @At("HEAD"))
    private void libmmd$captureItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        libmmd$pendingItemAction = InteractionActions.item(player, player.getItemInHand(hand));
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void libmmd$itemUsed(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        if (callback.getReturnValue().consumesAction() && !player.isUsingItem()) {
            ClientBootstrap.triggerAction(player, libmmd$pendingItemAction, hand);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void libmmd$captureBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                    CallbackInfoReturnable<InteractionResult> callback) {
        libmmd$pendingItemAction = InteractionActions.item(player, player.getItemInHand(hand));
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void libmmd$blockUsed(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                 CallbackInfoReturnable<InteractionResult> callback) {
        if (callback.getReturnValue().consumesAction() && !player.isUsingItem()) {
            ClientBootstrap.triggerAction(player, libmmd$pendingItemAction, hand);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void libmmd$captureEntity(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
                                     CallbackInfoReturnable<InteractionResult> callback) {
        libmmd$pendingItemAction = InteractionActions.item(player, player.getItemInHand(hand));
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void libmmd$entityUsed(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
                                  CallbackInfoReturnable<InteractionResult> callback) {
        if (callback.getReturnValue().consumesAction()) {
            ClientBootstrap.triggerAction(player, libmmd$pendingItemAction, hand);
        }
    }

    @Inject(method = "dropItem", at = @At("HEAD"))
    private void libmmd$beforeDrop(LocalPlayer player, boolean all, CallbackInfo callback) {
        libmmd$hadDropItem = !player.getMainHandItem().isEmpty();
    }

    @Inject(method = "dropItem", at = @At("RETURN"))
    private void libmmd$dropped(LocalPlayer player, boolean all, CallbackInfo callback) {
        if (libmmd$hadDropItem) ClientBootstrap.triggerAction(player, "interact_drop", InteractionHand.MAIN_HAND);
    }

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void libmmd$containerInput(int containerId, int slot, int button, ContainerInput input,
                                      Player player, CallbackInfo callback) {
        if (player.containerMenu.containerId == containerId) {
            ClientBootstrap.triggerAction(player, input == ContainerInput.THROW ? "interact_drop" : "interact_inventory",
                InteractionHand.MAIN_HAND);
        }
    }
}
