package com.micheanl.libmmd.format.pmx

enum class PmxRigidBodyShape(val encodedValue: Int) {
	SPHERE(0),
	BOX(1),
	CAPSULE(2),
}

enum class PmxRigidBodyMode(val encodedValue: Int) {
	FOLLOW_BONE(0),
	PHYSICS(1),
	PHYSICS_WITH_BONE(2),
}

data class PmxRigidBody(
	val name: String,
	val englishName: String,
	val boneIndex: Int,
	val collisionGroup: Int,
	val collisionExclusionMask: Int,
	val shape: PmxRigidBodyShape,
	val size: PmxVector3,
	val position: PmxVector3,
	val rotation: PmxVector3,
	val mass: Float,
	val linearDamping: Float,
	val angularDamping: Float,
	val restitution: Float,
	val friction: Float,
	val mode: PmxRigidBodyMode,
)

data class PmxRigidBodyAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
	val morphs: List<PmxMorph>,
	val displayFrames: List<PmxDisplayFrame>,
	val rigidBodies: List<PmxRigidBody>,
)
