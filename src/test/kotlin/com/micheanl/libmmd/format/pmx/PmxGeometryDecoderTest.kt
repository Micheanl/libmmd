package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PmxGeometryDecoderTest {
	@Test
	fun `reads all PMX skinning modes and triangle indices`() {
		val asset = PmxDecoder.readGeometry(geometryFixture())
		val geometry = asset.geometry

		assertEquals(5, geometry.vertexCount)
		assertEquals(1, geometry.triangleCount)
		assertEquals(1, geometry.additionalUvChannels)
		assertContentEquals(intArrayOf(0, 1, 2), geometry.triangleIndices)
		assertEquals(PmxSkinningMode.BDEF1, geometry.skinning.modeAt(0))
		assertEquals(PmxSkinningMode.BDEF2, geometry.skinning.modeAt(1))
		assertEquals(PmxSkinningMode.BDEF4, geometry.skinning.modeAt(2))
		assertEquals(PmxSkinningMode.SDEF, geometry.skinning.modeAt(3))
		assertEquals(PmxSkinningMode.QDEF, geometry.skinning.modeAt(4))
		assertEquals(1, geometry.skinning.boneIndex(0, 0))
		assertEquals(0.25f, geometry.skinning.boneWeight(1, 0))
		assertEquals(0.75f, geometry.skinning.boneWeight(1, 1))
		assertEquals(0.4f, geometry.skinning.boneWeight(4, 3))
		assertContentEquals(floatArrayOf(3f, 3.1f, 3.2f), geometry.positions.copyOfRange(9, 12))
		assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), geometry.additionalTextureCoordinates.copyOfRange(12, 16))
		assertContentEquals(
			FloatArray(PmxSkinning.SDEF_COMPONENTS) { it.toFloat() },
			assertNotNull(geometry.skinning.sdefParameters).copyOfRange(27, 36),
		)
	}

	@Test
	fun `rejects QDEF in PMX 2_0`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readGeometry(geometryFixture(version = 2.0f))
		}

		assertTrue(exception.message.orEmpty().contains("QDEF requires PMX 2.1"))
	}

	@Test
	fun `rejects triangle index outside vertex range`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readGeometry(geometryFixture(indices = intArrayOf(0, 1, 5)))
		}

		assertTrue(exception.message.orEmpty().contains("outside 0 until 5"))
	}

	@Test
	fun `enforces configured vertex limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readGeometry(geometryFixture(), PmxDecodeLimits(maxVertices = 4))
		}

		assertTrue(exception.message.orEmpty().contains("vertex count 5"))
	}

	private fun geometryFixture(
		version: Float = 2.1f,
		indices: IntArray = intArrayOf(0, 1, 2),
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor(version)
		writeInt32(5)
		writeVertex(PmxSkinningMode.BDEF1, 0)
		writeVertex(PmxSkinningMode.BDEF2, 1)
		writeVertex(PmxSkinningMode.BDEF4, 2)
		writeVertex(PmxSkinningMode.SDEF, 3)
		writeVertex(PmxSkinningMode.QDEF, 4)
		writeInt32(indices.size)
		indices.forEach { writeUnsignedInt16(it) }
	}.toByteArray()

	private fun ByteArrayOutputStream.writeDescriptor(version: Float) {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(version)
		write(8)
		write(1)
		write(1)
		write(2)
		write(1)
		write(1)
		write(2)
		write(1)
		write(1)
		repeat(4) { writeText("") }
	}

	private fun ByteArrayOutputStream.writeVertex(mode: PmxSkinningMode, vertexIndex: Int) {
		writeFloat32(vertexIndex.toFloat())
		writeFloat32(vertexIndex + 0.1f)
		writeFloat32(vertexIndex + 0.2f)
		writeFloat32(0f)
		writeFloat32(1f)
		writeFloat32(0f)
		writeFloat32(0.25f)
		writeFloat32(0.75f)
		repeat(4) { writeFloat32((it + 1).toFloat()) }
		write(mode.encodedValue)
		when (mode) {
			PmxSkinningMode.BDEF1 -> writeInt16(1)
			PmxSkinningMode.BDEF2 -> {
				writeInt16(2)
				writeInt16(3)
				writeFloat32(0.25f)
			}

			PmxSkinningMode.BDEF4,
			PmxSkinningMode.QDEF,
			-> {
				repeat(4) { writeInt16(it + 4) }
				listOf(0.1f, 0.2f, 0.3f, 0.4f).forEach { writeFloat32(it) }
			}

			PmxSkinningMode.SDEF -> {
				writeInt16(7)
				writeInt16(8)
				writeFloat32(0.6f)
				repeat(PmxSkinning.SDEF_COMPONENTS) { writeFloat32(it.toFloat()) }
			}
		}
		writeFloat32(vertexIndex + 1f)
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

	private fun ByteArrayOutputStream.writeUnsignedInt16(value: Int) = writeInt16(value)

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}
}
