package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.client.model.ModelController;
import com.micheanl.libmmd.runtime.SceneRuntime;
import com.micheanl.libmmd.client.runtime.ClientNativeRuntime;
import com.micheanl.libmmd.client.runtime.ClientPhysicsSettings;
import com.micheanl.libmmd.client.animation.PlayerActionController;
import com.micheanl.libmmd.client.animation.CombatActionController;
import com.micheanl.libmmd.client.animation.VanillaActionSampler;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.core.component.DataComponents;
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
    private final SceneRuntime.Scene firstPersonScene;
    private final ClientPhysicsSettings settings;
    private final ModelController models;
    private final Map<Integer, PlayerInstance> instances = new HashMap<>();
    private final Map<Integer, PlayerActionController> controllers = new HashMap<>();
    private final Map<Integer, PlayerActionController.Playback> playbacks = new HashMap<>();
    private final Map<Integer, CombatActionController> combat = new HashMap<>();
    private final Map<Integer, PendingAction> pendingActions = new HashMap<>();
    private FirstPersonInstance firstPerson;
    private int firstPersonPlayerId;
    private ArmMesh.Selection armMesh;

    public PlayerRenderManager(ClientNativeRuntime runtime, ModelController models) {
        this.scene = runtime.scene();
        this.firstPersonScene = runtime.firstPersonScene();
        this.settings = runtime.settings();
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
                models.verticalOffset(),
                settings
            )
        );
        if (!instance.update(state, camera, playbacks.get(state.id), models.previewAction(),
            models.previewRevision())) return false;
        for (var node : instance.nodes()) ordered.submitCustom(SubmitRenderPhases.SOLID, node);
        return true;
    }

    public synchronized boolean submitHands(PoseStack poses,
        SubmitNodeCollector collector, PlayerRenderState player,
        FirstPersonHandsAndItemsRenderState hands) {
        var avatar = player.avatarRenderState;
        if (!models.hasModel() || avatar == null || avatar.isInvisible || avatar.isSpectator ||
            hands.isScoping || hands.handRenderSelection == null) return false;
        if (hands.mainHandItem.has(DataComponents.MAP_ID) ||
            hands.offHandItem.has(DataComponents.MAP_ID)) return false;
        if (!(collector instanceof SubmitNodeStorage storage) ||
            !(storage.order(0) instanceof FabricOrderedSubmitNodeCollector ordered)) return false;
        if (armMesh == null) armMesh = ArmMesh.select(models.model());
        if (!Float.isFinite(armMesh.eyeHeight()) ||
            armMesh.ranges().stream().noneMatch(ArmMesh.Range::left) ||
            armMesh.ranges().stream().allMatch(ArmMesh.Range::left)) return false;
        if (firstPerson == null || firstPersonPlayerId != avatar.id) {
            if (firstPerson != null) firstPerson.close();
            firstPerson = new FirstPersonInstance(firstPersonScene, models, armMesh);
            firstPersonPlayerId = avatar.id;
            firstPerson.update(playbacks.get(avatar.id));
        }
        return firstPerson.submit(poses, ordered, hands, avatar.mainArm);
    }

    public synchronized void validateModel() {
        try (var validation = scene.createInstance(models.model())) {
            validation.renderPacket();
        } catch (RuntimeException failure) {
            models.unload();
            throw failure;
        }
    }

    public synchronized void clear() {
        if (firstPerson != null) firstPerson.close();
        firstPerson = null;
        armMesh = null;
        for (var instance : instances.values()) instance.close();
        instances.clear();
        controllers.clear();
        playbacks.clear();
        combat.clear();
        pendingActions.clear();
    }

    public synchronized void sample(Player player, boolean mining) {
        if (!models.hasModel()) return;
        var catalog = models.actions().catalog();
        var controller = controllers.computeIfAbsent(player.getId(), id -> new PlayerActionController(catalog));
        var event = pendingActions.remove(player.getId());
        if (event != null) controller.event(event.action(), event.tick());
        playbacks.put(player.getId(), controller.update(VanillaActionSampler.sample(player, catalog, mining)));
        var body = instances.get(player.getId());
        if (body != null) body.sample(playbacks.get(player.getId()), models.previewAction(), models.previewRevision(),
            player.tickCount, player.getX(), player.getY(), player.getZ());
        if (firstPerson != null && firstPersonPlayerId == player.getId()) firstPerson.update(playbacks.get(player.getId()));
    }

    public synchronized void attack(Player player) {
        if (!models.hasModel()) return;
        var controller = combat.computeIfAbsent(player.getId(),
            id -> new CombatActionController(models.actions().catalog().comboResetTicks()));
        trigger(player, controller.attack(VanillaActionSampler.weaponGroup(VanillaActionSampler.itemId(player.getMainHandItem())),
            !player.onGround(), player.tickCount), InteractionHand.MAIN_HAND);
    }

    public synchronized void trigger(Player player, String action, InteractionHand hand) {
        if (!models.hasModel()) return;
        var arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        var resolved = arm == HumanoidArm.LEFT ? action + "_left" : action;
        models.actions().catalog().definition(resolved);
        pendingActions.put(player.getId(), new PendingAction(resolved, player.tickCount));
    }

    public synchronized void retain(Set<Integer> activeIds) {
        if (firstPerson != null && !activeIds.contains(firstPersonPlayerId)) {
            firstPerson.close();
            firstPerson = null;
        }
        controllers.keySet().retainAll(activeIds);
        playbacks.keySet().retainAll(activeIds);
        combat.keySet().retainAll(activeIds);
        pendingActions.keySet().retainAll(activeIds);
        instances.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) return false;
            entry.getValue().close();
            pendingActions.remove(entry.getKey());
            return true;
        });
    }

    @Override
    public synchronized void close() {
        clear();
    }

    private record PendingAction(String action, int tick) {}
}
