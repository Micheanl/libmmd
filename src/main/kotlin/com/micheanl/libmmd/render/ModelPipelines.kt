package com.micheanl.libmmd.render

import com.micheanl.libmmd.Engine
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines

@Environment(EnvType.CLIENT)
object ModelPipelines {
	private val deformation = BindGroupLayout.builder()
		.withUniform("BoneTransforms", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
		.withUniform("MorphOffsets", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
		.withUniform("SoftBodyOffsets", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
		.build()

	val untextured: RenderPipeline = create("model_untextured", false)
	val textured: RenderPipeline = create("model_textured", true)

	fun register() = Unit

	private fun create(name: String, textured: Boolean): RenderPipeline {
		var builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Engine.id("pipeline/$name"))
			.withVertexShader(Engine.id("core/model"))
			.withFragmentShader(Engine.id("core/$name"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(deformation)
			.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withCull(false)
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)
			.withVertexBinding(1, ModelVertexFormats.skinning)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		if (textured) {
			builder = builder.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
		}
		return RenderPipelines.register(builder.build())
	}
}
