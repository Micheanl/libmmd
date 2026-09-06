package com.micheanl.libmmd.render

import com.micheanl.libmmd.asset.RenderAsset
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.feature.submit.SubmitNode
import org.joml.Matrix4f
import org.joml.Matrix4fc

@Environment(EnvType.CLIENT)
class ModelSubmit(
	val instance: ModelInstance,
	transform: Matrix4fc,
) : SubmitNode {
	val asset: RenderAsset<GpuMesh>
		get() = instance.asset

	val transform: Matrix4f = Matrix4f(transform)

	override fun featureType(): FeatureRendererType<ModelSubmit> = ModelFeatureRenderer.TYPE
}
