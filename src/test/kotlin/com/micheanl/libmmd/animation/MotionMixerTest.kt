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

class MotionMixerTest {
	@Test
	fun `crossfades motion changes without snapping`() {
		val skeleton = Skeleton.from(listOf(bone()))
		val pose = Pose(skeleton)
		val mixer = MotionMixer(
			pose,
			mapOf(
				MotionState.IDLE to clip(skeleton, 0f),
				MotionState.WALK to clip(skeleton, 10f),
			),
			transitionSeconds = 0.2f,
		)

		mixer.update(0f)
		mixer.select(MotionState.WALK)
		mixer.update(0f)
		assertEquals(0f, position(pose), 0.0001f)

		mixer.update(0.1f)
		assertEquals(5f, position(pose), 0.0001f)

		mixer.update(0.1f)
		assertEquals(10f, position(pose), 0.0001f)
	}

	private fun position(pose: Pose): Float = pose.globalMatrix(0).getTranslation(Vector3f()).x

	private fun clip(skeleton: Skeleton, x: Float): AnimationClip {
		val linear = VmdBezier(0f, 0f, 1f, 1f)
		val frame = VmdBoneKeyframe(
			boneName = "root",
			frame = 0,
			translation = VmdVector3(x, 0f, 0f),
			rotation = VmdQuaternion(0f, 0f, 0f, 1f),
			interpolation = VmdBoneInterpolation(linear, linear, linear, linear),
		)
		return AnimationClip.bind(
			VmdMotion("Test", listOf(frame), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
			skeleton,
		)
	}

	private fun bone() = PmxBone(
		name = "root",
		englishName = "root",
		position = PmxVector3(0f, 0f, 0f),
		parentBoneIndex = -1,
		deformLayer = 0,
		flags = PmxBoneFlags(0),
		tail = PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		inheritance = null,
		fixedAxis = null,
		localAxes = null,
		externalParentKey = null,
		inverseKinematics = null,
	)
}
