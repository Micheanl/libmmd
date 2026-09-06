package com.micheanl.libmmd.deform

import com.micheanl.libmmd.animation.Pose
import java.nio.ByteBuffer

enum class DeformableBackend {
	CPU,
	OPENGL_COMPUTE,
	VULKAN_COMPUTE,
}

interface DeformableRuntime : AutoCloseable {
	val asset: DeformableAsset
	val backend: DeformableBackend
	val interpolationAlpha: Float
	val colliderCount: Int

	fun updateMotion(input: DeformableMotionInput, deltaSeconds: Float)

	fun advance(deltaSeconds: Float, pose: Pose): Int

	fun offsetBytes(): ByteBuffer?

	fun reset(pose: Pose)

	fun allFinite(): Boolean

	override fun close()
}
