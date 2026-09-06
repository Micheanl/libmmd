package com.micheanl.libmmd.animation

class BoneMask private constructor(
	val skeleton: Skeleton,
	private val weights: FloatArray,
) {
	operator fun get(boneIndex: Int): Float = weights[boneIndex]

	companion object {
		fun fullBody(skeleton: Skeleton): BoneMask = BoneMask(skeleton, FloatArray(skeleton.boneCount) { 1f })

		fun upperBody(rig: HumanoidRig): BoneMask {
			val weights = FloatArray(rig.skeleton.boneCount)
			val spine = rig[HumanoidJoint.SPINE]
			if (spine != null) {
				for (index in rig.skeleton.bones.indices) {
					var cursor = index
					while (cursor >= 0) {
						if (cursor == spine) {
							weights[index] = 1f
							break
						}
						cursor = rig.skeleton.bones[cursor].parentIndex
					}
				}
			} else {
				UPPER_JOINTS.mapNotNull(rig::get).forEach { weights[it] = 1f }
			}
			return BoneMask(rig.skeleton, weights)
		}

		private val UPPER_JOINTS = setOf(
			HumanoidJoint.CHEST,
			HumanoidJoint.NECK,
			HumanoidJoint.HEAD,
			HumanoidJoint.LEFT_SHOULDER,
			HumanoidJoint.LEFT_UPPER_ARM,
			HumanoidJoint.LEFT_LOWER_ARM,
			HumanoidJoint.LEFT_HAND,
			HumanoidJoint.RIGHT_SHOULDER,
			HumanoidJoint.RIGHT_UPPER_ARM,
			HumanoidJoint.RIGHT_LOWER_ARM,
			HumanoidJoint.RIGHT_HAND,
		)
	}
}
