package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;
import com.micheanl.libmmd.client.animation.ActionLibrary;
import com.micheanl.libmmd.client.animation.PlayerActionController;
import com.micheanl.libmmd.client.animation.PlayerActionState;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.nio.file.Path;
import java.util.List;

final class PlayerInstance implements AutoCloseable {
    private final SceneRuntime.ModelInstance instance;
    private final NativeRuntime.Model model;
    private final float modelScale;
    private final float verticalOffset;
    private final GpuTextures textures;
    private final NativeRuntime.Motion defaultMotion;
    private final ActionLibrary actions;
    private final PlayerActionController actionController;
    private long previewRevision = -1;
    private long automaticRevision = -1;
    private long eventRevision = -1;
    private boolean isPreviewing;
    private GpuBuffers buffers;
    private List<RenderNode> nodes = List.of();

    PlayerInstance(
        SceneRuntime.Scene scene,
        NativeRuntime.Model model,
        NativeRuntime.Motion motion,
        ActionLibrary actions,
        Path packPath,
        float modelScale,
        float verticalOffset
    ) {
        this.instance = scene.createInstance(model);
        this.model = model;
        this.defaultMotion = motion;
        this.actions = actions;
        this.actionController = new PlayerActionController(actions.catalog());
        this.modelScale = modelScale;
        this.verticalOffset = verticalOffset;
        GpuTextures loadedTextures = null;
        try {
            loadedTextures = GpuTextures.load(model, packPath);
            textures = loadedTextures;
            instance.play(motion, true, 0.0f);
        } catch (RuntimeException failure) {
            if (loadedTextures != null) loadedTextures.close();
            instance.close();
            throw failure;
        }
    }

    boolean update(AvatarRenderState state, CameraRenderState camera, PlayerActionState actionState,
                   String previewAction, long revision, PlayerRenderManager.PendingAction event) {
        String selected;
        long selectedRevision;
        if (previewAction != null) {
            selected = previewAction;
            selectedRevision = revision;
            isPreviewing = true;
        } else if (actionState != null) {
            if (event != null && event.revision() != eventRevision) {
                actionController.event(event.action(), actionState.tick());
                eventRevision = event.revision();
            }
            var playback = actionController.update(actionState);
            selected = playback.action();
            selectedRevision = playback.revision();
            if (isPreviewing) automaticRevision = -1;
            isPreviewing = false;
        } else {
            selected = null;
            selectedRevision = revision;
        }
        var needsPlayback = selected != null && (isPreviewing
            ? previewRevision != selectedRevision
            : automaticRevision != selectedRevision);
        if (needsPlayback) {
            var action = actions.catalog().definition(selected);
            instance.play(actions.motion(selected), action.looping(), actions.catalog().transitionSeconds());
            if (isPreviewing) previewRevision = selectedRevision;
            else automaticRevision = selectedRevision;
        } else if (selected == null && (isPreviewing || previewRevision != revision)) {
            instance.play(defaultMotion, true, actions.catalog().transitionSeconds());
            previewRevision = revision;
            isPreviewing = false;
        }
        var yaw = (float) Math.toRadians(-state.bodyRot);
        var halfYaw = yaw * 0.5f;
        instance.setTransform(new SceneRuntime.Transform(
            new NativeRuntime.Vector3(
                (float) (state.x - camera.pos.x),
                (float) (state.y - camera.pos.y + verticalOffset),
                (float) (state.z - camera.pos.z)
            ),
            new NativeRuntime.Quaternion(0.0f, (float) Math.sin(halfYaw), 0.0f, (float) Math.cos(halfYaw)),
            new NativeRuntime.Vector3(
                state.scale * modelScale,
                state.scale * modelScale,
                state.scale * modelScale
            )
        ));
        var packet = instance.renderPacket();
        if (!packet.visible()) {
            nodes = List.of();
            return false;
        }
        if (buffers == null) {
            buffers = GpuBuffers.upload(packet);
            nodes = RenderNodeFactory.create(packet, model, buffers, textures);
        } else {
            buffers.updateMatrices(packet);
        }
        return true;
    }

    List<RenderNode> nodes() {
        return nodes;
    }

    @Override
    public void close() {
        try {
            if (buffers != null) {
                buffers.close();
                buffers = null;
            }
        } finally {
            try {
                textures.close();
            } finally {
                instance.close();
            }
        }
    }

}
