package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneInheritance
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxIkLink
import com.micheanl.libmmd.format.pmx.PmxInverseKinematics
import com.micheanl.libmmd.format.pmx.PmxVector3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkeletonTest {
	@Test
	fun `evaluates parents before children regardless of source order`() {
		val skeleton = Skeleton.from(
			listOf(
				bone("child", PmxVector3(0f, 1f, 2f), 1),
				bone("root", PmxVector3(0f, 0f, 0f), -1),
			),
		)

		assertEquals(listOf(1, 0), skeleton.evaluationOrder.toList())
		assertEquals(PmxVector3(0f, 1f, -2f), skeleton.bones[0].bindTranslation)
		assertEquals(0, skeleton.indexOf("child"))
	}

	@Test
	fun `produces identity skin matrices in bind pose`() {
		val pose = Pose(Skeleton.from(listOf(bone("root", PmxVector3(2f, 3f, 4f), -1))))
		val matrix = pose.matrix(0)

		assertEquals(1f, matrix.m00())
		assertEquals(1f, matrix.m11())
		assertEquals(1f, matrix.m22())
		assertEquals(0f, matrix.m30())
		assertEquals(0f, matrix.m31())
		assertEquals(0f, matrix.m32())
		val palette = pose.paletteBytes().order(ByteOrder.nativeOrder())
		assertEquals(2 * Pose.MATRIX_BYTES, palette.remaining())
		assertEquals(1f, palette.getFloat(0))
		assertEquals(1f, palette.getFloat(Pose.MATRIX_BYTES))
	}

	@Test
	fun `combines local translation and parent rotation`() {
		val skeleton = Skeleton.from(
			listOf(
				bone("child", PmxVector3(0f, 1f, 0f), 1),
				bone("root", PmxVector3(0f, 0f, 0f), -1),
			),
		)
		val pose = Pose(skeleton)
		pose.setTranslation(0, Vector3f(1f, 0f, 0f))
		val translated = pose.matrix(0).transformPosition(Vector3f(0f, 1f, 0f))
		assertVector(Vector3f(1f, 1f, 0f), translated)

		pose.reset()
		pose.setRotation(1, Quaternionf().rotateZ((PI / 2.0).toFloat()))
		val rotated = pose.matrix(0).transformPosition(Vector3f(0f, 1f, 0f))
		assertVector(Vector3f(-1f, 0f, 0f), rotated)
	}

	@Test
	fun `rejects cyclic bone hierarchy`() {
		assertFailsWith<IllegalArgumentException> {
			Skeleton.from(
				listOf(
					bone("a", PmxVector3(0f, 0f, 0f), 1),
					bone("b", PmxVector3(0f, 1f, 0f), 0),
				),
			)
		}
	}

	@Test
	fun `applies inherited local translation and rotation`() {
		val skeleton = Skeleton.from(
			listOf(
				bone("source", PmxVector3(0f, 0f, 0f), -1),
				bone(
					"inherited",
					PmxVector3(0f, 0f, 0f),
					-1,
					flags = PmxBoneFlags((1 shl 8) or (1 shl 9)),
					inheritance = PmxBoneInheritance(0, 0.5f),
				),
			),
		)
		val pose = Pose(skeleton)
			.setTranslation(0, Vector3f(2f, 0f, 0f))
			.setRotation(0, Quaternionf().rotateZ((PI / 2.0).toFloat()))

		val matrix = pose.globalMatrix(1)
		assertVector(Vector3f(1f, 0f, 0f), matrix.getTranslation(Vector3f()))
		assertVector(Vector3f(0.7071f, 0.7071f, 0f), matrix.transformDirection(Vector3f(1f, 0f, 0f)))
	}

	@Test
	fun `solves and disables CCD inverse kinematics`() {
		val skeleton = Skeleton.from(
			listOf(
				bone("root", PmxVector3(0f, 0f, 0f), -1),
				bone("link", PmxVector3(0f, 0f, 0f), 0),
				bone("effector", PmxVector3(1f, 0f, 0f), 1),
				bone(
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
		val pose = Pose(skeleton)

		assertVector(Vector3f(0f, 1f, 0f), pose.globalMatrix(2).getTranslation(Vector3f()))

		pose.setIkEnabled(3, false)
		assertVector(Vector3f(1f, 0f, 0f), pose.globalMatrix(2).getTranslation(Vector3f()))
	}

	private fun bone(
		name: String,
		position: PmxVector3,
		parentIndex: Int,
		flags: PmxBoneFlags = PmxBoneFlags(0),
		inheritance: PmxBoneInheritance? = null,
		inverseKinematics: PmxInverseKinematics? = null,
	): PmxBone = PmxBone(
		name = name,
		englishName = name,
		position = position,
		parentBoneIndex = parentIndex,
		deformLayer = 0,
		flags = flags,
		tail = PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		inheritance = inheritance,
		fixedAxis = null,
		localAxes = null,
		externalParentKey = null,
		inverseKinematics = inverseKinematics,
	)

	private fun assertVector(expected: Vector3f, actual: Vector3f) {
		assertEquals(expected.x, actual.x, 0.0001f)
		assertEquals(expected.y, actual.y, 0.0001f)
		assertEquals(expected.z, actual.z, 0.0001f)
	}
}
