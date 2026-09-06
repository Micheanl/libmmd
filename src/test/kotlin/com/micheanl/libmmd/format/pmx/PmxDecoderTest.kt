package com.micheanl.libmmd.format.pmx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PmxDecoderTest {
	@Test
	fun `reads UTF-8 PMX descriptor`() {
		val bytes = pmxDescriptor(
			version = 2.1f,
			encoding = 1,
			additionalUvChannels = 2,
			indexWidths = intArrayOf(4, 1, 2, 4, 2, 1),
			texts = listOf("初音ミク", "Hatsune Miku", "説明", "Description"),
		)

		val descriptor = PmxDecoder.readDescriptor(bytes)

		assertEquals(PmxVersion.V2_1, descriptor.format.version)
		assertEquals(PmxTextEncoding.UTF_8, descriptor.format.textEncoding)
		assertEquals(2, descriptor.format.additionalUvChannels)
		assertEquals(PmxIndexWidth.FOUR, descriptor.format.indices.vertex)
		assertEquals(PmxIndexWidth.ONE, descriptor.format.indices.texture)
		assertEquals(PmxIndexWidth.TWO, descriptor.format.indices.material)
		assertEquals("初音ミク", descriptor.metadata.name)
		assertEquals("Description", descriptor.metadata.englishDescription)
	}

	@Test
	fun `reads UTF-16 PMX descriptor`() {
		val descriptor = PmxDecoder.readDescriptor(
			pmxDescriptor(
				encoding = 0,
				texts = listOf("モデル", "Model", "コメント", "Comment"),
			),
		)

		assertEquals(PmxTextEncoding.UTF_16_LE, descriptor.format.textEncoding)
		assertEquals("モデル", descriptor.metadata.name)
		assertEquals("コメント", descriptor.metadata.description)
	}

	@Test
	fun `rejects invalid signature`() {
		val bytes = pmxDescriptor().also { it[0] = 'X'.code.toByte() }

		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readDescriptor(bytes)
		}

		assertEquals(0, exception.byteOffset)
	}

	@Test
	fun `rejects unsupported index width`() {
		val bytes = pmxDescriptor(indexWidths = intArrayOf(3, 1, 1, 1, 1, 1))

		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readDescriptor(bytes)
		}

		assertTrue(exception.message.orEmpty().contains("vertex index width"))
	}

	@Test
	fun `reports truncated input`() {
		val bytes = pmxDescriptor().copyOf(12)

		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readDescriptor(bytes)
		}

		assertTrue(exception.byteOffset >= 9)
	}

	private fun pmxDescriptor(
		version: Float = 2.0f,
		encoding: Int = 1,
		additionalUvChannels: Int = 0,
		indexWidths: IntArray = intArrayOf(1, 1, 1, 1, 1, 1),
		texts: List<String> = listOf("Model", "Model", "", ""),
	): ByteArray {
		require(indexWidths.size == 6)
		require(texts.size == 4)
		val charset = if (encoding == 0) StandardCharsets.UTF_16LE else StandardCharsets.UTF_8
		return ByteArrayOutputStream().apply {
			write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
			writeFloat32(version)
			write(8)
			write(encoding)
			write(additionalUvChannels)
			indexWidths.forEach(::write)
			texts.forEach { writeText(it, charset) }
		}.toByteArray()
	}

	private fun ByteArrayOutputStream.writeFloat32(value: Float) {
		write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
	}

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}

	private fun ByteArrayOutputStream.writeText(value: String, charset: Charset) {
		val bytes = value.toByteArray(charset)
		writeInt32(bytes.size)
		write(bytes)
	}
}
