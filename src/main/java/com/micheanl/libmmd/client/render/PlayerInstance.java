package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;
import com.micheanl.libmmd.client.animation.ActionLibrary;
import com.micheanl.libmmd.client.runtime.ClientPhysicsSettings;
import com.micheanl.libmmd.client.animation.PlayerActionController;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class PlayerInstance implements AutoCloseable {
    private final SceneRuntime.ModelInstance instance;
    private final NativeRuntime.Model model;
    private final float modelScale;
    private final float verticalOffset;
    private final GpuTextures textures;
    private final ActionLibrary actions;
    private final ClientPhysicsSettings physicsSettings;
    private int physicsTick;
    private boolean physicsResetPending;
    private double physicsX = Double.NaN;
    private double physicsY;
    private double physicsZ;
    private long previewRevision = -1;
    private long automaticRevision = -1;
    private String baseAction;
    private String upperAction;
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
        float verticalOffset,
        ClientPhysicsSettings physicsSettings
    ) {
        this.instance = scene.createInstance(model);
        this.model = model;
        this.actions = actions;
        this.physicsSettings = physicsSettings;
        this.modelScale = modelScale;
        this.verticalOffset = verticalOffset;
        GpuTextures loadedTextures = null;
        try {
            loadedTextures = GpuTextures.load(model, packPath);
            textures = loadedTextures;
            instance.play(motion, true, 0.0f);
            if (physicsSettings.enabled()) instance.resetPhysics();
        } catch (RuntimeException failure) {
            if (loadedTextures != null) loadedTextures.close();
            instance.close();
            throw failure;
        }
    }

    boolean update(AvatarRenderState state, CameraRenderState camera, PlayerActionController.Playback playback,
                   String previewAction, long revision) {
        animate(playback, previewAction, revision);
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

    void sample(PlayerActionController.Playback playback, String previewAction, long revision,
                int tick, double x, double y, double z) {
        animate(playback, previewAction, revision);
        if (physicsSettings.enabled()) {
            double dx = x - physicsX, dy = y - physicsY, dz = z - physicsZ;
            double distance = physicsSettings.resetDistance();
            if (!Double.isFinite(physicsX) || tick < physicsTick || dx * dx + dy * dy + dz * dz > distance * distance ||
                physicsResetPending) instance.resetPhysics();
            physicsResetPending = false;
        }
        physicsTick = tick;
        physicsX = x;
        physicsY = y;
        physicsZ = z;
    }

    private void animate(PlayerActionController.Playback playback, String previewAction, long revision) {
        boolean wasPreviewing = isPreviewing;
        long previousPreview = previewRevision;
        if (previewAction != null) {
            if (!isPreviewing || previewRevision != revision) {
                instance.stopOverlay(0);
                var definition = actions.catalog().definition(previewAction);
                instance.play(actions.motion(previewAction), definition.looping(), actions.catalog().transitionSeconds());
                previewRevision = revision;
            }
            isPreviewing = true;
        } else if (playback != null) {
            if (isPreviewing || !playback.base().equals(baseAction) ||
                (playback.upperBody() == null && upperAction == null &&
                    !actions.catalog().definition(playback.base()).looping() && automaticRevision != playback.revision())) {
                var definition = actions.catalog().definition(playback.base());
                instance.play(actions.motion(playback.base()), definition.looping(), actions.catalog().transitionSeconds());
                baseAction = playback.base();
            }
            if (isPreviewing || !Objects.equals(playback.upperBody(), upperAction) || automaticRevision != playback.revision()) {
                if (playback.upperBody() == null) instance.stopOverlay(actions.catalog().transitionSeconds());
                else {
                    var definition = actions.catalog().definition(playback.upperBody());
                    instance.playOverlay(actions.upperBodyMotion(playback.upperBody()), definition.looping(), actions.catalog().transitionSeconds());
                }
                upperAction = playback.upperBody();
            }
            automaticRevision = playback.revision();
            isPreviewing = false;
        }
        physicsResetPending |= wasPreviewing != isPreviewing || previousPreview != previewRevision;
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
