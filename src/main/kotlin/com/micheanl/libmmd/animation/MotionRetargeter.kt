package com.micheanl.libmmd.animation

import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import org.joml.Vector3fc

class RetargetProfile private constructor(
	val height: Float,
	private val orientations: Map<HumanoidJoint, Quaternionf>,
) {
	init {
		require(height.isFinite() && height > 0f) { "Retarget profile height must be finite and positive" }
	}

	internal fun orientation(joint: HumanoidJoint, destination: Quaternionf): Quaternionf =
		destination.set(orientations[joint] ?: IDENTITY)

	companion object {
		fun from(rig: HumanoidRig): RetargetProfile {
			val orientations = HumanoidJoint.entries.mapNotNull { joint ->
				val canonical = canonicalDirection(joint) ?: return@mapNotNull null
				val target = rig.direction(joint)
				if (target.lengthSquared() <= 1e-8f) return@mapNotNull null
				joint to Quaternionf().rotationTo(canonical, target)
			}.toMap()
			return RetargetProfile(rig.height, orientations)
		}

		fun standardMmd(height: Float = 16f): RetargetProfile = RetargetProfile(height, emptyMap())

		private fun canonicalDirection(joint: HumanoidJoint): Vector3f? = when (joint) {
			HumanoidJoint.ROOT,
			HumanoidJoint.CENTER,
			HumanoidJoint.HIPS,
			HumanoidJoint.SPINE,
			HumanoidJoint.CHEST,
			HumanoidJoint.NECK,
			-> Vector3f(0f, 1f, 0f)

			HumanoidJoint.LEFT_SHOULDER,
			HumanoidJoint.LEFT_UPPER_ARM,
			HumanoidJoint.LEFT_LOWER_ARM,
			-> Vector3f(1f, 0f, 0f)

			HumanoidJoint.RIGHT_SHOULDER,
			HumanoidJoint.RIGHT_UPPER_ARM,
			HumanoidJoint.RIGHT_LOWER_ARM,
			-> Vector3f(-1f, 0f, 0f)

			HumanoidJoint.LEFT_UPPER_LEG,
			HumanoidJoint.LEFT_LOWER_LEG,
			HumanoidJoint.RIGHT_UPPER_LEG,
			HumanoidJoint.RIGHT_LOWER_LEG,
			-> Vector3f(0f, -1f, 0f)

			HumanoidJoint.LEFT_FOOT,
			HumanoidJoint.RIGHT_FOOT,
			-> Vector3f(0f, 0f, -1f)

			else -> null
		}

		private val IDENTITY = Quaternionf()
	}
}

class MotionRetargeter(
	val targetRig: HumanoidRig,
	private val sourceProfile: RetargetProfile? = null,
) {
	private val targetProfile = sourceProfile?.let { RetargetProfile.from(targetRig) }
	private val translationScale = sourceProfile?.let { targetRig.scaleComparedTo(it.height) } ?: 1f

	internal fun binding(sourceBoneName: String): BoneBinding? {
		val exact = targetRig.skeleton.indexOf(sourceBoneName)
		val joint = HumanoidJoint.resolve(sourceBoneName)
		if (exact != null) return BoneBinding(exact, joint)
		return joint?.let(targetRig::get)?.let { BoneBinding(it, joint) }
	}

	internal fun translation(joint: HumanoidJoint?, source: Vector3fc, destination: Vector3f): Vector3f {
		destination.set(source)
		if (joint != null && joint in TRANSLATION_JOINTS) destination.mul(translationScale)
		return destination
	}

	internal fun rotation(joint: HumanoidJoint?, source: Quaternionfc, destination: Quaternionf): Quaternionf {
		val motionRotation = Quaternionf(source)
		destination.set(motionRotation)
		val sourceProfile = sourceProfile ?: return destination
		val targetProfile = targetProfile ?: return destination
		if (joint == null) return destination
		val sourceOrientation = sourceProfile.orientation(joint, Quaternionf())
		val targetOrientation = targetProfile.orientation(joint, Quaternionf())
		val correction = targetOrientation.mul(sourceOrientation.invert())
		return destination.set(correction).mul(motionRotation).mul(Quaternionf(correction).invert()).normalize()
	}

	internal data class BoneBinding(val boneIndex: Int, val joint: HumanoidJoint?)

	companion object {
		private val TRANSLATION_JOINTS = setOf(
			HumanoidJoint.ROOT,
			HumanoidJoint.CENTER,
			HumanoidJoint.HIPS,
			HumanoidJoint.LEFT_FOOT_IK,
			HumanoidJoint.RIGHT_FOOT_IK,
		)
	}
}
