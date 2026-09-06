package com.micheanl.libmmd.client.animation;

public record PlayerActionState(
    int tick, String movement, String use, String attack, boolean swinging, float swingProgress,
    int hurtTicks, float yaw, boolean onGround, double verticalSpeed
) {
    public boolean blocksActions() {
        return movement.equals("react_death") || movement.equals("move_sleep") || movement.equals("move_riptide");
    }
}
