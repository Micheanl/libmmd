package com.micheanl.libmmd.player

import com.micheanl.libmmd.animation.AnimationGraphSnapshot
import org.joml.Vector3f

data class AnimationDebugSnapshot(
	val bones: List<BoneDebugSegment>,
	val joints: List<JointDebugAxes>,
	val leftFoot: FootDebugPoint?,
	val rightFoot: FootDebugPoint?,
	val labelPosition: Vector3f,
	val graph: AnimationGraphSnapshot,
	val strideScale: Float,
	val orientationRadians: Float,
)

data class BoneDebugSegment(val parent: Vector3f, val child: Vector3f)

data class JointDebugAxes(
	val origin: Vector3f,
	val x: Vector3f,
	val y: Vector3f,
	val z: Vector3f,
)

data class FootDebugPoint(
	val foot: Vector3f,
	val target: Vector3f?,
	val contact: Float,
	val locked: Boolean,
)
