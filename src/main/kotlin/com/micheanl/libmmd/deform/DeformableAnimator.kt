package com.micheanl.libmmd.deform

import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.micheanl.libmmd.format.pmx.PmxSkinning
import org.joml.Matrix4f
import org.joml.Vector3f

class DeformableAnimator(
	private val asset: DeformableAsset,
	private val geometry: PmxGeometry,
) {
	private val matrices = Array(geometry.skinning.boneIndices.maxOrNull()?.plus(1)?.coerceAtLeast(0) ?: 0) { Matrix4f() }
	private val transformed = Vector3f()

	fun sample(pose: Pose, destination: Array<Vector3f> = Array(asset.particles.size) { Vector3f() }): Array<Vector3f> {
		require(destination.size == asset.particles.size)
		for (bone in matrices.indices) {
			if (bone < pose.skeleton.boneCount) pose.matrix(bone, matrices[bone]) else matrices[bone].identity()
		}
		asset.particles.forEachIndexed { particleIndex, particle ->
			val result = destination[particleIndex].zero()
			var weightSum = 0f
			repeat(PmxSkinning.MAX_INFLUENCES) { influence ->
				val bone = geometry.skinning.boneIndex(particle.vertexIndex, influence)
				val weight = geometry.skinning.boneWeight(particle.vertexIndex, influence)
				if (bone !in matrices.indices || !weight.isFinite() || weight <= 0f) return@repeat
				matrices[bone].transformPosition(particle.restPosition, transformed)
				result.fma(weight, transformed)
				weightSum += weight
			}
			if (weightSum > 0f) result.div(weightSum) else result.set(particle.restPosition)
		}
		return destination
	}
}
