package com.micheanl.libmmd.format.pmx

enum class PmxJointType(val encodedValue: Int) {
	SPRING_SIX_DOF(0),
	SIX_DOF(1),
	POINT_TO_POINT(2),
	CONE_TWIST(3),
	SLIDER(4),
	HINGE(5),
}

data class PmxVector3Range(
	val minimum: PmxVector3,
	val maximum: PmxVector3,
)

data class PmxJoint(
	val name: String,
	val englishName: String,
	val type: PmxJointType,
	val firstRigidBodyIndex: Int,
	val secondRigidBodyIndex: Int,
	val position: PmxVector3,
	val rotation: PmxVector3,
	val translationLimits: PmxVector3Range,
	val rotationLimits: PmxVector3Range,
	val translationSpring: PmxVector3,
	val rotationSpring: PmxVector3,
)

data class PmxJointAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
	val morphs: List<PmxMorph>,
	val displayFrames: List<PmxDisplayFrame>,
	val rigidBodies: List<PmxRigidBody>,
	val joints: List<PmxJoint>,
)
