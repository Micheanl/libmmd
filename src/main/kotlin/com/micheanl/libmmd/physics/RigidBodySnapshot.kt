package com.micheanl.libmmd.physics

import org.joml.Matrix4f
import org.joml.Matrix4fc

class RigidBodySnapshot internal constructor(
	val index: Int,
	val name: String,
	val geometry: BodyGeometry,
	val dynamic: Boolean,
	val role: PhysicsBodyRole,
	val detailLevel: PhysicsDetailLevel,
	val contactOffset: Float,
	transform: Matrix4fc,
) {
	val transform = Matrix4f(transform)
}

sealed interface BodyGeometry {
	data class Sphere(val radius: Float) : BodyGeometry

	data class Box(
		val halfX: Float,
		val halfY: Float,
		val halfZ: Float,
	) : BodyGeometry

	data class Capsule(
		val radius: Float,
		val halfHeight: Float,
	) : BodyGeometry
}
