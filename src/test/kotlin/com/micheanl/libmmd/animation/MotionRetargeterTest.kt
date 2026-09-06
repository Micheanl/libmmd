package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxVector3
import com.micheanl.libmmd.format.vmd.VmdBezier
import com.micheanl.libmmd.format.vmd.VmdBoneInterpolation
import com.micheanl.libmmd.format.vmd.VmdBoneKeyframe
import com.micheanl.libmmd.format.vmd.VmdMotion
import com.micheanl.libmmd.format.vmd.VmdQuaternion
import com.micheanl.libmmd.format.vmd.VmdVector3
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals

class MotionRetargeterTest {
	@Test
	fun `binds Japanese tracks to English target bones`() {
		val skeleton = Skeleton.from(listOf(bone("Character_Center", "Center", 0f, -1)))
		val clip = AnimationClip.bind(motion("センター", VmdVector3(2f, 0f, 1f)), HumanoidRig.from(skeleton))
		val pose = Pose(skeleton)

		clip.apply(0f, pose)

		assertEquals(2f, pose.globalMatrix(0).m30(), 0.0001f)
		assertEquals(-1f, pose.globalMatrix(0).m32(), 0.0001f)
		assertEquals(1, clip.boundBoneCount)
	}

	@Test
	fun `scales root motion to target proportions`() {
		val sourceRig = HumanoidRig.from(
			Skeleton.from(listOf(bone("頭", "Head", 10f, -1), bone("左足首", "LeftFoot", 0f, -1))),
		)
		val targetSkeleton = Skeleton.from(
			listOf(bone("センター", "Center", 0f, -1), bone("頭", "Head", 20f, -1), bone("左足首", "LeftFoot", 0f, -1)),
		)
		val targetRig = HumanoidRig.from(targetSkeleton)
		val clip = AnimationClip.bind(motion("センター", VmdVector3(0f, 3f, 0f)), targetRig, RetargetProfile.from(sourceRig))
		val pose = Pose(targetSkeleton)

		clip.apply(0f, pose)

		assertEquals(6f, pose.globalMatrix(0).m31(), 0.0001f)
	}

	private fun bone(name: String, englishName: String, y: Float, parent: Int): PmxBone = PmxBone(
		name = name,
		englishName = englishName,
		position = PmxVector3(0f, y, 0f),
		parentBoneIndex = parent,
		deformLayer = 0,
		flags = PmxBoneFlags(0),
		tail = PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		inheritance = null,
		fixedAxis = null,
		localAxes = null,
		externalParentKey = null,
		inverseKinematics = null,
	)

	private fun motion(boneName: String, translation: VmdVector3): VmdMotion {
		val linear = VmdBezier(0f, 0f, 1f, 1f)
		return VmdMotion(
			"Retarget Test",
			listOf(
				VmdBoneKeyframe(
					boneName,
					0,
					translation,
					VmdQuaternion(0f, 0f, 0f, 1f),
					VmdBoneInterpolation(linear, linear, linear, linear),
				),
			),
			emptyList(),
			emptyList(),
			emptyList(),
			emptyList(),
			emptyList(),
		)
	}
}
