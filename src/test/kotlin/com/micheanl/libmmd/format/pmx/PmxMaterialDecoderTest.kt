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

class PmxMaterialDecoderTest {
	@Test
	fun `reads textures and MMD material properties`() {
		val asset = PmxDecoder.readMaterials(materialFixture())
		val skin = asset.materials[0]
		val effect = asset.materials[1]

		assertEquals(listOf("textures\\body.png", "effects/sphere.sph", "toon/custom.bmp"), asset.texturePaths)
		assertEquals(PmxRgba(1f, 0.8f, 0.7f, 0.5f), skin.diffuse)
		assertEquals(PmxRgb(0.1f, 0.2f, 0.3f), skin.specular)
		assertEquals(16f, skin.specularStrength)
		assertTrue(skin.drawFlags.disablesCulling)
		assertTrue(skin.drawFlags.castsGroundShadow)
		assertTrue(skin.drawFlags.castsSelfShadow)
		assertTrue(skin.drawFlags.receivesSelfShadow)
		assertTrue(skin.drawFlags.rendersEdge)
		assertEquals(PmxSphereMode.MULTIPLY, skin.sphereMode)
		assertEquals(4, assertIs<PmxToonTexture.Shared>(skin.toonTexture).slot)
		assertEquals(0, skin.firstIndex)
		assertEquals(3, skin.indexCount)

		assertTrue(effect.drawFlags.usesVertexColor)
		assertTrue(effect.drawFlags.drawsPoints)
		assertTrue(effect.drawFlags.drawsLines)
		assertEquals(PmxSphereMode.ADDITIONAL_UV, effect.sphereMode)
		assertEquals(2, assertIs<PmxToonTexture.Custom>(effect.toonTexture).textureIndex)
		assertEquals(3, effect.firstIndex)
		assertEquals(3, effect.indexCount)
	}

	@Test
	fun `rejects texture index outside texture table`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMaterials(materialFixture(firstTextureIndex = 3))
		}

		assertTrue(exception.message.orEmpty().contains("texture index 3"))
	}

	@Test
	fun `rejects material ranges that do not cover geometry`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMaterials(materialFixture(secondMaterialIndexCount = 0))
		}

		assertTrue(exception.message.orEmpty().contains("cover 3 indices"))
	}

	@Test
	fun `enforces configured material limit`() {
		val exception = assertFailsWith<PmxFormatException> {
			PmxDecoder.readMaterials(materialFixture(), PmxDecodeLimits(maxMaterials = 1))
		}

		assertTrue(exception.message.orEmpty().contains("material count 2"))
	}

	private fun materialFixture(
		firstTextureIndex: Int = 0,
		secondMaterialIndexCount: Int = 3,
	): ByteArray = ByteArrayOutputStream().apply {
		writeDescriptor()
		writeInt32(3)
		repeat(3) { vertex -> writeVertex(vertex) }
		writeInt32(6)
		intArrayOf(0, 1, 2, 0, 2, 1).forEach(::write)
		writeInt32(3)
		writeText("textures\\body.png")
		writeText("effects/sphere.sph")
		writeText("toon/custom.bmp")
		writeInt32(2)
		writeMaterial(
			name = "肌",
			englishName = "Skin",
			diffuse = floatArrayOf(1f, 0.8f, 0.7f, 0.5f),
			drawFlags = 0x1F,
			textureIndex = firstTextureIndex,
			sphereTextureIndex = 1,
			sphereMode = PmxSphereMode.MULTIPLY,
			toonReference = 1,
			toonValue = 4,
			indexCount = 3,
		)
		writeMaterial(
			name = "効果",
			englishName = "Effect",
			diffuse = floatArrayOf(0.4f, 0.5f, 1f, 1f),
			drawFlags = 0xE0,
			textureIndex = -1,
			sphereTextureIndex = 1,
			sphereMode = PmxSphereMode.ADDITIONAL_UV,
			toonReference = 0,
			toonValue = 2,
			indexCount = secondMaterialIndexCount,
		)
	}.toByteArray()

	private fun ByteArrayOutputStream.writeDescriptor() {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(2.1f)
		write(8)
		write(1)
		write(1)
		repeat(6) { write(1) }
		repeat(4) { writeText("") }
	}

	private fun ByteArrayOutputStream.writeVertex(vertex: Int) {
		writeFloat32(vertex.toFloat())
		writeFloat32(0f)
		writeFloat32(0f)
		writeFloat32(0f)
		writeFloat32(1f)
		writeFloat32(0f)
		writeFloat32(0f)
		writeFloat32(0f)
		repeat(4) { writeFloat32(0f) }
		write(PmxSkinningMode.BDEF1.encodedValue)
		write(0)
		writeFloat32(1f)
	}

	private fun ByteArrayOutputStream.writeMaterial(
		name: String,
		englishName: String,
		diffuse: FloatArray,
		drawFlags: Int,
		textureIndex: Int,
		sphereTextureIndex: Int,
		sphereMode: PmxSphereMode,
		toonReference: Int,
		toonValue: Int,
		indexCount: Int,
	) {
		writeText(name)
		writeText(englishName)
		diffuse.forEach { writeFloat32(it) }
		floatArrayOf(0.1f, 0.2f, 0.3f).forEach { writeFloat32(it) }
		writeFloat32(16f)
		floatArrayOf(0.05f, 0.06f, 0.07f).forEach { writeFloat32(it) }
		write(drawFlags)
		floatArrayOf(0f, 0f, 0f, 1f).forEach { writeFloat32(it) }
		writeFloat32(1f)
		write(textureIndex and 0xFF)
		write(sphereTextureIndex and 0xFF)
		write(sphereMode.encodedValue)
		write(toonReference)
		write(toonValue and 0xFF)
		writeText("metadata")
		writeInt32(indexCount)
	}

	private fun ByteArrayOutputStream.writeText(value: String) {
		val bytes = value.toByteArray(StandardCharsets.UTF_8)
		writeInt32(bytes.size)
		write(bytes)
	}

	private fun ByteArrayOutputStream.writeFloat32(value: Float) {
		write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
	}

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}
}
