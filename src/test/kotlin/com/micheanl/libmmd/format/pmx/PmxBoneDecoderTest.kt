package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmxBoneDecoderTest {
	@Test
	fun `reads hierarchy transform options and inverse kinematics`() {
		val asset = PmxDecoder.readBones(boneFixture())
		val root = asset.bones[0]
		val control = asset.bones[1]
		val ikBone = asset.bones[2]

		assertEquals(-1, root.parentBoneIndex)
		assertEquals(1, assertIs<PmxBoneTail.LinkedBone>(root.tail).boneIndex)
		assertTrue(root.flags.canRotate)
		assertTrue(root.flags.isVisible)
		assertTrue(root.flags.isEnabled)

		assertEquals(PmxVector3(0f, 1f, 0f), assertIs<PmxBoneTail.Offset>(control.tail).offset)
		assertEquals(PmxBoneInheritance(0, 0.5f), control.inheritance)
		assertEquals(PmxVector3(1f, 0f, 0f), control.fixedAxis)
		assertEquals(
			PmxLocalAxes(PmxVector3(1f, 0f, 0f), PmxVector3(0f, 0f, 1f)),
			control.localAxes,
		)
		assertEquals(27, control.externalParentKey)
		assertTrue(control.flags.inheritsRotation)
		assertTrue(control.flags.inheritsTranslation)
		assertTrue(control.flags.deformsAfterPhysics)

		val ik = ikBone.inverseKinematics
		requireNotNull(ik)
		assertEquals(1, ik.targetBoneIndex)
		assertEquals(40, ik.iterationCount)
		assertEquals(0.5f, ik.angleLimit)
		assertEquals(2, ik.links.size)
		assertEquals(
			PmxIkAngleLimits(PmxVector3(-1f, -2f, -3f), PmxVector3(1f, 2f, 3f)),
			ik.links[0].angleLimits,
		)
		assertNull(ik.links[1].angleLimits)
	}

	@Test
	fun `rejects skinning references outside bone table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readBones(boneFixture(vertexBoneIndex = 3))
		}

		assertTrue(exception.message.orEmpty().contains("references bone 3"))
	}

	@Test
	fun `rejects hierarchy references outside bone table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readBones(boneFixture(rootParentIndex = 3))
		}

		assertTrue(exception.message.orEmpty().contains("parent bone index 3"))
	}

	@Test
	fun `enforces configured IK link limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readBones(boneFixture(), PmxDecodeLimits(maxIkLinksPerBone = 1))
		}

		assertTrue(exception.message.orEmpty().contains("IK link count 2"))
	}

	@Test
	fun `enforces configured IK iteration limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readBones(boneFixture(), PmxDecodeLimits(maxIkIterationsPerBone = 39))
		}

		assertTrue(exception.message.orEmpty().contains("IK iteration count 40"))
	}

	private fun boneFixture(
		vertexBoneIndex: Int = 0,
		rootParentIndex: Int = -1,
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor()
		writeInt32(1)
		writeVertex(vertexBoneIndex)
		writeInt32(0)
		writeInt32(0)
		writeInt32(0)
		writeInt32(3)
		writeBoneHeader("センター", "Root", PmxVector3(0f, 0f, 0f), rootParentIndex, 0, 0x001B)
		writeSignedIndex(1)
		writeBoneHeader("操作", "Control", PmxVector3(0f, 1f, 0f), 0, 1, 0x3F02)
		writeVector3(PmxVector3(0f, 1f, 0f))
		writeSignedIndex(0)
		writeFloat32(0.5f)
		writeVector3(PmxVector3(1f, 0f, 0f))
		writeVector3(PmxVector3(1f, 0f, 0f))
		writeVector3(PmxVector3(0f, 0f, 1f))
		writeInt32(27)
		writeBoneHeader("足ＩＫ", "Leg IK", PmxVector3(0f, 2f, 0f), 0, 2, 0x0023)
		writeSignedIndex(1)
		writeSignedIndex(1)
		writeInt32(40)
		writeFloat32(0.5f)
		writeInt32(2)
		writeSignedIndex(1)
		write(1)
		writeVector3(PmxVector3(-1f, -2f, -3f))
		writeVector3(PmxVector3(1f, 2f, 3f))
		writeSignedIndex(0)
		write(0)
	}.toByteArray()

	private fun ByteArrayOutputStream.writeDescriptor() {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(2.1f)
		write(8)
		write(1)
		write(0)
		repeat(6) { write(1) }
		repeat(4) { writeText("") }
	}

	private fun ByteArrayOutputStream.writeVertex(boneIndex: Int) {
		repeat(3) { writeFloat32(0f) }
		writeVector3(PmxVector3(0f, 1f, 0f))
		repeat(2) { writeFloat32(0f) }
		write(PmxSkinningMode.BDEF1.encodedValue)
		writeSignedIndex(boneIndex)
		writeFloat32(1f)
	}

	private fun ByteArrayOutputStream.writeBoneHeader(
		name: String,
		englishName: String,
		position: PmxVector3,
		parentBoneIndex: Int,
		deformLayer: Int,
		flags: Int,
	) {
		writeText(name)
		writeText(englishName)
		writeVector3(position)
		writeSignedIndex(parentBoneIndex)
		writeInt32(deformLayer)
		writeInt16(flags)
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
