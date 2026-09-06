package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.client.animation.PlayerActionController;
import com.micheanl.libmmd.client.model.ModelController;
import com.micheanl.libmmd.runtime.SceneRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class FirstPersonInstance implements AutoCloseable {
    private final SceneRuntime.ModelInstance instance;
    private final ModelController models;
    private final ArmMesh.Selection mesh;
    private final GpuTextures textures;
    private final GpuBuffers buffers;
    private final ByteBuffer transform = ByteBuffer.allocateDirect(16 * Float.BYTES).order(ByteOrder.nativeOrder());
    private String selected;
    private long revision = -1;

    FirstPersonInstance(SceneRuntime.Scene scene, ModelController models, ArmMesh.Selection mesh) {
        this.models = models;
        this.mesh = mesh;
        instance = scene.createInstance(models.model());
        GpuTextures loadedTextures = null;
        try {
            loadedTextures = GpuTextures.load(models.model(), models.packPath());
            textures = loadedTextures;
            instance.play(models.actions().motion("move_idle"), true, 0);
            buffers = GpuBuffers.upload(instance.renderPacket(), mesh.indices());
        } catch (RuntimeException failure) {
            if (loadedTextures != null) loadedTextures.close();
            instance.close();
            throw failure;
        }
    }

    void update(PlayerActionController.Playback playback) {
        var action = playback == null ? "fp_use_item" : "fp_" + playback.action();
        var nextRevision = playback == null ? 0 : playback.revision();
        if (!models.actions().catalog().contains(action)) {
            action = "fp_use_item";
            nextRevision = 0;
        }
        if (!action.equals(selected) || nextRevision != revision) {
            var definition = models.actions().catalog().definition(action);
            instance.play(models.actions().motion(action), definition.looping(), models.actions().catalog().transitionSeconds());
            selected = action;
            revision = nextRevision;
        }
    }

    boolean submit(PoseStack poses, FabricOrderedSubmitNodeCollector collector,
                   FirstPersonHandsAndItemsRenderState hands, HumanoidArm mainArm) {
        var matrix = new Matrix4f(poses.last().pose())
            .translate(0, -mesh.eyeHeight() * models.scale(), 0)
            .rotateY((float) Math.PI).scale(models.scale());
        matrix.get(0, transform);
        var packet = instance.renderPacket();
        buffers.updateMatrices(packet, transform);
        boolean submitted = false;
        for (var range : mesh.ranges()) {
            boolean main = range.left() == (mainArm == HumanoidArm.LEFT);
            if (main ? !hands.handRenderSelection.renderMainHand : !hands.handRenderSelection.renderOffHand) continue;
            collector.submitCustom(SubmitRenderPhases.SOLID, new RenderNode(buffers, textures.get(range.texture()),
                range.count(), packet.indexStride(), range.first(), 1));
            submitted = true;
        }
        return submitted;
    }

    @Override
    public void close() {
        try { buffers.close(); }
        finally {
            try { textures.close(); }
            finally { instance.close(); }
        }
    }
}
