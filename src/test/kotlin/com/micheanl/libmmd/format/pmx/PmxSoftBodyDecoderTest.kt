package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PmxSoftBodyDecoderTest {
	@Test
	fun `reads soft body physics configuration anchors and pins`() {
		val softBodies = PmxDecoder.readSoftBodies(softBodyFixture()).softBodies

		assertEquals(2, softBodies.size)
		val mesh = softBodies[0]
		assertEquals(PmxSoftBodyShape.TRIANGLE_MESH, mesh.shape)
		assertEquals(0, mesh.materialIndex)
		assertEquals(0xFFFE, mesh.collisionExclusionMask)
		assertTrue(mesh.flags.usesBLink)
		assertTrue(mesh.flags.createsClusters)
		assertTrue(mesh.flags.allowsLinkCrossing)
		assertEquals(PmxSoftBodyAerodynamics.FACE_TWO_SIDED, mesh.aerodynamics)
		assertEquals(1f, mesh.config.velocityCorrectionFactor)
		assertEquals(12f, mesh.config.anchorHardness)
		assertEquals(13f, mesh.cluster.softRigidHardness)
		assertEquals(18f, mesh.cluster.softSoftImpulseSplit)
		assertEquals(PmxSoftBodySolverIterations(19, 20, 21, 22), mesh.solverIterations)
		assertEquals(PmxSoftBodyMaterialCoefficients(23f, 24f, 25f), mesh.materialCoefficients)
		assertEquals(PmxSoftBodyAnchor(0, 0, true), mesh.anchors[0])
		assertEquals(PmxSoftBodyAnchor(1, 1, false), mesh.anchors[1])
		assertContentEquals(intArrayOf(0, 1), mesh.pinnedVertexIndices)

		assertEquals(PmxSoftBodyShape.ROPE, softBodies[1].shape)
		assertEquals(PmxSoftBodyAerodynamics.VERTEX_POINT, softBodies[1].aerodynamics)
	}

	@Test
	fun `returns no soft bodies for PMX 20`() {
		val asset = PmxDecoder.readSoftBodies(softBodyFixture(version = 2.0f))

		assertTrue(asset.softBodies.isEmpty())
	}

	@Test
	fun `rejects soft body material outside material table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readSoftBodies(softBodyFixture(materialIndex = 1))
		}

		assertTrue(exception.message.orEmpty().contains("soft body material index 1"))
	}

	@Test
	fun `rejects soft body anchor outside rigid body table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readSoftBodies(softBodyFixture(firstAnchorRigidBodyIndex = 2))
		}

		assertTrue(exception.message.orEmpty().contains("anchor rigid body index 2"))
	}

	@Test
	fun `rejects soft body pin outside vertex table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readSoftBodies(softBodyFixture(firstPinnedVertexIndex = 2))
		}

		assertTrue(exception.message.orEmpty().contains("pin vertex index 2"))
	}

	@Test
	fun `enforces soft body count limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readSoftBodies(softBodyFixture(), PmxDecodeLimits(maxSoftBodies = 1))
		}

		assertTrue(exception.message.orEmpty().contains("soft body count 2"))
	}

	@Test
	fun `enforces total soft body anchor limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readSoftBodies(softBodyFixture(), PmxDecodeLimits(maxSoftBodyAnchors = 1))
		}

		assertTrue(exception.message.orEmpty().contains("soft body anchor count 2"))
	}

	@Test
	fun `enforces total soft body pin limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readSoftBodies(softBodyFixture(), PmxDecodeLimits(maxSoftBodyPins = 1))
		}

		assertTrue(exception.message.orEmpty().contains("soft body pin count 2"))
	}

	private fun softBodyFixture(
		version: Float = 2.1f,
		materialIndex: Int = 0,
		firstAnchorRigidBodyIndex: Int = 0,
		firstPinnedVertexIndex: Int = 0,
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor(version)
		writeInt32(2)
		repeat(2) { writeVertex() }
		writeInt32(0)
		writeInt32(0)
		writeInt32(1)
		writeMaterial()
		repeat(3) { writeInt32(0) }
		writeInt32(2)
		writeRigidBody("Body A")
		writeRigidBody("Body B")
		writeInt32(0)
		if (version >= 2.1f) {
			writeInt32(2)
			writeSoftBody(
				name = "Cloth",
				shape = PmxSoftBodyShape.TRIANGLE_MESH,
				materialIndex = materialIndex,
				aerodynamics = PmxSoftBodyAerodynamics.FACE_TWO_SIDED,
				anchorRigidBodyIndex = firstAnchorRigidBodyIndex,
				firstPinnedVertexIndex = firstPinnedVertexIndex,
				withBindings = true,
			)
			writeSoftBody(
				name = "Rope",
				shape = PmxSoftBodyShape.ROPE,
				materialIndex = 0,
				aerodynamics = PmxSoftBodyAerodynamics.VERTEX_POINT,
				anchorRigidBodyIndex = 0,
				firstPinnedVertexIndex = 0,
				withBindings = false,
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

	private fun ByteArrayOutputStream.writeVertex() {
		repeat(3) { writeFloat32(0f) }
		writeVector3(PmxVector3(0f, 1f, 0f))
		repeat(2) { writeFloat32(0f) }
		write(PmxSkinningMode.BDEF1.encodedValue)
		writeSignedIndex(-1)
		writeFloat32(1f)
	}

	private fun ByteArrayOutputStream.writeMaterial() {
		writeText("Material")
		writeText("")
		repeat(4) { writeFloat32(1f) }
		repeat(3) { writeFloat32(0f) }
		writeFloat32(0f)
		repeat(3) { writeFloat32(0f) }
		write(0)
		repeat(4) { writeFloat32(0f) }
		writeFloat32(0f)
		writeSignedIndex(-1)
		writeSignedIndex(-1)
		write(PmxSphereMode.DISABLED.encodedValue)
		write(1)
		write(0)
		writeText("")
		writeInt32(0)
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

	private fun ByteArrayOutputStream.writeSoftBody(
		name: String,
		shape: PmxSoftBodyShape,
		materialIndex: Int,
		aerodynamics: PmxSoftBodyAerodynamics,
		anchorRigidBodyIndex: Int,
		firstPinnedVertexIndex: Int,
		withBindings: Boolean,
	) {
		writeText(name)
		writeText("")
		write(shape.encodedValue)
		writeSignedIndex(materialIndex)
		write(1)
		writeInt16(0xFFFE)
		write(0x07)
		writeInt32(2)
		writeInt32(3)
		writeFloat32(4f)
		writeFloat32(0.1f)
		writeInt32(aerodynamics.encodedValue)
		(1..18).forEach { writeFloat32(it.toFloat()) }
		(19..22).forEach { writeInt32(it) }
		(23..25).forEach { writeFloat32(it.toFloat()) }
		if (withBindings) {
			writeInt32(2)
			writeSignedIndex(anchorRigidBodyIndex)
			write(0)
			write(1)
			writeSignedIndex(1)
			write(1)
			write(0)
			writeInt32(2)
			write(firstPinnedVertexIndex)
			write(1)
		} else {
			writeInt32(0)
			writeInt32(0)
		}
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
