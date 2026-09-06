package com.micheanl.libmmd.deform

import com.micheanl.libmmd.animation.HumanoidJoint
import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.Pose
import org.joml.Vector3f

class BodyCollisionRig(
	private val rig: HumanoidRig,
	private val friction: Float = 0.35f,
) {
	private val bindings = createBindings()
	private var initialized = false

	val colliderCount: Int
		get() = bindings.size

	fun update(pose: Pose): List<AnimatedCapsule> {
		require(pose.skeleton === rig.skeleton)
		bindings.forEach { binding ->
			if (initialized) {
				binding.previousStart.set(binding.start)
				binding.previousEnd.set(binding.end)
			}
			pose.globalMatrix(binding.startBone).getTranslation(binding.start)
			pose.globalMatrix(binding.endBone).getTranslation(binding.end)
			if (!initialized) {
				binding.previousStart.set(binding.start)
				binding.previousEnd.set(binding.end)
			}
		}
		initialized = true
		return bindings.map { binding ->
			AnimatedCapsule(
				previousStart = Vector3f(binding.previousStart),
				previousEnd = Vector3f(binding.previousEnd),
				start = Vector3f(binding.start),
				end = Vector3f(binding.end),
				radius = binding.radius,
				friction = friction,
			)
		}
	}

	fun reset() {
		initialized = false
	}

	private fun createBindings(): List<Binding> {
		val height = rig.height
		return CAPSULES.mapNotNull { specification ->
			val start = rig[specification.start] ?: return@mapNotNull null
			val end = rig[specification.end] ?: return@mapNotNull null
			Binding(start, end, height * specification.radiusRatio)
		}
	}

	private data class Binding(
		val startBone: Int,
		val endBone: Int,
		val radius: Float,
		val previousStart: Vector3f = Vector3f(),
		val previousEnd: Vector3f = Vector3f(),
		val start: Vector3f = Vector3f(),
		val end: Vector3f = Vector3f(),
	)

	private data class Specification(
		val start: HumanoidJoint,
		val end: HumanoidJoint,
		val radiusRatio: Float,
	)

	private companion object {
		val CAPSULES = listOf(
			Specification(HumanoidJoint.HIPS, HumanoidJoint.SPINE, 0.105f),
			Specification(HumanoidJoint.SPINE, HumanoidJoint.CHEST, 0.115f),
			Specification(HumanoidJoint.CHEST, HumanoidJoint.NECK, 0.12f),
			Specification(HumanoidJoint.LEFT_UPPER_LEG, HumanoidJoint.RIGHT_UPPER_LEG, 0.09f),
			Specification(HumanoidJoint.LEFT_UPPER_LEG, HumanoidJoint.LEFT_LOWER_LEG, 0.075f),
			Specification(HumanoidJoint.LEFT_LOWER_LEG, HumanoidJoint.LEFT_FOOT, 0.06f),
			Specification(HumanoidJoint.RIGHT_UPPER_LEG, HumanoidJoint.RIGHT_LOWER_LEG, 0.075f),
			Specification(HumanoidJoint.RIGHT_LOWER_LEG, HumanoidJoint.RIGHT_FOOT, 0.06f),
		)
	}
}
