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

class PmxMorphDecoderTest {
	@Test
	fun `reads every PMX morph offset type`() {
		val asset = PmxDecoder.readMorphs(morphFixture())

		assertEquals(11, asset.morphs.size)
		val group = asset.morphs[0]
		assertEquals(PmxMorphPanel.OTHER, group.panel)
		assertEquals(PmxMorphType.GROUP, group.type)
		assertEquals(PmxGroupMorphOffset(1, 0.25f), assertIs<PmxGroupMorphOffset>(group.offsets.single()))

		assertEquals(
			PmxVertexMorphOffset(0, PmxVector3(1f, 2f, 3f)),
			assertIs<PmxVertexMorphOffset>(asset.morphs[1].offsets.single()),
		)
		assertEquals(
			PmxBoneMorphOffset(0, PmxVector3(4f, 5f, 6f), PmxVector4(0f, 0f, 0f, 1f)),
			assertIs<PmxBoneMorphOffset>(asset.morphs[2].offsets.single()),
		)

		asset.morphs.slice(3..7).forEachIndexed { channel, morph ->
			val offset = assertIs<PmxUvMorphOffset>(morph.offsets.single())
			assertEquals(channel, offset.uvChannel)
			assertEquals(PmxVector4(channel.toFloat(), 0f, 0f, 0f), offset.displacement)
		}

		val material = assertIs<PmxMaterialMorphOffset>(asset.morphs[8].offsets.single())
		assertEquals(-1, material.materialIndex)
		assertEquals(PmxMaterialMorphOperation.ADDITIVE, material.operation)
		assertEquals(PmxRgba(1f, 0.5f, 0.25f, 1f), material.diffuse)
		assertEquals(PmxRgba(0.4f, 0.3f, 0.2f, 0.1f), material.toonTint)

		assertEquals(
			PmxFlipMorphOffset(0, 0.75f),
			assertIs<PmxFlipMorphOffset>(asset.morphs[9].offsets.single()),
		)
		assertEquals(
			PmxImpulseMorphOffset(2, true, PmxVector3(7f, 8f, 9f), PmxVector3(1f, 2f, 3f)),
			assertIs<PmxImpulseMorphOffset>(asset.morphs[10].offsets.single()),
		)
	}

	@Test
	fun `rejects PMX 21 morph types in PMX 20`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMorphs(morphFixture(version = 2.0f))
		}

		assertTrue(exception.message.orEmpty().contains("FLIP morph requires PMX 2.1"))
	}

	@Test
	fun `rejects unavailable additional UV morph channel`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMorphs(morphFixture(additionalUvChannels = 0))
		}

		assertTrue(exception.message.orEmpty().contains("UV channel 1"))
	}

	@Test
	fun `rejects vertex morph references outside geometry`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMorphs(morphFixture(vertexMorphIndex = 1))
		}

		assertTrue(exception.message.orEmpty().contains("vertex morph index 1"))
	}

	@Test
	fun `enforces total morph offset limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMorphs(morphFixture(), PmxDecodeLimits(maxMorphOffsets = 10))
		}

		assertTrue(exception.message.orEmpty().contains("morph offset count 1"))
	}

	private fun morphFixture(
		version: Float = 2.1f,
		additionalUvChannels: Int = 4,
		vertexMorphIndex: Int = 0,
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor(version, additionalUvChannels)
		writeInt32(1)
		writeVertex(additionalUvChannels)
		writeInt32(0)
		writeInt32(0)
		writeInt32(1)
		writeMaterial()
		writeInt32(1)
		writeBone()
		writeInt32(11)
		writeMorph("Group", PmxMorphType.GROUP) {
			writeSignedIndex(1)
			writeFloat32(0.25f)
		}
		writeMorph("Vertex", PmxMorphType.VERTEX) {
			write(vertexMorphIndex)
			writeVector3(PmxVector3(1f, 2f, 3f))
		}
		writeMorph("Bone", PmxMorphType.BONE) {
			writeSignedIndex(0)
			writeVector3(PmxVector3(4f, 5f, 6f))
			writeVector4(PmxVector4(0f, 0f, 0f, 1f))
		}
		val uvTypes = listOf(
			PmxMorphType.UV,
			PmxMorphType.ADDITIONAL_UV_1,
			PmxMorphType.ADDITIONAL_UV_2,
			PmxMorphType.ADDITIONAL_UV_3,
			PmxMorphType.ADDITIONAL_UV_4,
		)
		uvTypes.forEachIndexed { channel, type ->
			writeMorph(type.name, type) {
				write(0)
				writeVector4(PmxVector4(channel.toFloat(), 0f, 0f, 0f))
			}
		}
		writeMorph("Material", PmxMorphType.MATERIAL) {
			writeSignedIndex(-1)
			write(PmxMaterialMorphOperation.ADDITIVE.encodedValue)
			writeRgba(PmxRgba(1f, 0.5f, 0.25f, 1f))
			writeRgb(PmxRgb(0.1f, 0.2f, 0.3f))
			writeFloat32(8f)
			writeRgb(PmxRgb(0.01f, 0.02f, 0.03f))
			writeRgba(PmxRgba(0f, 0f, 0f, 1f))
			writeFloat32(0.5f)
			writeRgba(PmxRgba(0.1f, 0.2f, 0.3f, 0.4f))
			writeRgba(PmxRgba(0.2f, 0.3f, 0.4f, 0.5f))
			writeRgba(PmxRgba(0.4f, 0.3f, 0.2f, 0.1f))
		}
		writeMorph("Flip", PmxMorphType.FLIP) {
			writeSignedIndex(0)
			writeFloat32(0.75f)
		}
		writeMorph("Impulse", PmxMorphType.IMPULSE) {
			writeSignedIndex(2)
			write(1)
			writeVector3(PmxVector3(7f, 8f, 9f))
			writeVector3(PmxVector3(1f, 2f, 3f))
		}
	}.toByteArray()

	private fun ByteArrayOutputStream.writeDescriptor(version: Float, additionalUvChannels: Int) {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(version)
		write(8)
		write(1)
		write(additionalUvChannels)
		repeat(6) { write(1) }
		repeat(4) { writeText("") }
	}

	private fun ByteArrayOutputStream.writeVertex(additionalUvChannels: Int) {
		repeat(3) { writeFloat32(0f) }
		writeVector3(PmxVector3(0f, 1f, 0f))
		repeat(2 + additionalUvChannels * 4) { writeFloat32(0f) }
		write(PmxSkinningMode.BDEF1.encodedValue)
		writeSignedIndex(0)
		writeFloat32(1f)
	}

	private fun ByteArrayOutputStream.writeMaterial() {
		writeText("Material")
		writeText("")
		writeRgba(PmxRgba(1f, 1f, 1f, 1f))
		writeRgb(PmxRgb(0f, 0f, 0f))
		writeFloat32(0f)
		writeRgb(PmxRgb(0f, 0f, 0f))
		write(0)
		writeRgba(PmxRgba(0f, 0f, 0f, 1f))
		writeFloat32(0f)
		writeSignedIndex(-1)
		writeSignedIndex(-1)
		write(PmxSphereMode.DISABLED.encodedValue)
		write(1)
		write(0)
		writeText("")
		writeInt32(0)
	}

	private fun ByteArrayOutputStream.writeBone() {
		writeText("Bone")
		writeText("")
		writeVector3(PmxVector3(0f, 0f, 0f))
		writeSignedIndex(-1)
		writeInt32(0)
		writeInt16(0)
		writeVector3(PmxVector3(0f, 1f, 0f))
	}

	private fun ByteArrayOutputStream.writeMorph(
		name: String,
		type: PmxMorphType,
		writeOffset: ByteArrayOutputStream.() -> Unit,
	) {
		writeText(name)
		writeText("")
		write(PmxMorphPanel.OTHER.encodedValue)
		write(type.encodedValue)
		writeInt32(1)
		writeOffset()
	}

	private fun ByteArrayOutputStream.writeRgb(value: PmxRgb) {
		writeFloat32(value.red)
		writeFloat32(value.green)
		writeFloat32(value.blue)
	}

	private fun ByteArrayOutputStream.writeRgba(value: PmxRgba) {
		writeFloat32(value.red)
		writeFloat32(value.green)
		writeFloat32(value.blue)
		writeFloat32(value.alpha)
	}

	private fun ByteArrayOutputStream.writeVector3(value: PmxVector3) {
		writeFloat32(value.x)
		writeFloat32(value.y)
		writeFloat32(value.z)
	}

	private fun ByteArrayOutputStream.writeVector4(value: PmxVector4) {
		writeFloat32(value.x)
		writeFloat32(value.y)
		writeFloat32(value.z)
		writeFloat32(value.w)
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
