package com.micheanl.libmmd.format.pmx

data class PmxRgb(
	val red: Float,
	val green: Float,
	val blue: Float,
)

data class PmxRgba(
	val red: Float,
	val green: Float,
	val blue: Float,
	val alpha: Float,
)

@JvmInline
value class PmxDrawFlags(val bits: Int) {
	val disablesCulling: Boolean get() = contains(NO_CULL)
	val castsGroundShadow: Boolean get() = contains(GROUND_SHADOW)
	val castsSelfShadow: Boolean get() = contains(DRAW_SHADOW)
	val receivesSelfShadow: Boolean get() = contains(RECEIVE_SHADOW)
	val rendersEdge: Boolean get() = contains(HAS_EDGE)
	val usesVertexColor: Boolean get() = contains(VERTEX_COLOR)
	val drawsPoints: Boolean get() = contains(POINT_DRAWING)
	val drawsLines: Boolean get() = contains(LINE_DRAWING)

	private fun contains(mask: Int): Boolean = bits and mask != 0

	private companion object {
		const val NO_CULL = 1 shl 0
		const val GROUND_SHADOW = 1 shl 1
		const val DRAW_SHADOW = 1 shl 2
		const val RECEIVE_SHADOW = 1 shl 3
		const val HAS_EDGE = 1 shl 4
		const val VERTEX_COLOR = 1 shl 5
		const val POINT_DRAWING = 1 shl 6
		const val LINE_DRAWING = 1 shl 7
	}
}

enum class PmxSphereMode(val encodedValue: Int) {
	DISABLED(0),
	MULTIPLY(1),
	ADDITIVE(2),
	ADDITIONAL_UV(3),
}

sealed interface PmxToonTexture {
	data class Custom(val textureIndex: Int) : PmxToonTexture
	data class Shared(val slot: Int) : PmxToonTexture
}

data class PmxMaterial(
	val name: String,
	val englishName: String,
	val diffuse: PmxRgba,
	val specular: PmxRgb,
	val specularStrength: Float,
	val ambient: PmxRgb,
	val drawFlags: PmxDrawFlags,
	val edgeColor: PmxRgba,
	val edgeScale: Float,
	val textureIndex: Int,
	val sphereTextureIndex: Int,
	val sphereMode: PmxSphereMode,
	val toonTexture: PmxToonTexture,
	val metadata: String,
	val firstIndex: Int,
	val indexCount: Int,
)

data class PmxMaterialAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
)
