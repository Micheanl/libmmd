package com.micheanl.libmmd.deform

import com.micheanl.libmmd.format.pmx.PmxDrawFlags
import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.micheanl.libmmd.format.pmx.PmxMaterial
import com.micheanl.libmmd.format.pmx.PmxRgb
import com.micheanl.libmmd.format.pmx.PmxRgba
import com.micheanl.libmmd.format.pmx.PmxSkinning
import com.micheanl.libmmd.format.pmx.PmxSphereMode
import com.micheanl.libmmd.format.pmx.PmxToonTexture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeformableRegionExtractorTest {
	@Test
	fun `extracts a weighted skirt region and bakes structural topology`() {
		val asset = DeformableRegionExtractor.extract(
			geometry = quad(),
			materials = listOf(material("Fabric", 6)),
			boneNames = listOf("qunzi_0"),
			configuration = DeformableExtractionConfiguration(),
		)

		assertEquals(4, asset.particles.size)
		assertEquals(2, asset.simulatedVertexCount)
		assertEquals(1, asset.regions.size)
		assertEquals(DeformableRegionType.SKIRT, asset.regions.single().type)
		assertFalse(asset.regions.single().closed)
		assertEquals(5, asset.distanceConstraints.size)
		assertEquals(1, asset.bendingConstraints.size)
		assertEquals(0, asset.volumeConstraints.size)
	}

	private fun quad(): PmxGeometry {
		val bones = IntArray(4 * PmxSkinning.MAX_INFLUENCES) { -1 }
		val weights = FloatArray(bones.size)
		repeat(4) { vertex ->
			bones[vertex * PmxSkinning.MAX_INFLUENCES] = 0
			weights[vertex * PmxSkinning.MAX_INFLUENCES] = 1f
		}
		return PmxGeometry(
			positions = floatArrayOf(-1f, 1f, 0f, 1f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, 0f),
			normals = FloatArray(12),
			textureCoordinates = FloatArray(8),
			additionalTextureCoordinates = floatArrayOf(),
			additionalUvChannels = 0,
			skinning = PmxSkinning(ByteArray(4), bones, weights, null),
			edgeScales = FloatArray(4),
			triangleIndices = intArrayOf(0, 2, 1, 1, 2, 3),
		)
	}

	private fun material(name: String, indexCount: Int) = PmxMaterial(
		name = name,
		englishName = name,
		diffuse = PmxRgba(1f, 1f, 1f, 1f),
		specular = PmxRgb(0f, 0f, 0f),
		specularStrength = 0f,
		ambient = PmxRgb(0f, 0f, 0f),
		drawFlags = PmxDrawFlags(0),
		edgeColor = PmxRgba(0f, 0f, 0f, 0f),
		edgeScale = 0f,
		textureIndex = -1,
		sphereTextureIndex = -1,
		sphereMode = PmxSphereMode.DISABLED,
		toonTexture = PmxToonTexture.Shared(0),
		metadata = "",
		firstIndex = 0,
		indexCount = indexCount,
	)
}
