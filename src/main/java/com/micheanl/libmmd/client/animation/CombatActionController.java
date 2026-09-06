package com.micheanl.libmmd.client.animation;

public final class CombatActionController {
    private final int resetTicks;
    private String previousWeapon;
    private boolean wasAirborne;
    private int previousTick;
    private int combo;

    public CombatActionController(int resetTicks) {
        this.resetTicks = resetTicks;
    }

    public String attack(String weapon, boolean airborne, int tick) {
        if (!weapon.equals(previousWeapon) || airborne != wasAirborne || tick < previousTick ||
            (long) tick - previousTick > resetTicks) combo = 0;
        var action = "combat_" + weapon + (airborne ? "_air_" : "_light_") + (combo + 1);
        combo = (combo + 1) % (airborne ? 2 : 3);
        previousWeapon = weapon;
        wasAirborne = airborne;
        previousTick = tick;
        return action;
    }
}
