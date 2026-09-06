package com.micheanl.libmmd.player

import com.micheanl.libmmd.animation.MotionState
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.animation.Skeleton
import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxVector3
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class PoseDynamicsTest {
	@Test
	fun `smoothly distributes acceleration bending through the torso`() {
		val skeleton = skeleton()
		val pose = Pose(skeleton)
		val dynamics = PoseDynamics(skeleton)
		dynamics.apply(pose, MotionState.IDLE, 0f, 0f, 0f, 0f, 0f, 0f, true, STEP)

		repeat(20) {
			pose.reset()
			dynamics.apply(pose, MotionState.SPRINT, 0f, 0f, 0f, 0f, 0f, 0.3f, true, STEP)
		}
		val bentDirection = pose.globalMatrix(3).transformDirection(Vector3f(0f, 1f, 0f))
		assertTrue(bentDirection.z > 0.05f)
		assertTrue(bentDirection.isFinite)

		repeat(60) {
			pose.reset()
			dynamics.apply(pose, MotionState.IDLE, 0f, 0f, 0f, 0f, 0f, 0f, true, STEP)
		}
		val settledDirection = pose.globalMatrix(3).transformDirection(Vector3f(0f, 1f, 0f))
		assertTrue(abs(settledDirection.z) < abs(bentDirection.z) * 0.1f)
	}

	@Test
	fun `absorbs landing velocity without discontinuity`() {
		val skeleton = skeleton()
		val pose = Pose(skeleton)
		val dynamics = PoseDynamics(skeleton)
		dynamics.apply(pose, MotionState.IDLE, 0f, 0f, 0f, 0f, -1f, 0f, false, STEP)
		pose.reset()

		dynamics.apply(pose, MotionState.IDLE, 0f, 0f, 0f, 0f, 0f, 0f, true, STEP)

		val landingOffset = pose.globalMatrix(0).m31()
		assertTrue(landingOffset in -0.12f..0f)
		assertTrue(pose.globalMatrix(3).isFinite)
	}

	private fun skeleton(): Skeleton = Skeleton.from(
		listOf(
			bone("センター", "Center", 0f, -1),
			bone("下半身", "LowerBody", 0.7f, 0),
			bone("上半身", "UpperBody", 1f, 1),
			bone("上半身2", "UpperBody2", 1.35f, 2),
			bone("首", "Neck", 1.6f, 3),
			bone("頭", "Head", 1.75f, 4),
		),
	)

	private fun bone(name: String, englishName: String, y: Float, parentIndex: Int): PmxBone = PmxBone(
		name = name,
		englishName = englishName,
		position = PmxVector3(0f, y, 0f),
		parentBoneIndex = parentIndex,
		deformLayer = 0,
		flags = PmxBoneFlags(0),
		tail = PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		inheritance = null,
		fixedAxis = null,
		localAxes = null,
		externalParentKey = null,
		inverseKinematics = null,
	)

	private companion object {
		const val STEP = 1f / 20f
	}
}
