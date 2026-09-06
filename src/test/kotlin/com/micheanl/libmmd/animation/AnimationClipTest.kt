package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxIkLink
import com.micheanl.libmmd.format.pmx.PmxInverseKinematics
import com.micheanl.libmmd.format.pmx.PmxVector3
import com.micheanl.libmmd.format.vmd.VmdBezier
import com.micheanl.libmmd.format.vmd.VmdBoneInterpolation
import com.micheanl.libmmd.format.vmd.VmdBoneKeyframe
import com.micheanl.libmmd.format.vmd.VmdMotion
import com.micheanl.libmmd.format.vmd.VmdIkKeyframe
import com.micheanl.libmmd.format.vmd.VmdIkState
import com.micheanl.libmmd.format.vmd.VmdQuaternion
import com.micheanl.libmmd.format.vmd.VmdVector3
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimationClipTest {
	private val linear = VmdBezier(0f, 0f, 1f, 1f)
	private val interpolation = VmdBoneInterpolation(linear, linear, linear, linear)

	@Test
	fun `binds Japanese bone names and converts coordinates`() {
		val skeleton = skeleton()
		val clip = AnimationClip.bind(
			motion(
				frame(0, VmdVector3(0f, 0f, 2f)),
				frame(30, VmdVector3(10f, 20f, 4f)),
			),
			skeleton,
		)
		val pose = Pose(skeleton)

		clip.apply(0.5f, pose)
		val position = pose.matrix(0).transformPosition(Vector3f())

		assertEquals(5f, position.x, 0.0001f)
		assertEquals(10f, position.y, 0.0001f)
		assertEquals(-3f, position.z, 0.0001f)
		assertEquals(1, clip.boundBoneCount)
		assertEquals(1f, clip.durationSeconds)
	}

	@Test
	fun `interpolates quaternion rotation`() {
		val skeleton = skeleton()
		val clip = AnimationClip.bind(
			motion(
				frame(0, rotation = VmdQuaternion(0f, 0f, 0f, 1f)),
				frame(30, rotation = VmdQuaternion(0f, 0f, 1f, 0f)),
			),
			skeleton,
		)
		val pose = Pose(skeleton)

		clip.apply(0.5f, pose)
		val direction = pose.matrix(0).transformDirection(Vector3f(1f, 0f, 0f))

		assertEquals(0f, direction.x, 0.0001f)
		assertEquals(1f, direction.y, 0.0001f)
	}

	@Test
	fun `animator controls playback and looping`() {
		val skeleton = skeleton()
		val clip = AnimationClip.bind(motion(frame(0), frame(30, VmdVector3(30f, 0f, 0f))), skeleton)
		val animator = Animator(clip, Pose(skeleton)).play()

		animator.update(0.25f)
		assertEquals(0.25f, animator.timeSeconds)
		animator.pause().update(0.25f)
		assertEquals(0.25f, animator.timeSeconds)

		animator.looping = false
		animator.play().update(1f)
		assertEquals(1f, animator.timeSeconds)
		assertFalse(animator.isPlaying)

		animator.looping = true
		animator.play(restart = true).update(1.25f)
		assertEquals(0.25f, animator.timeSeconds)
		assertTrue(animator.isPlaying)
	}

	@Test
	fun `applies discrete VMD IK enable states`() {
		val skeleton = ikSkeleton()
		val clip = AnimationClip.bind(
			motion().copy(
				ikKeyframes = listOf(
					VmdIkKeyframe(0, true, listOf(VmdIkState("controller", false))),
					VmdIkKeyframe(30, true, listOf(VmdIkState("controller", true))),
				),
			),
			skeleton,
		)
		val pose = Pose(skeleton)

		clip.apply(0.5f, pose)
		assertEquals(1f, pose.globalMatrix(2).m30(), 0.0001f)
		assertEquals(0f, pose.globalMatrix(2).m31(), 0.0001f)

		clip.apply(1f, pose)
		assertEquals(0f, pose.globalMatrix(2).m30(), 0.0001f)
		assertEquals(1f, pose.globalMatrix(2).m31(), 0.0001f)
	}

	@Test
	fun `solves cubic Bezier timing`() {
		assertEquals(0.5f, BezierCurve.LINEAR.transform(0.5f), 0.0001f)
		assertTrue(BezierCurve(0.8f, 0f, 1f, 0.2f).transform(0.5f) < 0.2f)
	}

	private fun skeleton(): Skeleton = Skeleton.from(listOf(bone("センター", "Center", PmxVector3(0f, 0f, 0f), -1)))

	private fun ikSkeleton(): Skeleton = Skeleton.from(
		listOf(
			bone("root", "root", PmxVector3(0f, 0f, 0f), -1),
			bone("link", "link", PmxVector3(0f, 0f, 0f), 0),
			bone("effector", "effector", PmxVector3(1f, 0f, 0f), 1),
			bone(
				"controller",
				"controller",
				PmxVector3(0f, 1f, 0f),
				0,
				flags = PmxBoneFlags(1 shl 5),
				inverseKinematics = PmxInverseKinematics(
					targetBoneIndex = 2,
					iterationCount = 8,
					angleLimit = (PI / 2.0).toFloat(),
					links = listOf(PmxIkLink(1, null)),
				),
			),
		),
	)

	private fun bone(
		name: String,
		englishName: String,
		position: PmxVector3,
		parentIndex: Int,
		flags: PmxBoneFlags = PmxBoneFlags(0),
		inverseKinematics: PmxInverseKinematics? = null,
	): PmxBone = PmxBone(
		name = name,
		englishName = englishName,
		position = position,
		parentBoneIndex = parentIndex,
		deformLayer = 0,
		flags = flags,
		tail = PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		inheritance = null,
		fixedAxis = null,
		localAxes = null,
		externalParentKey = null,
		inverseKinematics = inverseKinematics,
	)

	private fun motion(vararg frames: VmdBoneKeyframe): VmdMotion = VmdMotion(
		modelName = "Test",
		boneKeyframes = frames.toList(),
		morphKeyframes = emptyList(),
		cameraKeyframes = emptyList(),
		lightKeyframes = emptyList(),
		shadowKeyframes = emptyList(),
		ikKeyframes = emptyList(),
	)

	private fun frame(
		frame: Int,
		translation: VmdVector3 = VmdVector3(0f, 0f, 0f),
		rotation: VmdQuaternion = VmdQuaternion(0f, 0f, 0f, 1f),
	): VmdBoneKeyframe = VmdBoneKeyframe("センター", frame, translation, rotation, interpolation)
}
