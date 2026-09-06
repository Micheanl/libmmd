package com.micheanl.libmmd.physics

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3fc
import physx.PxTopLevelFunctions
import physx.common.PxIDENTITYEnum
import physx.common.PxTransform
import physx.common.PxVec3
import physx.extensions.PxD6AxisEnum
import physx.extensions.PxD6Joint
import physx.extensions.PxD6MotionEnum
import physx.extensions.PxJointActorIndexEnum
import physx.physics.PxRigidDynamic

class PhysicsGrab internal constructor(
	private val runtime: PhysicsWorld.Runtime,
	val rig: PhysicsRig,
	val rigidBodyIndex: Int,
	private val actor: PxRigidDynamic,
	worldPosition: Vector3fc,
	private val onClose: (PhysicsGrab) -> Unit,
) : AutoCloseable {
	private val nativePosition = PxVec3()
	private val nativeTransform = PxTransform(PxIDENTITYEnum.PxIdentity)
	private val joint: PxD6Joint
	private val currentPosition = Vector3f(worldPosition)
	private var closed = false

	val position: Vector3f
		get() = Vector3f(currentPosition)

	val isClosed: Boolean
		get() = closed

	init {
		require(worldPosition.isFinite) { "Grab position must be finite" }
		val actorPose = actor.globalPose
		val actorPosition = actorPose.p
		val actorRotation = actorPose.q
		val actorMatrix = Matrix4f()
			.translation(actorPosition.x, actorPosition.y, actorPosition.z)
			.rotate(Quaternionf(actorRotation.x, actorRotation.y, actorRotation.z, actorRotation.w))
		val localPosition = actorMatrix.invert().transformPosition(Vector3f(worldPosition))
		val worldFrame = PxTransform(PxIDENTITYEnum.PxIdentity)
		val actorFrame = PxTransform(PxIDENTITYEnum.PxIdentity)
		try {
			setPosition(worldFrame, worldPosition)
			setPosition(actorFrame, localPosition)
			joint = checkNotNull(
				PxTopLevelFunctions.D6JointCreate(runtime.physics, null, worldFrame, actor, actorFrame),
			) { "Unable to create grab constraint" }
		} finally {
			actorFrame.destroy()
			worldFrame.destroy()
		}
		joint.setMotion(PxD6AxisEnum.eX, PxD6MotionEnum.eLOCKED)
		joint.setMotion(PxD6AxisEnum.eY, PxD6MotionEnum.eLOCKED)
		joint.setMotion(PxD6AxisEnum.eZ, PxD6MotionEnum.eLOCKED)
		joint.setMotion(PxD6AxisEnum.eTWIST, PxD6MotionEnum.eFREE)
		joint.setMotion(PxD6AxisEnum.eSWING1, PxD6MotionEnum.eFREE)
		joint.setMotion(PxD6AxisEnum.eSWING2, PxD6MotionEnum.eFREE)
		actor.wakeUp()
	}

	fun moveTo(worldPosition: Vector3fc) {
		check(!closed) { "Physics grab is closed" }
		require(worldPosition.isFinite) { "Grab position must be finite" }
		currentPosition.set(worldPosition)
		setPosition(nativeTransform, worldPosition)
		joint.setLocalPose(PxJointActorIndexEnum.eACTOR0, nativeTransform)
		actor.wakeUp()
	}

	override fun close() {
		if (closed) return
		closed = true
		onClose(this)
		joint.release()
		nativeTransform.destroy()
		nativePosition.destroy()
	}

	private fun setPosition(transform: PxTransform, position: Vector3fc) {
		nativePosition.x = position.x()
		nativePosition.y = position.y()
		nativePosition.z = position.z()
		transform.setP(nativePosition)
	}
}
