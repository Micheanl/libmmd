package com.micheanl.libmmd.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;

import java.util.List;

@Environment(EnvType.CLIENT)
public final class RenderFeature implements FeatureRenderer<RenderNode> {
    @Override
    public void prepareGroup(FeatureFrameContext context, List<RenderNode> nodes, boolean translucent) {
    }

    @Override
    public void executeGroup(FeatureFrameContext context, OitStage stage, RenderPass pass, int groupIndex, List<RenderNode> nodes, boolean translucent) {
        if (nodes.isEmpty()) return;
        pass.setPipeline(RenderSystem.getCompiledPipeline(ModelPipeline.INSTANCE));
        RenderSystem.bindDefaultUniforms(pass);
        for (var node : nodes) {
            var buffers = node.buffers();
            pass.setVertexBuffer(0, buffers.vertices().slice());
            pass.setVertexBuffer(1, buffers.skinning().slice());
            pass.setVertexBuffer(2, buffers.instances().slice());
            pass.setIndexBuffer(buffers.indices(), node.indexStride() == Short.BYTES ? IndexType.SHORT : IndexType.INT);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy()));
            pass.setUniform("BoneTransforms", buffers.matrices());
            pass.setUniform("MorphOffsets", buffers.morphOffsets());
            pass.setUniform("SoftBodyOffsets", buffers.softBodyOffsets());
            pass.setUniform("Sampler0", node.texture().view(), node.texture().sampler());
            pass.drawIndexed(node.indexCount(), node.instanceCount(), node.firstIndex(), 0, 0);
        }
    }
}
