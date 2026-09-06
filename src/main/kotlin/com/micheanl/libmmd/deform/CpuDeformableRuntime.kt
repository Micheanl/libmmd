package com.micheanl.libmmd.deform

import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.format.pmx.PmxGeometry
import org.joml.Vector3f
import org.joml.Vector3fc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class DeformableMotionInput(
	val localVelocity: Vector3f = Vector3f(),
	val angularVelocityRadians: Float = 0f,
	val grounded: Boolean = true,
) {
	init {
		require(localVelocity.isFinite)
		require(angularVelocityRadians.isFinite())
	}
}

class CpuDeformableRuntime(
	override val asset: DeformableAsset,
	geometry: PmxGeometry,
	pose: Pose,
	configuration: XpbdConfiguration = XpbdConfiguration(),
) : DeformableRuntime {
	private val animator = DeformableAnimator(asset, geometry)
	private val bodyCollisions = BodyCollisionRig(HumanoidRig.from(pose.skeleton))
	private val solver = XpbdSolver(asset, configuration)
	private val animatedPositions = Array(asset.particles.size) { Vector3f() }
	private val motionCoupling = DeformableMotionCoupling()
	private val scratch = Vector3f()
	private val offsets = ByteBuffer.allocateDirect(asset.vertexCount * VECTOR_BYTES).order(ByteOrder.nativeOrder())
	private var initialized = false

	override val backend: DeformableBackend = DeformableBackend.CPU

	override val interpolationAlpha: Float
		get() = solver.interpolationAlpha

	override val colliderCount: Int
		get() = bodyCollisions.colliderCount

	override fun updateMotion(input: DeformableMotionInput, deltaSeconds: Float) {
		motionCoupling.update(input, deltaSeconds, initialized)
	}

	override fun advance(deltaSeconds: Float, pose: Pose): Int {
		animator.sample(pose, animatedPositions)
		if (!initialized) {
			solver.reset(animatedPositions)
			bodyCollisions.reset()
			initialized = true
		}
		return solver.advance(
			deltaSeconds,
			animatedPositions,
			bodyCollisions.update(pose),
			motionCoupling.acceleration,
		)
	}

	override fun offsetBytes(): ByteBuffer {
		offsets.clear()
		repeat(asset.vertexCount * 4) { offsets.putFloat(0f) }
		if (initialized) {
			asset.particles.forEachIndexed { particleIndex, particle ->
				solver.interpolatedPosition(particleIndex, scratch).sub(animatedPositions[particleIndex])
				val offset = particle.vertexIndex * VECTOR_BYTES
				offsets.putFloat(offset, scratch.x)
				offsets.putFloat(offset + Float.SIZE_BYTES, scratch.y)
				offsets.putFloat(offset + Float.SIZE_BYTES * 2, scratch.z)
			}
		}
		offsets.position(0)
		offsets.limit(offsets.capacity())
		return offsets
	}

	override fun reset(pose: Pose) {
		animator.sample(pose, animatedPositions)
		solver.reset(animatedPositions)
		bodyCollisions.reset()
		motionCoupling.reset()
		initialized = false
	}

	override fun allFinite(): Boolean = solver.allFinite() &&
		asset.particles.indices.all { particle ->
			solver.position(particle, scratch)
			abs(scratch.x) < MAXIMUM_POSITION && abs(scratch.y) < MAXIMUM_POSITION && abs(scratch.z) < MAXIMUM_POSITION
		}

	override fun close() = Unit

	private companion object {
		const val VECTOR_BYTES = 4 * Float.SIZE_BYTES
		const val MAXIMUM_POSITION = 100_000f
	}
}
