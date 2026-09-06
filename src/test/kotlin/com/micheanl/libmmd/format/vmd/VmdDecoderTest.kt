package com.micheanl.libmmd.format.vmd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VmdDecoderTest {
	private val shiftJis = Charset.forName("windows-31j")

	@Test
	fun `reads all VMD 0002 sections`() {
		val motion = VmdDecoder.readMotion(motionFixture())

		assertEquals("モデル", motion.modelName)
		assertEquals("センター", motion.boneKeyframes.single().boneName)
		assertEquals(12, motion.boneKeyframes.single().frame)
		assertEquals(20f / 127f, motion.boneKeyframes.single().interpolation.x.x1)
		assertEquals("笑い", motion.morphKeyframes.single().morphName)
		assertEquals(45, motion.cameraKeyframes.single().fieldOfView)
		assertTrue(motion.cameraKeyframes.single().perspective)
		assertEquals(VmdColor(0.4f, 0.5f, 0.6f), motion.lightKeyframes.single().color)
		assertEquals(2, motion.shadowKeyframes.single().mode)
		assertEquals(VmdIkState("左足ＩＫ", false), motion.ikKeyframes.single().states.single())
	}

	@Test
	fun `accepts motion ending after bone section`() {
		val bytes = ByteArrayOutputStream().apply {
			writeHeader()
			writeInt32(0)
		}.toByteArray()

		val motion = VmdDecoder.readMotion(bytes)

		assertTrue(motion.boneKeyframes.isEmpty())
		assertTrue(motion.ikKeyframes.isEmpty())
	}

	@Test
	fun `rejects invalid signature limits and truncated frames`() {
		assertFailsWith<VmdFormatException> { VmdDecoder.readMotion(ByteArray(54)) }
		assertFailsWith<VmdFormatException> {
			VmdDecoder.readMotion(motionFixture(), VmdDecodeLimits(maxBoneKeyframes = 0))
		}
		assertFailsWith<VmdFormatException> {
			VmdDecoder.readMotion(motionFixture().copyOf(80))
		}
	}

	private fun motionFixture(): ByteArray = ByteArrayOutputStream().apply {
		writeHeader()
		writeInt32(1)
		writeFixed("センター", 15, shiftJis)
		writeInt32(12)
		writeVector(1f, 2f, 3f)
		writeVector4(0f, 0f, 0f, 1f)
		val interpolation = ByteArray(64)
		interpolation[0] = 20
		interpolation[4] = 30
		interpolation[8] = 100
		interpolation[12] = 110
		write(interpolation)

		writeInt32(1)
		writeFixed("笑い", 15, shiftJis)
		writeInt32(15)
		writeFloat32(0.75f)

		writeInt32(1)
		writeInt32(20)
		writeFloat32(-5f)
		writeVector(4f, 5f, 6f)
		writeVector(0.1f, 0.2f, 0.3f)
		write(ByteArray(24) { it.toByte() })
		writeInt32(45)
		write(0)

		writeInt32(1)
		writeInt32(25)
		writeVector(0.4f, 0.5f, 0.6f)
		writeVector(7f, 8f, 9f)

		writeInt32(1)
		writeInt32(30)
		write(2)
		writeFloat32(0.8f)

		writeInt32(1)
		writeInt32(35)
		write(1)
		writeInt32(1)
		writeFixed("左足ＩＫ", 20, shiftJis)
		write(0)
	}.toByteArray()

	private fun ByteArrayOutputStream.writeHeader() {
		writeFixed("Vocaloid Motion Data 0002", 30, Charsets.US_ASCII)
		writeFixed("モデル", 20, shiftJis)
	}

	private fun ByteArrayOutputStream.writeFixed(value: String, size: Int, charset: Charset) {
		val encoded = value.toByteArray(charset)
		require(encoded.size <= size)
		write(encoded)
		write(ByteArray(size - encoded.size))
	}

	private fun ByteArrayOutputStream.writeVector(x: Float, y: Float, z: Float) {
		writeFloat32(x)
		writeFloat32(y)
		writeFloat32(z)
	}

	private fun ByteArrayOutputStream.writeVector4(x: Float, y: Float, z: Float, w: Float) {
		writeVector(x, y, z)
		writeFloat32(w)
	}

	private fun ByteArrayOutputStream.writeFloat32(value: Float) {
		write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
	}

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}
}
