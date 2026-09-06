package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxVector3
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HumanoidRigTest {
	@Test
	fun `maps standard Japanese humanoid bones`() {
		val rig = HumanoidRig.from(
			skeleton(
				bone("全ての親", 0f, -1),
				bone("センター", 1f, 0),
				bone("下半身", 2f, 1),
				bone("上半身", 4f, 2),
				bone("上半身2", 6f, 3),
				bone("首", 8f, 4),
				bone("頭", 9f, 5),
				bone("左足", 2f, 2),
				bone("左ひざ", 1f, 7),
				bone("左足首", 0f, 8),
			),
		)

		assertEquals(0, rig[HumanoidJoint.ROOT])
		assertEquals(6, rig[HumanoidJoint.HEAD])
		assertEquals(9f, rig.height, 0.0001f)
		assertEquals(1f, rig.length(HumanoidJoint.NECK), 0.0001f)
		assertVector(Vector3f(0f, -1f, 0f), rig.direction(HumanoidJoint.LEFT_UPPER_LEG))
	}

	@Test
	fun `maps namespace prefixed English bones and rejects helpers`() {
		val rig = HumanoidRig.from(
			skeleton(
				bone("mixamorig:Hips", 0f, -1),
				bone("mixamorig:LeftArmTwist", 1f, 0),
				bone("mixamorig:LeftArm", 2f, 0),
				bone("mixamorig:LeftForeArm", 3f, 2),
				bone("mixamorig:LeftHand", 4f, 3),
			),
		)

		assertEquals(2, rig[HumanoidJoint.LEFT_UPPER_ARM])
		assertEquals(3, rig[HumanoidJoint.LEFT_LOWER_ARM])
		assertEquals(4, rig[HumanoidJoint.LEFT_HAND])
		assertTrue(rig.height > 0f)
	}

	@Test
	fun `does not map upper and lower leg to the same generic name`() {
		val rig = HumanoidRig.from(skeleton(bone("LeftLeg", 0f, -1)))

		assertEquals(0, rig[HumanoidJoint.LEFT_UPPER_LEG])
		assertNull(rig[HumanoidJoint.LEFT_LOWER_LEG])
	}

	@Test
	fun `resolves semantic motion track names`() {
		assertEquals(HumanoidJoint.LEFT_LOWER_ARM, HumanoidJoint.resolve("mixamorig:LeftForeArm"))
		assertEquals(HumanoidJoint.RIGHT_FOOT_IK, HumanoidJoint.resolve("右足IK"))
		assertNull(HumanoidJoint.resolve("スカート前"))
	}

	@Test
	fun `applies explicit bone mapping over automatic result`() {
		val skeleton = skeleton(bone("左腕", 0f, -1), bone("CustomArm", 1f, -1))

		val rig = HumanoidRig.from(skeleton, mapOf(HumanoidJoint.LEFT_UPPER_ARM to "CustomArm"))

		assertEquals(1, rig[HumanoidJoint.LEFT_UPPER_ARM])
	}

	private fun skeleton(vararg bones: PmxBone): Skeleton = Skeleton.from(bones.toList())

	private fun bone(name: String, y: Float, parent: Int): PmxBone = PmxBone(
		name = name,
		englishName = name,
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

	private fun assertVector(expected: Vector3f, actual: Vector3f) {
		assertEquals(expected.x, actual.x, 0.0001f)
		assertEquals(expected.y, actual.y, 0.0001f)
		assertEquals(expected.z, actual.z, 0.0001f)
	}
}
