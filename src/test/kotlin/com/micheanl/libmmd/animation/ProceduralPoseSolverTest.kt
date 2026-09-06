package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxVector3
import org.joml.Quaternionf
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProceduralPoseSolverTest {
	@Test
	fun `moves planted foot controller to sampled ground`() {
		val skeleton = legSkeleton()
		val pose = Pose(skeleton)
		val solver = ProceduralPoseSolver(HumanoidRig.from(skeleton), 0f)

		solver.apply(
			pose,
			MotionState.IDLE,
			onGround = true,
			deltaSeconds = 1f,
			groundSampler = GroundSampler { GroundSample(1f, Vector3f(0f, 1f, 0f)) },
		)

		assertEquals(1f, pose.translation(5).y, 0.001f)
		assertTrue(solver.contacts.left > 0.99f)
		assertEquals(0f, solver.contacts.right, 0.0001f)
	}

	@Test
	fun `releases foot contact while airborne`() {
		val skeleton = legSkeleton()
		val pose = Pose(skeleton)
		val solver = ProceduralPoseSolver(HumanoidRig.from(skeleton), 0f)
		solver.apply(pose, MotionState.IDLE, true, 1f)

		solver.apply(pose, MotionState.ELYTRA_FLY, false, 1f)

		assertTrue(solver.contacts.left < 0.001f)
	}

	@Test
	fun `clamps extreme elbow rotations`() {
		val skeleton = Skeleton.from(
			listOf(
				bone("左腕", 1f, -1),
				bone("左ひじ", 2f, 0),
				bone("左手首", 3f, 1),
			),
		)
		val pose = Pose(skeleton).setRotation(1, Quaternionf().rotateX(PI.toFloat()))
		val solver = ProceduralPoseSolver(HumanoidRig.from(skeleton), 0f)

		solver.apply(pose, MotionState.IDLE, true, 0f)

		val angle = 2f * kotlin.math.acos(pose.rotation(1).w)
		assertEquals(Math.toRadians(155.0).toFloat(), angle, 0.001f)
	}

	@Test
	fun `warps stride from actual speed and reverses for backward movement`() {
		val skeleton = legSkeleton()
		val pose = Pose(skeleton).setTranslation(5, Vector3f(0f, 1f, 2f))
		val solver = ProceduralPoseSolver(HumanoidRig.from(skeleton), 0f)

		solver.apply(
			pose,
			MotionState.WALK,
			true,
			0f,
			locomotion = LocomotionParameters(0.2f, -0.2f, 0f),
		)

		assertEquals(1.5f, solver.strideScale, 0.0001f)
		assertEquals(-3f, pose.translation(5).z, 0.0001f)
	}

	@Test
	fun `limits orientation warping for side movement`() {
		val skeleton = legSkeleton()
		val pose = Pose(skeleton).setTranslation(5, Vector3f(0f, 1f, 2f))
		val solver = ProceduralPoseSolver(HumanoidRig.from(skeleton), 0f)

		solver.apply(
			pose,
			MotionState.WALK,
			true,
			0f,
			locomotion = LocomotionParameters(0.1f, 0f, 0.1f),
		)

		assertEquals(Math.toRadians(65.0).toFloat(), solver.orientationRadians, 0.0001f)
		assertTrue(pose.translation(5).x > 1f)
	}

	@Test
	fun `keeps a planted foot fixed in world space`() {
		val skeleton = legSkeleton()
		val pose = Pose(skeleton)
		val solver = ProceduralPoseSolver(HumanoidRig.from(skeleton), 0f)
		solver.apply(pose, MotionState.IDLE, true, 1f, modelTransform = Matrix4f())
		assertTrue(solver.locks.left)
		pose.reset()

		solver.apply(
			pose,
			MotionState.IDLE,
			true,
			1f / 20f,
			modelTransform = Matrix4f().translation(1f, 0f, 0f),
		)

		assertEquals(-1f, pose.translation(5).x, 0.001f)
		assertTrue(solver.locks.left)
	}

	private fun legSkeleton(): Skeleton = Skeleton.from(
		listOf(
			bone("センター", 0f, -1),
			bone("下半身", 10f, 0),
			bone("左足", 9f, 1),
			bone("左ひざ", 5f, 2),
			bone("左足首", 0f, 3),
			bone("左足ＩＫ", 0f, 0),
		),
	)

	private fun bone(name: String, y: Float, parent: Int): PmxBone = PmxBone(
		name,
		name,
		PmxVector3(0f, y, 0f),
		parent,
		0,
		PmxBoneFlags(0),
		PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		null,
		null,
		null,
		null,
		null,
	)
}
