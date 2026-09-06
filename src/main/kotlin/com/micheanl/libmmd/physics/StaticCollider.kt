package com.micheanl.libmmd.physics

import org.joml.Vector3fc
import physx.common.PxTransform
import physx.common.PxVec3
import physx.geometry.PxBoxGeometry
import physx.physics.PxFilterData
import physx.physics.PxMaterial
import physx.physics.PxRigidStatic
import physx.physics.PxShape
import physx.physics.PxShapeFlagEnum
import physx.physics.PxShapeFlags

class StaticCollider internal constructor(
	private val runtime: PhysicsWorld.Runtime,
	center: Vector3fc,
	halfExtents: Vector3fc,
	friction: Float,
	restitution: Float,
	collisionGroup: Int,
	collisionMask: Int,
	private val onClose: (StaticCollider) -> Unit,
) : AutoCloseable {
	private val material: PxMaterial
	private val shape: PxShape
	private val actor: PxRigidStatic
	private var closed = false

	init {
		require(center.isFinite) { "Collider center must be finite" }
		require(halfExtents.isFinite && halfExtents.x() > 0f && halfExtents.y() > 0f && halfExtents.z() > 0f) {
			"Collider half extents must be finite and positive"
		}
		require(friction.isFinite() && friction >= 0f) { "Collider friction must be finite and non-negative" }
		require(restitution.isFinite() && restitution in 0f..1f) { "Collider restitution must be within 0..1" }
		require(collisionGroup in 0 until COLLISION_GROUP_COUNT) { "Collider collision group must be within 0..15" }
		material = checkNotNull(runtime.physics.createMaterial(friction, friction, restitution)) {
			"Unable to create collider material"
		}
		val geometry = PxBoxGeometry(halfExtents.x(), halfExtents.y(), halfExtents.z())
		val flags = PxShapeFlags(
			(PxShapeFlagEnum.eSIMULATION_SHAPE.value or PxShapeFlagEnum.eSCENE_QUERY_SHAPE.value).toByte(),
		)
		shape = try {
			checkNotNull(runtime.physics.createShape(geometry, material, true, flags)) {
				"Unable to create collider shape"
			}
		} finally {
			flags.destroy()
			geometry.destroy()
		}
		val filterData = PxFilterData(1 shl collisionGroup, collisionMask and COLLISION_GROUP_MASK, 0, 0)
		try {
			shape.setSimulationFilterData(filterData)
			shape.setQueryFilterData(filterData)
		} finally {
			filterData.destroy()
		}
		val position = PxVec3(center.x(), center.y(), center.z())
		val transform = PxTransform(position)
		try {
			actor = checkNotNull(runtime.physics.createRigidStatic(transform)) { "Unable to create static collider" }
		} finally {
			transform.destroy()
			position.destroy()
		}
		check(actor.attachShape(shape)) { "Unable to attach static collider shape" }
		check(runtime.scene.addActor(actor)) { "Unable to add static collider to PhysX scene" }
	}

	override fun close() {
		if (closed) return
		closed = true
		onClose(this)
		runtime.scene.removeActor(actor)
		actor.release()
		shape.release()
		material.release()
	}

	private companion object {
		const val COLLISION_GROUP_COUNT = 16
		const val COLLISION_GROUP_MASK = 0xffff
	}
}
