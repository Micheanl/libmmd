package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxMaterialMorphOperation
import org.joml.Quaternionf
import org.joml.Vector4f
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MorphWeights(val set: MorphSet) {
	private val weights = FloatArray(set.targetCount)
	private val composedWeights = FloatArray(set.targetCount)
	private val resolvedWeights = FloatArray(set.targetCount)
	private val vertexOffsets = FloatArray(Math.multiplyExact(set.vertexCount, COMPONENTS_PER_VERTEX))
	private val materialColors = FloatArray(Math.multiplyExact(set.materialCount, COMPONENTS_PER_MATERIAL))
	private val bytes = ByteBuffer.allocateDirect(Math.multiplyExact(vertexOffsets.size, Float.SIZE_BYTES))
		.order(ByteOrder.nativeOrder())
	private var dirty = true

	internal var needsUpload: Boolean = true
		private set

	operator fun get(targetIndex: Int): Float = weights[targetIndex]

	fun set(targetIndex: Int, weight: Float): MorphWeights = apply {
		require(targetIndex in weights.indices) { "targetIndex is outside morph targets" }
		require(weight.isFinite()) { "Morph weight must be finite" }
		if (weights[targetIndex] != weight) {
			weights[targetIndex] = weight
			dirty = true
			needsUpload = true
		}
	}

	fun reset(): MorphWeights = apply {
		if (weights.any { it != 0f }) {
			weights.fill(0f)
			dirty = true
			needsUpload = true
		}
	}

	fun materialColor(materialIndex: Int, textured: Boolean, destination: Vector4f = Vector4f()): Vector4f {
		require(materialIndex in 0 until set.materialCount) { "materialIndex is outside materials" }
		evaluate()
		val offset = materialIndex * COMPONENTS_PER_MATERIAL
		val textureOffset = offset + COLOR_COMPONENTS
		return destination.set(
			materialColors[offset] * if (textured) materialColors[textureOffset] else 1f,
			materialColors[offset + 1] * if (textured) materialColors[textureOffset + 1] else 1f,
			materialColors[offset + 2] * if (textured) materialColors[textureOffset + 2] else 1f,
			materialColors[offset + 3] * if (textured) materialColors[textureOffset + 3] else 1f,
		)
	}

	internal fun vertexOffsetBytes(): ByteBuffer {
		evaluate()
		needsUpload = false
		return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
	}

	internal fun applyBones(pose: Pose) {
		require(pose.skeleton.boneCount == set.boneCount) { "Pose and morph set use different skeleton sizes" }
		evaluate()
		val rotation = Quaternionf()
		set.targets.forEachIndexed { targetIndex, target ->
			val weight = resolvedWeights[targetIndex]
			if (weight == 0f) return@forEachIndexed
			target.boneDeltas.forEach { delta ->
				pose.addTranslation(delta.boneIndex, delta.x * weight, delta.y * weight, delta.z * weight)
				rotation.identity().slerp(delta.rotation, weight)
				pose.multiplyRotation(delta.boneIndex, rotation)
			}
		}
	}

	private fun evaluate() {
		if (!dirty) return
		set.resolve(weights, composedWeights, resolvedWeights)
		vertexOffsets.fill(0f)
		resetMaterials()
		set.targets.forEachIndexed { targetIndex, target ->
			val weight = resolvedWeights[targetIndex]
			if (weight == 0f) return@forEachIndexed
			applyVertexDeltas(target, weight)
			applyUvDeltas(target, weight)
			target.materialDeltas.forEach { applyMaterialDelta(it, weight) }
		}
		bytes.clear()
		vertexOffsets.forEach(bytes::putFloat)
		bytes.flip()
		dirty = false
	}

	private fun applyVertexDeltas(target: MorphTarget, weight: Float) {
		target.vertexDeltas.forEach { delta ->
			val offset = delta.vertexIndex * COMPONENTS_PER_VERTEX
			vertexOffsets[offset] += delta.x * weight
			vertexOffsets[offset + 1] += delta.y * weight
			vertexOffsets[offset + 2] += delta.z * weight
		}
	}

	private fun applyUvDeltas(target: MorphTarget, weight: Float) {
		target.uvDeltas.forEach { delta ->
			val offset = delta.vertexIndex * COMPONENTS_PER_VERTEX
			vertexOffsets[offset + 3] += delta.x * weight
			vertexOffsets[offset + 4] += delta.y * weight
		}
	}

	private fun resetMaterials() {
		set.materials.forEachIndexed { index, material ->
			val offset = index * COMPONENTS_PER_MATERIAL
			materialColors[offset] = material.diffuse.red
			materialColors[offset + 1] = material.diffuse.green
			materialColors[offset + 2] = material.diffuse.blue
			materialColors[offset + 3] = material.diffuse.alpha
			repeat(COLOR_COMPONENTS) { materialColors[offset + COLOR_COMPONENTS + it] = 1f }
		}
	}

	private fun applyMaterialDelta(delta: MaterialDelta, weight: Float) {
		val indices = if (delta.materialIndex < 0) set.materials.indices else delta.materialIndex..delta.materialIndex
		for (materialIndex in indices) {
			val offset = materialIndex * COMPONENTS_PER_MATERIAL
			applyColor(offset, delta.diffuse.red, delta.diffuse.green, delta.diffuse.blue, delta.diffuse.alpha, weight, delta.operation)
			applyColor(
				offset + COLOR_COMPONENTS,
				delta.textureTint.red,
				delta.textureTint.green,
				delta.textureTint.blue,
				delta.textureTint.alpha,
				weight,
				delta.operation,
			)
		}
	}

	private fun applyColor(
		offset: Int,
		red: Float,
		green: Float,
		blue: Float,
		alpha: Float,
		weight: Float,
		operation: PmxMaterialMorphOperation,
	) {
		materialColors[offset] = applyComponent(materialColors[offset], red, weight, operation)
		materialColors[offset + 1] = applyComponent(materialColors[offset + 1], green, weight, operation)
		materialColors[offset + 2] = applyComponent(materialColors[offset + 2], blue, weight, operation)
		materialColors[offset + 3] = applyComponent(materialColors[offset + 3], alpha, weight, operation)
	}

	private fun applyComponent(
		current: Float,
		value: Float,
		weight: Float,
		operation: PmxMaterialMorphOperation,
	): Float = when (operation) {
		PmxMaterialMorphOperation.ADDITIVE -> current + value * weight
		PmxMaterialMorphOperation.MULTIPLY -> current * (1f + (value - 1f) * weight)
	}

	private companion object {
		const val COLOR_COMPONENTS = 4
		const val COMPONENTS_PER_MATERIAL = 8
		const val COMPONENTS_PER_VERTEX = 8
	}
}
