package com.micheanl.libmmd.format.pmx

enum class PmxSkinningMode(val encodedValue: Int) {
	BDEF1(0),
	BDEF2(1),
	BDEF4(2),
	SDEF(3),
	QDEF(4),
}

class PmxSkinning internal constructor(
	private val modes: ByteArray,
	val boneIndices: IntArray,
	val boneWeights: FloatArray,
	val sdefParameters: FloatArray?,
) {
	val vertexCount: Int
		get() = modes.size

	fun modeAt(vertexIndex: Int): PmxSkinningMode = PmxSkinningMode.entries[modes[vertexIndex].toInt()]

	fun boneIndex(vertexIndex: Int, influenceIndex: Int): Int =
		boneIndices[vertexIndex * MAX_INFLUENCES + influenceIndex]

	fun boneWeight(vertexIndex: Int, influenceIndex: Int): Float =
		boneWeights[vertexIndex * MAX_INFLUENCES + influenceIndex]

	companion object {
		const val MAX_INFLUENCES: Int = 4
		const val SDEF_COMPONENTS: Int = 9
	}
}

class PmxGeometry internal constructor(
	val positions: FloatArray,
	val normals: FloatArray,
	val textureCoordinates: FloatArray,
	val additionalTextureCoordinates: FloatArray,
	val additionalUvChannels: Int,
	val skinning: PmxSkinning,
	val edgeScales: FloatArray,
	val triangleIndices: IntArray,
) {
	val vertexCount: Int
		get() = edgeScales.size

	val triangleCount: Int
		get() = triangleIndices.size / 3
}

data class PmxGeometryAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
)
