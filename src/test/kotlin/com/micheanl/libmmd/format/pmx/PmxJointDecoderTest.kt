package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PmxJointDecoderTest {
	@Test
	fun `reads all joint types and six degree constraints`() {
		val joints = PmxDecoder.readJoints(jointFixture()).joints

		assertEquals(PmxJointType.entries, joints.map(PmxJoint::type))
		val spring = joints.first()
		assertEquals(0, spring.firstRigidBodyIndex)
		assertEquals(1, spring.secondRigidBodyIndex)
		assertEquals(PmxVector3(1f, 2f, 3f), spring.position)
		assertEquals(PmxVector3(0.1f, 0.2f, 0.3f), spring.rotation)
		assertEquals(
			PmxVector3Range(PmxVector3(-1f, -2f, -3f), PmxVector3(1f, 2f, 3f)),
			spring.translationLimits,
		)
		assertEquals(
			PmxVector3Range(PmxVector3(-0.5f, -1f, -1.5f), PmxVector3(0.5f, 1f, 1.5f)),
			spring.rotationLimits,
		)
		assertEquals(PmxVector3(4f, 5f, 6f), spring.translationSpring)
		assertEquals(PmxVector3(7f, 8f, 9f), spring.rotationSpring)
		assertEquals(-1, joints.last().secondRigidBodyIndex)
	}

	@Test
	fun `rejects PMX 21 joint types in PMX 20`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readJoints(jointFixture(version = 2.0f))
		}

		assertTrue(exception.message.orEmpty().contains("SIX_DOF joint requires PMX 2.1"))
	}

	@Test
	fun `rejects joint references outside rigid body table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readJoints(jointFixture(firstJointRigidBodyIndex = 2))
		}

		assertTrue(exception.message.orEmpty().contains("first joint rigid body index 2"))
	}

	@Test
	fun `rejects unknown joint type`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readJoints(jointFixture(firstJointType = 6))
		}

		assertTrue(exception.message.orEmpty().contains("Unsupported joint type 6"))
	}

	@Test
	fun `enforces joint count limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readJoints(jointFixture(), PmxDecodeLimits(maxJoints = 5))
		}

		assertTrue(exception.message.orEmpty().contains("joint count 6"))
	}

	private fun jointFixture(
		version: Float = 2.1f,
		firstJointRigidBodyIndex: Int = 0,
		firstJointType: Int = PmxJointType.SPRING_SIX_DOF.encodedValue,
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor(version)
		repeat(7) { writeInt32(0) }
		writeInt32(2)
		writeRigidBody("Body A")
		writeRigidBody("Body B")
		writeInt32(PmxJointType.entries.size)
		PmxJointType.entries.forEachIndexed { index, type ->
			writeJoint(
				type = if (index == 0) firstJointType else type.encodedValue,
				firstRigidBodyIndex = if (index == 0) firstJointRigidBodyIndex else 0,
				secondRigidBodyIndex = if (index == PmxJointType.entries.lastIndex) -1 else 1,
			)
		}
	}.toByteArray()

	private fun ByteArrayOutputStream.writeDescriptor(version: Float) {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(version)
		write(8)
		write(1)
		write(0)
		repeat(6) { write(1) }
		repeat(4) { writeText("") }
	}

	private fun ByteArrayOutputStream.writeRigidBody(name: String) {
		writeText(name)
		writeText("")
		writeSignedIndex(-1)
		write(0)
		writeInt16(0)
		write(PmxRigidBodyShape.SPHERE.encodedValue)
		repeat(3) { writeVector3(PmxVector3(0f, 0f, 0f)) }
		repeat(5) { writeFloat32(0f) }
		write(PmxRigidBodyMode.FOLLOW_BONE.encodedValue)
	}

	private fun ByteArrayOutputStream.writeJoint(
		type: Int,
		firstRigidBodyIndex: Int,
		secondRigidBodyIndex: Int,
	) {
		writeText("Joint $type")
		writeText("")
		write(type)
		writeSignedIndex(firstRigidBodyIndex)
		writeSignedIndex(secondRigidBodyIndex)
		writeVector3(PmxVector3(1f, 2f, 3f))
		writeVector3(PmxVector3(0.1f, 0.2f, 0.3f))
		writeVector3(PmxVector3(-1f, -2f, -3f))
		writeVector3(PmxVector3(1f, 2f, 3f))
		writeVector3(PmxVector3(-0.5f, -1f, -1.5f))
		writeVector3(PmxVector3(0.5f, 1f, 1.5f))
		writeVector3(PmxVector3(4f, 5f, 6f))
		writeVector3(PmxVector3(7f, 8f, 9f))
	}

	private fun ByteArrayOutputStream.writeVector3(value: PmxVector3) {
		writeFloat32(value.x)
		writeFloat32(value.y)
		writeFloat32(value.z)
	}

	private fun ByteArrayOutputStream.writeSignedIndex(value: Int) {
		write(value and 0xFF)
	}

	private fun ByteArrayOutputStream.writeText(value: String) {
		val bytes = value.toByteArray(StandardCharsets.UTF_8)
		writeInt32(bytes.size)
		write(bytes)
	}

	private fun ByteArrayOutputStream.writeFloat32(value: Float) {
		write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
	}

	private fun ByteArrayOutputStream.writeInt16(value: Int) {
		write(ByteBuffer.allocate(Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
	}

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}
}
