package com.micheanl.libmmd.client.animation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

public final class InteractionActions {
    private InteractionActions() {}

    public static String item(Player player, ItemStack stack) {
        var id = VanillaActionSampler.itemId(stack);
        if (id.equals("fishing_rod")) return player.fishing == null ? "interact_fish_cast" : "interact_fish_reel";
        if (id.endsWith("bucket")) return "interact_bucket";
        if (id.equals("flint_and_steel") || id.equals("fire_charge")) return "interact_ignite";
        if (id.equals("written_book") || id.equals("writable_book")) return "interact_book";
        if (id.equals("shears")) return "interact_shear";
        if (id.equals("lead")) return "interact_leash";
        if (id.equals("snowball") || id.equals("egg") || id.equals("ender_pearl") ||
            id.equals("experience_bottle") || id.equals("splash_potion") || id.equals("lingering_potion")) return "interact_throw";
        return stack.getItem() instanceof BlockItem ? "interact_place" : "interact_interact";
    }
}
