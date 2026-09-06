package com.micheanl.libmmd.client.animation;

import java.util.Objects;

public final class PlayerActionController {
    private static final float GAME_TICKS_PER_SECOND = 20.0f;
    private static final float VMD_FRAMES_PER_SECOND = 30.0f;
    private final ActionCatalog catalog;
    private PlayerActionState previous;
    private String selected = "move_idle";
    private int eventEndTick;
    private long revision;
    private int lastEventTick = Integer.MIN_VALUE;

    public PlayerActionController(ActionCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Playback update(PlayerActionState state) {
        if (previous != null && state.tick() < previous.tick()) previous = null;
        var attackStarted = state.swinging() && (previous == null || !previous.swinging() ||
            state.swingProgress() < previous.swingProgress() || !state.attack().equals(previous.attack()));
        var hurtStarted = state.hurtTicks() > 0 && (previous == null || state.hurtTicks() > previous.hurtTicks());
        if (state.blocksActions()) {
            choose(state.movement(), false);
            eventEndTick = state.tick();
        } else if (lastEventTick == state.tick()) {
            // An accepted interaction takes precedence over its accompanying hand swing.
        } else if (hurtStarted) {
            event("react_hurt", state.tick());
        } else if (attackStarted && state.use() == null) {
            event(state.attack(), state.tick());
        } else if (state.use() != null) {
            choose(state.use(), false);
            eventEndTick = state.tick();
        } else if (state.tick() >= eventEndTick) {
            var transition = transition(state);
            if (transition != null) event(transition, state.tick());
            else choose(state.movement(), false);
        }
        previous = state;
        return new Playback(selected, revision);
    }

    public Playback event(String action, int tick) {
        choose(action, true);
        lastEventTick = tick;
        eventEndTick = tick + Math.max(1, (int) Math.ceil(
            catalog.definition(action).durationFrames() * GAME_TICKS_PER_SECOND / VMD_FRAMES_PER_SECOND));
        return new Playback(selected, revision);
    }

    private String transition(PlayerActionState state) {
        if (previous == null) return null;
        if (state.movement().startsWith("move_jump") && previous.onGround() && !state.onGround()) return "move_jump_start";
        if (state.onGround() && !previous.onGround() &&
            (previous.movement().equals("move_fall") || previous.movement().equals("move_jump_air"))) return "move_jump_land";
        if (state.movement().equals("move_ride") && !previous.movement().equals("move_ride")) return "move_mount";
        if (previous.movement().equals("move_ride") && !state.movement().equals("move_ride")) return "move_dismount";
        if (state.movement().equals("move_walk") && previous.movement().equals("move_idle")) return "move_start";
        if (state.movement().equals("move_idle") &&
            (previous.movement().startsWith("move_walk") || previous.movement().equals("move_run"))) return "move_stop";
        if (state.movement().equals("move_idle")) {
            var turn = (state.yaw() - previous.yaw() + 540.0f) % 360.0f - 180.0f;
            if (Math.abs(turn) >= catalog.turnThresholdDegrees()) return turn < 0 ? "move_turn_left" : "move_turn_right";
        }
        return null;
    }

    private void choose(String action, boolean restart) {
        catalog.definition(action);
        if (restart || !selected.equals(action)) {
            selected = action;
            revision++;
        }
    }

    public record Playback(String action, long revision) {}
}
