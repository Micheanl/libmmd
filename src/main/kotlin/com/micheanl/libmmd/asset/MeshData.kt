package com.micheanl.libmmd.asset

import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.micheanl.libmmd.format.pmx.PmxSkinning
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MeshIndexType(val byteCount: Int) {
	SHORT(Short.SIZE_BYTES),
	INT(Int.SIZE_BYTES),
}

class MeshData private constructor(
	private val vertices: ByteBuffer,
	private val skinning: ByteBuffer,
	private val indices: ByteBuffer,
	val vertexCount: Int,
	val indexCount: Int,
	val indexType: MeshIndexType,
) {
	fun vertexBytes(): ByteBuffer = vertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun skinningBytes(): ByteBuffer = skinning.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun indexBytes(): ByteBuffer = indices.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	companion object {
		const val VERTEX_STRIDE: Int = 28
		const val SKINNING_STRIDE: Int = 32

		fun from(geometry: PmxGeometry): MeshData {
			val vertexSize = Math.multiplyExact(geometry.vertexCount, VERTEX_STRIDE)
			val vertexBytes = ByteBuffer.allocateDirect(vertexSize).order(ByteOrder.nativeOrder())
			repeat(geometry.vertexCount) { vertexIndex ->
				val positionOffset = vertexIndex * 3
				val uvOffset = vertexIndex * 2
				vertexBytes.putFloat(geometry.positions[positionOffset])
				vertexBytes.putFloat(geometry.positions[positionOffset + 1])
				vertexBytes.putFloat(-geometry.positions[positionOffset + 2])
				vertexBytes.putFloat(geometry.textureCoordinates[uvOffset])
				vertexBytes.putFloat(geometry.textureCoordinates[uvOffset + 1])
				repeat(4) { vertexBytes.put(0xFF.toByte()) }
				vertexBytes.put(packNormal(geometry.normals[positionOffset]))
				vertexBytes.put(packNormal(geometry.normals[positionOffset + 1]))
				vertexBytes.put(packNormal(-geometry.normals[positionOffset + 2]))
				vertexBytes.put(0)
			}
			vertexBytes.flip()
			val skinningSize = Math.multiplyExact(geometry.vertexCount, SKINNING_STRIDE)
			val skinningBytes = ByteBuffer.allocateDirect(skinningSize).order(ByteOrder.nativeOrder())
			repeat(geometry.vertexCount) { vertexIndex ->
				val boneIndices = IntArray(PmxSkinning.MAX_INFLUENCES)
				val weights = FloatArray(PmxSkinning.MAX_INFLUENCES)
				var weightSum = 0f
				repeat(PmxSkinning.MAX_INFLUENCES) { influenceIndex ->
					val boneIndex = geometry.skinning.boneIndex(vertexIndex, influenceIndex)
					val weight = geometry.skinning.boneWeight(vertexIndex, influenceIndex)
					if (boneIndex >= 0 && weight.isFinite() && weight > 0f) {
						boneIndices[influenceIndex] = boneIndex + 1
						weights[influenceIndex] = weight
						weightSum += weight
					}
				}
				if (!weightSum.isFinite() || weightSum <= 0f) {
					boneIndices.fill(0)
					weights.fill(0f)
					weights[0] = 1f
					weightSum = 1f
				}
				boneIndices.forEach(skinningBytes::putInt)
				weights.forEach { skinningBytes.putFloat(it / weightSum) }
			}
			skinningBytes.flip()

			val indexType = if (geometry.triangleIndices.maxOrNull() ?: 0 <= 0xFFFF) {
				MeshIndexType.SHORT
			} else {
				MeshIndexType.INT
			}
			val indexSize = Math.multiplyExact(geometry.triangleIndices.size, indexType.byteCount)
			val indexBytes = ByteBuffer.allocateDirect(indexSize).order(ByteOrder.nativeOrder())
			for (index in geometry.triangleIndices.indices step 3) {
				putIndex(indexBytes, geometry.triangleIndices[index], indexType)
				putIndex(indexBytes, geometry.triangleIndices[index + 2], indexType)
				putIndex(indexBytes, geometry.triangleIndices[index + 1], indexType)
			}
			indexBytes.flip()

			return MeshData(
				vertices = vertexBytes,
				skinning = skinningBytes,
				indices = indexBytes,
				vertexCount = geometry.vertexCount,
				indexCount = geometry.triangleIndices.size,
				indexType = indexType,
			)
		}

		private fun packNormal(value: Float): Byte =
			(value.coerceIn(-1f, 1f) * 127f).toInt().toByte()

		private fun putIndex(buffer: ByteBuffer, index: Int, type: MeshIndexType) {
			when (type) {
				MeshIndexType.SHORT -> buffer.putShort(index.toShort())
				MeshIndexType.INT -> buffer.putInt(index)
			}
		}
	}
}
