package com.micheanl.libmmd.client.animation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.SwingAnimationType;

public final class VanillaActionSampler {
    private VanillaActionSampler() {}

    public static PlayerActionState sample(Player player, ActionCatalog catalog, boolean mining) {
        var swing = player.getCurrentSwing();
        var hand = swing == null ? InteractionHand.MAIN_HAND : swing.hand();
        var arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        var weapon = weaponGroup(itemId(player.getItemInHand(hand)));
        var attack = mining ? "interact_mine" : "attack_" + weapon;
        if (!mining && swing != null && swing.animation().type() == SwingAnimationType.STAB &&
            !weapon.equals("spear") && !weapon.equals("trident")) attack = "attack_stab";
        if (!mining && !player.onGround() && player.getDeltaMovement().y < 0) attack = "attack_" + weapon + "_critical";
        if (arm == HumanoidArm.LEFT) attack += "_left";
        String use = null;
        if (player.isUsingItem()) {
            var useArm = player.getUsedItemHand() == InteractionHand.MAIN_HAND
                ? player.getMainArm() : player.getMainArm().getOpposite();
            use = useAction(player.getUseItem().getUseAnimation(), useArm == HumanoidArm.LEFT);
        }
        return new PlayerActionState(player.tickCount, movement(player, catalog), use, attack,
            player.isSwinging(), player.getSwingAnimation(1.0f), player.hurtTime, player.getYRot(),
            player.onGround(), player.getDeltaMovement().y);
    }

    public static String useAction(ItemUseAnimation animation, boolean left) {
        var name = switch (animation) {
            case NONE -> "use_item";
            case EAT -> "use_eat";
            case DRINK -> "use_drink";
            case BLOCK -> "use_block";
            case BOW -> "use_bow";
            case TRIDENT -> "use_trident";
            case CROSSBOW -> "use_crossbow_charge";
            case SPYGLASS -> "use_spyglass";
            case TOOT_HORN -> "use_toot_horn";
            case BRUSH -> "use_brush";
            case BUNDLE -> "use_bundle";
            case SPEAR -> "use_spear";
        };
        return left ? name + "_left" : name;
    }

    public static String weaponGroup(String id) {
        if (id.endsWith("_sword")) return "sword";
        if (id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_hoe")) return "tool";
        if (id.endsWith("_axe")) return "axe";
        if (id.endsWith("_spear")) return "spear";
        if (id.equals("trident")) return "trident";
        if (id.equals("mace")) return "mace";
        return "unarmed";
    }

    private static String movement(Player player, ActionCatalog catalog) {
        if (!player.isAlive()) return "react_death";
        if (player.getPose() == Pose.SLEEPING) return "move_sleep";
        if (player.isAutoSpinAttack()) return "move_riptide";
        if (player.isPassenger()) {
            if (player.getVehicle() instanceof AbstractBoat) return "move_boat";
            if (player.getVehicle() instanceof AbstractMinecart) return "move_minecart";
            return "move_ride";
        }
        if (player.getPose() == Pose.FALL_FLYING) return "move_glide";
        if (player.getPose() == Pose.SWIMMING) return player.isInWater() ? "move_swim" : "move_crawl";
        var velocity = player.getDeltaMovement();
        var threshold = catalog.movementThreshold();
        if (player.onClimbable()) {
            if (Math.abs(velocity.y) < threshold) return "move_climb_idle";
            return velocity.y > 0 ? "move_climb" : "move_climb_down";
        }
        if (player.getAbilities().flying) {
            if (Math.abs(velocity.y) < threshold) return "move_fly";
            return velocity.y > 0 ? "move_fly_up" : "move_fly_down";
        }
        if (!player.onGround() && !player.isInWater()) return velocity.y > 0 ? "move_jump_air" : "move_fall";
        var moving = velocity.x * velocity.x + velocity.z * velocity.z > threshold * threshold;
        if (player.getPose() == Pose.CROUCHING) return moving ? "move_sneak_walk" : "move_sneak_idle";
        if (!moving) return player.isFullyFrozen() ? "react_freeze" : "move_idle";
        if (player.isSprinting()) return "move_run";
        var yaw = Math.toRadians(player.getYRot());
        var forward = -velocity.x * Math.sin(yaw) + velocity.z * Math.cos(yaw);
        var left = velocity.x * Math.cos(yaw) + velocity.z * Math.sin(yaw);
        var sector = Math.floorMod((int) Math.round(Math.atan2(left, forward) / (Math.PI / 4)), 8);
        return switch (sector) {
            case 0 -> "move_walk";
            case 1 -> "move_walk_forward_left";
            case 2 -> "move_walk_left";
            case 3 -> "move_walk_back_left";
            case 4 -> "move_walk_back";
            case 5 -> "move_walk_back_right";
            case 6 -> "move_walk_right";
            default -> "move_walk_forward_right";
        };
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
