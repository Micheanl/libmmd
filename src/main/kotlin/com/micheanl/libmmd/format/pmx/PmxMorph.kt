package com.micheanl.libmmd.format.pmx

data class PmxVector4(
	val x: Float,
	val y: Float,
	val z: Float,
	val w: Float,
)

enum class PmxMorphPanel(val encodedValue: Int) {
	RESERVED(0),
	EYEBROW(1),
	EYE(2),
	LIP(3),
	OTHER(4),
}

enum class PmxMorphType(
	val encodedValue: Int,
	val uvChannel: Int? = null,
) {
	GROUP(0),
	VERTEX(1),
	BONE(2),
	UV(3, 0),
	ADDITIONAL_UV_1(4, 1),
	ADDITIONAL_UV_2(5, 2),
	ADDITIONAL_UV_3(6, 3),
	ADDITIONAL_UV_4(7, 4),
	MATERIAL(8),
	FLIP(9),
	IMPULSE(10),
}

enum class PmxMaterialMorphOperation(val encodedValue: Int) {
	MULTIPLY(0),
	ADDITIVE(1),
}

sealed interface PmxMorphOffset

data class PmxGroupMorphOffset(
	val morphIndex: Int,
	val influence: Float,
) : PmxMorphOffset

data class PmxVertexMorphOffset(
	val vertexIndex: Int,
	val translation: PmxVector3,
) : PmxMorphOffset

data class PmxBoneMorphOffset(
	val boneIndex: Int,
	val translation: PmxVector3,
	val rotation: PmxVector4,
) : PmxMorphOffset

data class PmxUvMorphOffset(
	val vertexIndex: Int,
	val uvChannel: Int,
	val displacement: PmxVector4,
) : PmxMorphOffset

data class PmxMaterialMorphOffset(
	val materialIndex: Int,
	val operation: PmxMaterialMorphOperation,
	val diffuse: PmxRgba,
	val specular: PmxRgb,
	val specularStrength: Float,
	val ambient: PmxRgb,
	val edgeColor: PmxRgba,
	val edgeScale: Float,
	val textureTint: PmxRgba,
	val sphereTint: PmxRgba,
	val toonTint: PmxRgba,
) : PmxMorphOffset

data class PmxFlipMorphOffset(
	val morphIndex: Int,
	val influence: Float,
) : PmxMorphOffset

data class PmxImpulseMorphOffset(
	val rigidBodyIndex: Int,
	val isLocal: Boolean,
	val velocity: PmxVector3,
	val torque: PmxVector3,
) : PmxMorphOffset

data class PmxMorph(
	val name: String,
	val englishName: String,
	val panel: PmxMorphPanel,
	val type: PmxMorphType,
	val offsets: List<PmxMorphOffset>,
)

data class PmxMorphAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
	val morphs: List<PmxMorph>,
)
