package com.micheanl.libmmd.format.pmx

data class PmxModel(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
	val morphs: List<PmxMorph>,
	val displayFrames: List<PmxDisplayFrame>,
	val rigidBodies: List<PmxRigidBody>,
	val joints: List<PmxJoint>,
	val softBodies: List<PmxSoftBody>,
)
