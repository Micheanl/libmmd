package com.micheanl.libmmd.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.BatchableSubmit;

@Environment(EnvType.CLIENT)
public record RenderNode(
    GpuBuffers buffers,
    GpuTextures.Binding texture,
    int indexCount,
    int indexStride,
    int firstIndex,
    int instanceCount
) implements BatchableSubmit {
    public static final FeatureRendererType<RenderNode> TYPE = FeatureRendererType.create("libmmd");

    public RenderNode(GpuBuffers buffers, GpuTextures.Binding texture, int indexCount, int indexStride) {
        this(buffers, texture, indexCount, indexStride, 0, 1);
    }

    @Override
    public FeatureRendererType<RenderNode> featureType() {
        return TYPE;
    }

    @Override
    public Object batchKey() {
        return new BatchKey(buffers, texture);
    }

    private record BatchKey(GpuBuffers buffers, GpuTextures.Binding texture) {}
}
