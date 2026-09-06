package com.micheanl.libmmd.format.pmx

sealed interface PmxDisplayElement

data class PmxBoneDisplayElement(
	val boneIndex: Int,
) : PmxDisplayElement

data class PmxMorphDisplayElement(
	val morphIndex: Int,
) : PmxDisplayElement

data class PmxDisplayFrame(
	val name: String,
	val englishName: String,
	val isSpecial: Boolean,
	val elements: List<PmxDisplayElement>,
)

data class PmxDisplayFrameAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
	val morphs: List<PmxMorph>,
	val displayFrames: List<PmxDisplayFrame>,
)
