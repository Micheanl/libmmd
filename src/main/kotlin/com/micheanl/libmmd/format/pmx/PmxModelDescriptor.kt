	package com.micheanl.libmmd.format.pmx

enum class PmxVersion(val encodedValue: Float) {
	V2_0(2.0f),
	V2_1(2.1f),
}

enum class PmxTextEncoding {
	UTF_16_LE,
	UTF_8,
}

enum class PmxIndexWidth(val byteCount: Int) {
	ONE(1),
	TWO(2),
	FOUR(4),
}

data class PmxIndexLayout(
	val vertex: PmxIndexWidth,
	val texture: PmxIndexWidth,
	val material: PmxIndexWidth,
	val bone: PmxIndexWidth,
	val morph: PmxIndexWidth,
	val rigidBody: PmxIndexWidth,
)

data class PmxFormat(
	val version: PmxVersion,
	val textEncoding: PmxTextEncoding,
	val additionalUvChannels: Int,
	val indices: PmxIndexLayout,
)

data class PmxModelMetadata(
	val name: String,
	val englishName: String,
	val description: String,
	val englishDescription: String,
)

data class PmxModelDescriptor(
	val format: PmxFormat,
	val metadata: PmxModelMetadata,
)
