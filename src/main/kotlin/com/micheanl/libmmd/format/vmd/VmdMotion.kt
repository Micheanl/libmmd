package com.micheanl.libmmd.format.vmd

data class VmdVector3(val x: Float, val y: Float, val z: Float)

data class VmdQuaternion(val x: Float, val y: Float, val z: Float, val w: Float)

data class VmdColor(val red: Float, val green: Float, val blue: Float)

data class VmdBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

data class VmdBoneInterpolation(
	val x: VmdBezier,
	val y: VmdBezier,
	val z: VmdBezier,
	val rotation: VmdBezier,
)

data class VmdBoneKeyframe(
	val boneName: String,
	val frame: Int,
	val translation: VmdVector3,
	val rotation: VmdQuaternion,
	val interpolation: VmdBoneInterpolation,
)

data class VmdMorphKeyframe(val morphName: String, val frame: Int, val weight: Float)

data class VmdCameraKeyframe(
	val frame: Int,
	val distance: Float,
	val position: VmdVector3,
	val rotation: VmdVector3,
	val interpolation: List<Int>,
	val fieldOfView: Int,
	val perspective: Boolean,
)

data class VmdLightKeyframe(val frame: Int, val color: VmdColor, val position: VmdVector3)

data class VmdShadowKeyframe(val frame: Int, val mode: Int, val distance: Float)

data class VmdIkState(val name: String, val enabled: Boolean)

data class VmdIkKeyframe(val frame: Int, val visible: Boolean, val states: List<VmdIkState>)

data class VmdMotion(
	val modelName: String,
	val boneKeyframes: List<VmdBoneKeyframe>,
	val morphKeyframes: List<VmdMorphKeyframe>,
	val cameraKeyframes: List<VmdCameraKeyframe>,
	val lightKeyframes: List<VmdLightKeyframe>,
	val shadowKeyframes: List<VmdShadowKeyframe>,
	val ikKeyframes: List<VmdIkKeyframe>,
)
