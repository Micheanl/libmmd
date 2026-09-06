package com.micheanl.libmmd.format.pmx

data class PmxVector3(
	val x: Float,
	val y: Float,
	val z: Float,
)

@JvmInline
value class PmxBoneFlags(val bits: Int) {
	val hasIndexedTail: Boolean get() = contains(INDEXED_TAIL)
	val canRotate: Boolean get() = contains(ROTATABLE)
	val canTranslate: Boolean get() = contains(TRANSLATABLE)
	val isVisible: Boolean get() = contains(VISIBLE)
	val isEnabled: Boolean get() = contains(ENABLED)
	val usesInverseKinematics: Boolean get() = contains(INVERSE_KINEMATICS)
	val inheritsRotation: Boolean get() = contains(INHERIT_ROTATION)
	val inheritsTranslation: Boolean get() = contains(INHERIT_TRANSLATION)
	val hasFixedAxis: Boolean get() = contains(FIXED_AXIS)
	val hasLocalAxes: Boolean get() = contains(LOCAL_AXES)
	val deformsAfterPhysics: Boolean get() = contains(AFTER_PHYSICS)
	val hasExternalParent: Boolean get() = contains(EXTERNAL_PARENT)

	private fun contains(mask: Int): Boolean = bits and mask != 0

	private companion object {
		const val INDEXED_TAIL = 1 shl 0
		const val ROTATABLE = 1 shl 1
		const val TRANSLATABLE = 1 shl 2
		const val VISIBLE = 1 shl 3
		const val ENABLED = 1 shl 4
		const val INVERSE_KINEMATICS = 1 shl 5
		const val INHERIT_ROTATION = 1 shl 8
		const val INHERIT_TRANSLATION = 1 shl 9
		const val FIXED_AXIS = 1 shl 10
		const val LOCAL_AXES = 1 shl 11
		const val AFTER_PHYSICS = 1 shl 12
		const val EXTERNAL_PARENT = 1 shl 13
	}
}

sealed interface PmxBoneTail {
	data class Offset(val offset: PmxVector3) : PmxBoneTail
	data class LinkedBone(val boneIndex: Int) : PmxBoneTail
}

data class PmxBoneInheritance(
	val sourceBoneIndex: Int,
	val influence: Float,
)

data class PmxLocalAxes(
	val xAxis: PmxVector3,
	val zAxis: PmxVector3,
)

data class PmxIkAngleLimits(
	val minimum: PmxVector3,
	val maximum: PmxVector3,
)

data class PmxIkLink(
	val boneIndex: Int,
	val angleLimits: PmxIkAngleLimits?,
)

data class PmxInverseKinematics(
	val targetBoneIndex: Int,
	val iterationCount: Int,
	val angleLimit: Float,
	val links: List<PmxIkLink>,
)

data class PmxBone(
	val name: String,
	val englishName: String,
	val position: PmxVector3,
	val parentBoneIndex: Int,
	val deformLayer: Int,
	val flags: PmxBoneFlags,
	val tail: PmxBoneTail,
	val inheritance: PmxBoneInheritance?,
	val fixedAxis: PmxVector3?,
	val localAxes: PmxLocalAxes?,
	val externalParentKey: Int?,
	val inverseKinematics: PmxInverseKinematics?,
)

data class PmxBoneAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
)
