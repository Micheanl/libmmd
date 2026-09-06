package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;

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
    private GpuBuffers buffers;
    private List<RenderNode> nodes = List.of();

    PlayerInstance(
        SceneRuntime.Scene scene,
        NativeRuntime.Model model,
        NativeRuntime.Motion motion,
        Path packPath,
        float modelScale,
        float verticalOffset
    ) {
        this.instance = scene.createInstance(model);
        this.model = model;
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

    boolean update(AvatarRenderState state, CameraRenderState camera) {
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
