package com.micheanl.libmmd.render

import com.micheanl.libmmd.format.pmx.PmxMaterial
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.RenderPass
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.FeatureRenderer
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.oit.OitStage
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier
import org.joml.Matrix4f

@Environment(EnvType.CLIENT)
class ModelFeatureRenderer : FeatureRenderer<ModelSubmit> {
	private data class Draw(
		val mesh: GpuMesh,
		val firstIndex: Int,
		val indexCount: Int,
		val transform: GpuBufferSlice,
		val texture: AbstractTexture?,
		val instance: ModelInstance,
	)

	private val groups = ArrayList<List<Draw>>()

	override fun prepareGroup(
		context: FeatureFrameContext,
		submits: List<ModelSubmit>,
		strictlyOrdered: Boolean,
	) {
		val draws = ArrayList<Draw>()
		for (submit in submits) {
			val source = submit.asset.source
			val modelView = RenderSystem.getModelViewMatrixCopy().mul(submit.transform)
			for ((materialIndex, material) in source.model.materials.withIndex()) {
				draws += prepareDraw(
					context,
					submit.asset.renderData,
					source.textureIds,
					materialIndex,
					material,
					modelView,
					submit.instance,
				)
			}
		}
		groups += draws
	}

	override fun executeGroup(
		context: FeatureFrameContext,
		stage: OitStage?,
		renderPass: RenderPass,
		groupIndex: Int,
		submits: List<ModelSubmit>,
		strictlyOrdered: Boolean,
	) {
		renderPass.pushDebugGroup { "MMD models" }
		try {
			RenderSystem.bindDefaultUniforms(renderPass)
			for (draw in groups[groupIndex]) {
				renderPass.setPipeline(
					RenderSystem.getCompiledPipeline(
						if (draw.texture == null) ModelPipelines.untextured else ModelPipelines.textured,
					),
				)
				renderPass.setUniform("DynamicTransforms", draw.transform)
				draw.texture?.let { texture ->
					renderPass.setUniform("Sampler0", texture.textureView, texture.sampler)
				}
				draw.instance.bindDeformation(renderPass)
				draw.mesh.draw(renderPass, draw.firstIndex, draw.indexCount)
			}
		} finally {
			renderPass.popDebugGroup()
		}
	}

	override fun finishExecute(context: FeatureFrameContext) {
		groups.clear()
	}

	private fun prepareDraw(
		context: FeatureFrameContext,
		mesh: GpuMesh,
		textureIds: List<Identifier>,
		materialIndex: Int,
		material: PmxMaterial,
		modelView: Matrix4f,
		instance: ModelInstance,
	): Draw {
		val texture = textureIds.getOrNull(material.textureIndex)
			?.let(context.textureManager()::getTexture)
		val color = instance.morphWeights.materialColor(materialIndex, texture != null)
		val transform = RenderSystem.getDynamicUniforms().writeTransform(
			modelView,
			color,
		)
		return Draw(mesh, material.firstIndex, material.indexCount, transform, texture, instance)
	}

	companion object {
		val TYPE: FeatureRendererType<ModelSubmit> = FeatureRendererType.create("libmmd:model")
	}
}
