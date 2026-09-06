package com.micheanl.libmmd.format.vmd

data class VmdDecodeLimits(
	val maxBoneKeyframes: Int = 2_000_000,
	val maxMorphKeyframes: Int = 1_000_000,
	val maxCameraKeyframes: Int = 500_000,
	val maxLightKeyframes: Int = 500_000,
	val maxShadowKeyframes: Int = 500_000,
	val maxIkKeyframes: Int = 500_000,
	val maxIkStates: Int = 2_000_000,
) {
	init {
		require(maxBoneKeyframes >= 0)
		require(maxMorphKeyframes >= 0)
		require(maxCameraKeyframes >= 0)
		require(maxLightKeyframes >= 0)
		require(maxShadowKeyframes >= 0)
		require(maxIkKeyframes >= 0)
		require(maxIkStates >= 0)
	}
}
