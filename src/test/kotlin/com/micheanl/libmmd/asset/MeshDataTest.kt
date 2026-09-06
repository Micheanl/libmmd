package com.micheanl.libmmd.asset

import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.micheanl.libmmd.format.pmx.PmxSkinning
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshDataTest {
	@Test
	fun `converts PMX geometry to Minecraft vertex layout`() {
		val mesh = MeshData.from(triangleGeometry())
		val vertices = mesh.vertexBytes().order(ByteOrder.nativeOrder())

		assertEquals(3, mesh.vertexCount)
		assertEquals(3, mesh.indexCount)
		assertEquals(MeshIndexType.SHORT, mesh.indexType)
		assertEquals(3 * MeshData.VERTEX_STRIDE, vertices.remaining())
		assertEquals(1f, vertices.getFloat(0))
		assertEquals(2f, vertices.getFloat(4))
		assertEquals(-3f, vertices.getFloat(8))
		assertEquals(0.25f, vertices.getFloat(12))
		assertEquals(0.75f, vertices.getFloat(16))
		assertEquals(0xFF, vertices.get(20).toInt() and 0xFF)
		assertEquals(-127, vertices.get(26).toInt())

		val skinning = mesh.skinningBytes().order(ByteOrder.nativeOrder())
		assertEquals(3 * MeshData.SKINNING_STRIDE, skinning.remaining())
		assertEquals(3, skinning.getInt(0))
		assertEquals(4, skinning.getInt(4))
		assertEquals(0.25f, skinning.getFloat(16))
		assertEquals(0.75f, skinning.getFloat(20))
	}

	@Test
	fun `reverses triangle winding after changing handedness`() {
		val indices = MeshData.from(triangleGeometry()).indexBytes().order(ByteOrder.nativeOrder())

		assertEquals(0, indices.getShort(0).toInt() and 0xFFFF)
		assertEquals(2, indices.getShort(2).toInt() and 0xFFFF)
		assertEquals(1, indices.getShort(4).toInt() and 0xFFFF)
	}

	private fun triangleGeometry(): PmxGeometry = PmxGeometry(
		positions = floatArrayOf(
			1f, 2f, 3f,
			0f, 0f, 0f,
			1f, 0f, 0f,
		),
		normals = floatArrayOf(
			0f, 0f, 1f,
			0f, 0f, 1f,
			0f, 0f, 1f,
		),
		textureCoordinates = floatArrayOf(0.25f, 0.75f, 0f, 0f, 1f, 0f),
		additionalTextureCoordinates = floatArrayOf(),
		additionalUvChannels = 0,
		skinning = PmxSkinning(
			modes = ByteArray(3),
			boneIndices = intArrayOf(2, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
			boneWeights = floatArrayOf(0.25f, 0.75f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
			sdefParameters = null,
		),
		edgeScales = FloatArray(3),
		triangleIndices = intArrayOf(0, 1, 2),
	)
}
