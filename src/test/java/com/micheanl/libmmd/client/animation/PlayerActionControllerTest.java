package com.micheanl.libmmd.client.animation;

import net.minecraft.world.item.ItemUseAnimation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerActionControllerTest {
    private static PlayerActionState state(int tick, String movement, String use, String attack,
                                           boolean swinging, float progress, int hurt, float yaw,
                                           boolean onGround, double verticalSpeed) {
        return new PlayerActionState(tick, movement, use, attack, swinging, progress, hurt, yaw, onGround, verticalSpeed);
    }

    @Test
    void mapsEveryItemUseAnimationForBothArms() {
        var catalog = ActionCatalog.bundled();
        for (var animation : ItemUseAnimation.values()) {
            for (var left : new boolean[] {false, true}) {
                var action = VanillaActionSampler.useAction(animation, left);
                assertTrue(catalog.names().contains(action), animation + " -> " + action);
            }
        }
    }

    @Test
    void mapsAllVanillaWeaponGroupsForBothArms() {
        var catalog = ActionCatalog.bundled();
        var ids = Set.of("wooden_sword", "stone_axe", "iron_spear", "diamond_pickaxe", "copper_shovel",
            "netherite_hoe", "trident", "mace", "air", "stick");
        for (var id : ids) {
            var group = VanillaActionSampler.weaponGroup(id);
            for (var suffix : new String[] {"", "_left", "_critical", "_critical_left"}) {
                assertTrue(catalog.names().contains("attack_" + group + suffix), id + suffix);
            }
        }
    }

    @Test
    void selectsMovementUseAttackReactionAndTransitionsWithoutRestartingLoops() {
        var controller = new PlayerActionController(ActionCatalog.bundled());
        var first = controller.update(state(0, "move_idle", null, "attack_unarmed", false, 0, 0, 0, true, 0));
        var second = controller.update(state(1, "move_idle", null, "attack_unarmed", false, 0, 0, 1, true, 0));
        assertEquals("move_idle", first.action());
        assertEquals(first.revision(), second.revision());
        assertEquals("move_start", controller.update(state(2, "move_walk", null,
            "attack_unarmed", false, 0, 0, 1, true, 0)).action());
        assertEquals("move_walk", controller.update(state(20, "move_walk", null,
            "attack_unarmed", false, 0, 0, 1, true, 0)).action());
        var attack = controller.update(state(21, "move_walk", null, "attack_sword_left", true, 0.05f, 0, 1, true, 0));
        assertEquals("attack_sword_left", attack.action());
        assertEquals(attack.revision(), controller.update(state(22, "move_walk", null,
            "attack_sword_left", true, 0.3f, 0, 1, true, 0)).revision());
        assertEquals("use_bow", controller.update(state(40, "move_idle", "use_bow",
            "attack_sword", false, 0, 0, 1, true, 0)).action());
        assertEquals("react_hurt", controller.update(state(41, "move_idle", null,
            "attack_sword", false, 0, 10, 1, true, 0)).action());
        assertEquals("react_death", controller.update(state(42, "react_death", null,
            "attack_sword", false, 0, 0, 1, true, 0)).action());
    }

    @Test
    void handlesJumpLandingRideTurnAndAcceptedInteractionPrecedence() {
        var controller = new PlayerActionController(ActionCatalog.bundled());
        controller.update(state(0, "move_idle", null, "attack_unarmed", false, 0, 0, 0, true, 0));
        assertEquals("move_jump_start", controller.update(state(1, "move_jump_air", null,
            "attack_unarmed", false, 0, 0, 0, false, 0.4)).action());
        assertEquals("move_jump_land", controller.update(state(20, "move_idle", null,
            "attack_unarmed", false, 0, 0, 0, true, 0)).action());
        assertEquals("move_mount", controller.update(state(40, "move_ride", null,
            "attack_unarmed", false, 0, 0, 0, false, 0)).action());
        assertEquals("move_dismount", controller.update(state(60, "move_idle", null,
            "attack_unarmed", false, 0, 0, 0, true, 0)).action());
        controller.update(state(80, "move_idle", null, "attack_unarmed", false, 0, 0, 0, true, 0));
        assertEquals("move_turn_right", controller.update(state(81, "move_idle", null,
            "attack_unarmed", false, 0, 0, 25, true, 0)).action());
        controller.event("interact_place", 100);
        assertEquals("interact_place", controller.update(state(100, "move_idle", null,
            "attack_unarmed", true, 0, 0, 25, true, 0)).action());
    }
}
