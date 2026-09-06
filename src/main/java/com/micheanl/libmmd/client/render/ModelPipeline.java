package com.micheanl.libmmd.client.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public final class ModelPipeline {
    public static final RenderPipeline INSTANCE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("libmmd", "pipeline/model"))
        .withVertexShader(Identifier.fromNamespaceAndPath("libmmd", "core/model"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("libmmd", "core/model_textured"))
        .withVertexBinding(0, GpuBuffers.VERTEX_FORMAT)
        .withVertexBinding(1, GpuBuffers.SKINNING_FORMAT)
        .withVertexBinding(2, GpuBuffers.INSTANCE_FORMAT)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("BoneTransforms", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform("MorphOffsets", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform("SoftBodyOffsets", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .build();

    private ModelPipeline() {}
}
