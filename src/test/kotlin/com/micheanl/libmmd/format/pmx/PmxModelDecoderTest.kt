package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PmxModelDecoderTest {
	@Test
	fun `reads a complete PMX 21 model`() {
		val model = PmxDecoder.readModel(emptyModelFixture(2.1f))

		assertEquals(PmxVersion.V2_1, model.descriptor.format.version)
		assertEquals("Model", model.descriptor.metadata.name)
		assertEquals(0, model.geometry.vertexCount)
		assertTrue(model.texturePaths.isEmpty())
		assertTrue(model.materials.isEmpty())
		assertTrue(model.bones.isEmpty())
		assertTrue(model.morphs.isEmpty())
		assertTrue(model.displayFrames.isEmpty())
		assertTrue(model.rigidBodies.isEmpty())
		assertTrue(model.joints.isEmpty())
		assertTrue(model.softBodies.isEmpty())
	}

	@Test
	fun `reads a complete PMX 20 model without a soft body section`() {
		val model = PmxDecoder.readModel(emptyModelFixture(2.0f))

		assertEquals(PmxVersion.V2_0, model.descriptor.format.version)
		assertTrue(model.softBodies.isEmpty())
	}

	@Test
	fun `rejects trailing bytes after a complete model`() {
		val bytes = emptyModelFixture(2.1f) + 0x7F.toByte()

		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readModel(bytes)
		}

		assertTrue(exception.message.orEmpty().contains("trailing bytes"))
	}

	@Test
	fun `reports a truncated complete model`() {
		val bytes = emptyModelFixture(2.1f).let { it.copyOf(it.size - 1) }

		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readModel(bytes)
		}

		assertTrue(exception.message.orEmpty().contains("Expected 4 bytes"))
	}

	private fun emptyModelFixture(version: Float): ByteArray = ByteArrayOutputStream().apply {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(version)
		write(8)
		write(1)
		write(0)
		repeat(6) { write(1) }
		writeText("Model")
		repeat(3) { writeText("") }
		repeat(if (version >= 2.1f) 10 else 9) { writeInt32(0) }
	}.toByteArray()

	private fun ByteArrayOutputStream.writeFloat32(value: Float) {
		write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
	}

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}

	private fun ByteArrayOutputStream.writeText(value: String) {
		val bytes = value.toByteArray(StandardCharsets.UTF_8)
		writeInt32(bytes.size)
		write(bytes)
	}
}
