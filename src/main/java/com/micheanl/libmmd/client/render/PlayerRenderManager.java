package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.client.model.ModelController;
import com.micheanl.libmmd.runtime.SceneRuntime;
import com.micheanl.libmmd.client.animation.PlayerActionState;
import com.micheanl.libmmd.client.animation.VanillaActionSampler;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Environment(EnvType.CLIENT)
public final class PlayerRenderManager implements AutoCloseable {
    private final SceneRuntime.Scene scene;
    private final ModelController models;
    private final Map<Integer, PlayerInstance> instances = new HashMap<>();
    private final Map<Integer, PlayerActionState> actionStates = new HashMap<>();
    private final Map<Integer, PendingAction> pendingActions = new HashMap<>();
    private long eventRevision;

    public PlayerRenderManager(SceneRuntime.Scene scene, ModelController models) {
        this.scene = scene;
        this.models = models;
    }

    public synchronized boolean submit(
        AvatarRenderState state,
        SubmitNodeCollector collector,
        CameraRenderState camera
    ) {
        if (!models.hasModel() || state.entityType != EntityTypes.PLAYER || state.isInvisibleToPlayer || state.isSpectator) {
            return false;
        }
        if (!(collector instanceof SubmitNodeStorage storage)) return false;
        if (!(storage.order(0) instanceof FabricOrderedSubmitNodeCollector ordered)) return false;
        var instance = instances.computeIfAbsent(
            state.id,
            id -> new PlayerInstance(
                scene,
                models.model(),
                models.motion(),
                models.actions(),
                models.packPath(),
                models.scale(),
                models.verticalOffset()
            )
        );
        if (!instance.update(state, camera, actionStates.get(state.id), models.previewAction(),
            models.previewRevision(), pendingActions.get(state.id))) return false;
        for (var node : instance.nodes()) ordered.submitCustom(SubmitRenderPhases.SOLID, node);
        return true;
    }

    public synchronized void clear() {
        for (var instance : instances.values()) instance.close();
        instances.clear();
        actionStates.clear();
        pendingActions.clear();
    }

    public synchronized void sample(Player player, boolean mining) {
        if (!models.hasModel()) return;
        actionStates.put(player.getId(), VanillaActionSampler.sample(player, models.actions().catalog(), mining));
    }

    public synchronized void trigger(Player player, String action, InteractionHand hand) {
        if (!models.hasModel()) return;
        var arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        var resolved = arm == HumanoidArm.LEFT ? action + "_left" : action;
        models.actions().catalog().definition(resolved);
        pendingActions.put(player.getId(), new PendingAction(resolved, ++eventRevision));
    }

    public synchronized void retain(Set<Integer> activeIds) {
        instances.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) return false;
            entry.getValue().close();
            actionStates.remove(entry.getKey());
            pendingActions.remove(entry.getKey());
            return true;
        });
    }

    @Override
    public synchronized void close() {
        clear();
    }

    record PendingAction(String action, long revision) {}
}
