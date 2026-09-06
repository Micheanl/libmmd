package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PmxRigidBodyDecoderTest {
	@Test
	fun `reads special and regular display frames`() {
		val frames = PmxDecoder.readDisplayFrames(physicsFixture()).displayFrames

		assertEquals(2, frames.size)
		assertTrue(frames[0].isSpecial)
		assertEquals(0, assertIs<PmxBoneDisplayElement>(frames[0].elements[0]).boneIndex)
		assertEquals(0, assertIs<PmxMorphDisplayElement>(frames[0].elements[1]).morphIndex)
		assertEquals(false, frames[1].isSpecial)
	}

	@Test
	fun `reads rigid body shapes filters and physics modes`() {
		val bodies = PmxDecoder.readRigidBodies(physicsFixture()).rigidBodies

		assertEquals(3, bodies.size)
		assertEquals(PmxRigidBodyShape.SPHERE, bodies[0].shape)
		assertEquals(PmxRigidBodyMode.FOLLOW_BONE, bodies[0].mode)
		assertEquals(0xFFFE, bodies[0].collisionExclusionMask)
		assertEquals(PmxVector3(1f, 1f, 1f), bodies[0].size)
		assertEquals(1f, bodies[0].mass)
		assertEquals(0.4f, bodies[0].friction)

		assertEquals(PmxRigidBodyShape.BOX, bodies[1].shape)
		assertEquals(PmxRigidBodyMode.PHYSICS, bodies[1].mode)
		assertEquals(-1, bodies[1].boneIndex)
		assertEquals(PmxVector3(4f, 5f, 6f), bodies[1].position)

		assertEquals(PmxRigidBodyShape.CAPSULE, bodies[2].shape)
		assertEquals(PmxRigidBodyMode.PHYSICS_WITH_BONE, bodies[2].mode)
	}

	@Test
	fun `rejects invalid display frame special flag`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readDisplayFrames(physicsFixture(specialFlag = 2))
		}

		assertTrue(exception.message.orEmpty().contains("special flag 2"))
	}

	@Test
	fun `rejects display frame references outside bone table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readDisplayFrames(physicsFixture(displayBoneIndex = 1))
		}

		assertTrue(exception.message.orEmpty().contains("display frame bone index 1"))
	}

	@Test
	fun `validates impulse morph references against rigid bodies`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readRigidBodies(physicsFixture(impulseRigidBodyIndex = 3))
		}

		assertTrue(exception.message.orEmpty().contains("references rigid body 3"))
	}

	@Test
	fun `enforces total display frame element limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readDisplayFrames(physicsFixture(), PmxDecodeLimits(maxDisplayFrameElements = 2))
		}

		assertTrue(exception.message.orEmpty().contains("display frame element count 1"))
	}

	@Test
	fun `enforces rigid body count limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readRigidBodies(physicsFixture(), PmxDecodeLimits(maxRigidBodies = 2))
		}

		assertTrue(exception.message.orEmpty().contains("rigid body count 3"))
	}

	private fun physicsFixture(
		specialFlag: Int = 1,
		displayBoneIndex: Int = 0,
		impulseRigidBodyIndex: Int = 1,
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor()
		writeInt32(1)
		writeVertex()
		writeInt32(0)
		writeInt32(0)
		writeInt32(0)
		writeInt32(1)
		writeBone()
		writeInt32(1)
		writeImpulseMorph(impulseRigidBodyIndex)
		writeInt32(2)
		writeDisplayFrame("Root", specialFlag) {
			write(0)
			writeSignedIndex(displayBoneIndex)
			write(1)
			writeSignedIndex(0)
		}
		writeDisplayFrame("Facial", 0) {
			write(1)
			writeSignedIndex(0)
		}
		writeInt32(3)
		writeRigidBody(
			name = "Sphere",
			boneIndex = 0,
			collisionGroup = 1,
			collisionExclusionMask = 0xFFFE,
			shape = PmxRigidBodyShape.SPHERE,
			size = PmxVector3(1f, 1f, 1f),
			position = PmxVector3(0f, 1f, 0f),
			mode = PmxRigidBodyMode.FOLLOW_BONE,
			mass = 1f,
		)
		writeRigidBody(
			name = "Box",
			boneIndex = -1,
			collisionGroup = 2,
			collisionExclusionMask = 0x8001,
			shape = PmxRigidBodyShape.BOX,
			size = PmxVector3(1f, 2f, 3f),
			position = PmxVector3(4f, 5f, 6f),
			mode = PmxRigidBodyMode.PHYSICS,
			mass = 2f,
		)
		writeRigidBody(
			name = "Capsule",
			boneIndex = 0,
			collisionGroup = 3,
			collisionExclusionMask = 0,
			shape = PmxRigidBodyShape.CAPSULE,
			size = PmxVector3(0.5f, 2f, 0.5f),
			position = PmxVector3(0f, 2f, 0f),
			mode = PmxRigidBodyMode.PHYSICS_WITH_BONE,
			mass = 3f,
		)
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

	private fun ByteArrayOutputStream.writeVertex() {
		repeat(3) { writeFloat32(0f) }
		writeVector3(PmxVector3(0f, 1f, 0f))
		repeat(2) { writeFloat32(0f) }
		write(PmxSkinningMode.BDEF1.encodedValue)
		writeSignedIndex(0)
		writeFloat32(1f)
	}

	private fun ByteArrayOutputStream.writeBone() {
		writeText("Root")
		writeText("")
		writeVector3(PmxVector3(0f, 0f, 0f))
		writeSignedIndex(-1)
		writeInt32(0)
		writeInt16(0)
		writeVector3(PmxVector3(0f, 1f, 0f))
	}

	private fun ByteArrayOutputStream.writeImpulseMorph(rigidBodyIndex: Int) {
		writeText("Impulse")
		writeText("")
		write(PmxMorphPanel.OTHER.encodedValue)
		write(PmxMorphType.IMPULSE.encodedValue)
		writeInt32(1)
		writeSignedIndex(rigidBodyIndex)
		write(0)
		writeVector3(PmxVector3(1f, 0f, 0f))
		writeVector3(PmxVector3(0f, 1f, 0f))
	}

	private fun ByteArrayOutputStream.writeDisplayFrame(
		name: String,
		specialFlag: Int,
		writeElements: ByteArrayOutputStream.() -> Unit,
	) {
		writeText(name)
		writeText("")
		write(specialFlag)
		writeInt32(if (name == "Root") 2 else 1)
		writeElements()
	}

	private fun ByteArrayOutputStream.writeRigidBody(
		name: String,
		boneIndex: Int,
		collisionGroup: Int,
		collisionExclusionMask: Int,
		shape: PmxRigidBodyShape,
		size: PmxVector3,
		position: PmxVector3,
		mode: PmxRigidBodyMode,
		mass: Float,
	) {
		writeText(name)
		writeText("")
		writeSignedIndex(boneIndex)
		write(collisionGroup)
		writeInt16(collisionExclusionMask)
		write(shape.encodedValue)
		writeVector3(size)
		writeVector3(position)
		writeVector3(PmxVector3(0.1f, 0.2f, 0.3f))
		writeFloat32(mass)
		writeFloat32(0.1f)
		writeFloat32(0.2f)
		writeFloat32(0.3f)
		writeFloat32(0.4f)
		write(mode.encodedValue)
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
