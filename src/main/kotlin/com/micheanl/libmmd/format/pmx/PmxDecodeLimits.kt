package com.micheanl.libmmd.format.pmx

data class PmxDecodeLimits(
	val maxVertices: Int = 1_000_000,
	val maxTriangleIndices: Int = 6_000_000,
	val maxTextures: Int = 4_096,
	val maxMaterials: Int = 4_096,
	val maxBones: Int = 65_536,
	val maxIkIterationsPerBone: Int = 1_024,
	val maxIkLinksPerBone: Int = 1_024,
	val maxMorphs: Int = 65_536,
	val maxMorphOffsets: Int = 4_000_000,
	val maxDisplayFrames: Int = 4_096,
	val maxDisplayFrameElements: Int = 1_000_000,
	val maxRigidBodies: Int = 65_536,
	val maxJoints: Int = 65_536,
	val maxSoftBodies: Int = 4_096,
	val maxSoftBodyAnchors: Int = 1_000_000,
	val maxSoftBodyPins: Int = 4_000_000,
) {
	init {
		require(maxVertices > 0)
		require(maxTriangleIndices > 0)
		require(maxTextures > 0)
		require(maxMaterials > 0)
		require(maxBones > 0)
		require(maxIkIterationsPerBone > 0)
		require(maxIkLinksPerBone > 0)
		require(maxMorphs > 0)
		require(maxMorphOffsets > 0)
		require(maxDisplayFrames > 0)
		require(maxDisplayFrameElements > 0)
		require(maxRigidBodies > 0)
		require(maxJoints > 0)
		require(maxSoftBodies > 0)
		require(maxSoftBodyAnchors > 0)
		require(maxSoftBodyPins > 0)
	}
}
