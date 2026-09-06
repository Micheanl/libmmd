package com.micheanl.libmmd.physics

import com.micheanl.libmmd.animation.HumanoidJoint
import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.Skeleton
import com.micheanl.libmmd.format.pmx.PmxModel
import com.micheanl.libmmd.format.pmx.PmxRigidBody
import com.micheanl.libmmd.format.pmx.PmxRigidBodyMode
import com.micheanl.libmmd.format.pmx.PmxRigidBodyShape
import com.micheanl.libmmd.format.pmx.PmxVector3
import org.joml.Quaternionf
import org.joml.Vector3f

internal class CharacterPhysicsAsset private constructor(
	val model: PmxModel,
	val configuration: PhysicsAssetConfiguration,
) {
	val collisions = CharacterCollisionProfile(model, configuration)

	companion object {
		fun prepare(model: PmxModel, skeleton: Skeleton, configuration: PhysicsAssetConfiguration): CharacterPhysicsAsset {
			if (!configuration.autoGenerateBodyColliders) return CharacterPhysicsAsset(model, configuration)
			val generated = generateBodyColliders(model, HumanoidRig.from(skeleton), configuration)
			val prepared = if (generated.isEmpty()) model else model.copy(rigidBodies = model.rigidBodies + generated)
			return CharacterPhysicsAsset(prepared, configuration)
		}

		private fun generateBodyColliders(
			model: PmxModel,
			rig: HumanoidRig,
			configuration: PhysicsAssetConfiguration,
		): List<PmxRigidBody> {
			val occupiedBones = model.rigidBodies.map(PmxRigidBody::boneIndex).filter { it >= 0 }.toSet()
			val collisionGroup = (15 downTo 0).firstOrNull { group -> model.rigidBodies.none { it.collisionGroup == group } } ?: 15
			return BODY_SEGMENTS.mapNotNull { (joint, widthScale) ->
				val boneIndex = rig[joint]?.takeUnless(occupiedBones::contains) ?: return@mapNotNull null
				val direction = rig.direction(joint, Vector3f())
				val length = rig.length(joint)
				if (length <= 1.0e-4f || direction.lengthSquared() <= 1.0e-8f) return@mapNotNull null
				val start = model.bones[boneIndex].position
				val radius = (length * configuration.automaticColliderRadiusRatio * widthScale)
					.coerceAtMost(length * 0.42f)
				val cylinderLength = (length - radius * 2f).coerceAtLeast(length * 0.05f)
				val rawDirection = Vector3f(direction.x, direction.y, -direction.z)
				rawDirection.normalize()
				val rotation = Quaternionf().rotationTo(0f, 1f, 0f, rawDirection.x, rawDirection.y, rawDirection.z)
				val euler = rotation.getEulerAnglesXYZ(Vector3f())
				PmxRigidBody(
					name = "BodyProxy.${joint.name.lowercase().replace('_', '.')}",
					englishName = "BodyProxy.${joint.name.lowercase().replace('_', '.')}",
					boneIndex = boneIndex,
					collisionGroup = collisionGroup,
					collisionExclusionMask = 0xffff,
					shape = PmxRigidBodyShape.CAPSULE,
					size = PmxVector3(radius, cylinderLength, 0f),
					position = PmxVector3(
						start.x + rawDirection.x * length * 0.5f,
						start.y + rawDirection.y * length * 0.5f,
						start.z + rawDirection.z * length * 0.5f,
					),
					rotation = PmxVector3(euler.x, euler.y, euler.z),
					mass = 1f,
					linearDamping = 0.5f,
					angularDamping = 0.5f,
					restitution = 0f,
					friction = 0.6f,
					mode = PmxRigidBodyMode.FOLLOW_BONE,
				)
			}
		}

		private val BODY_SEGMENTS = listOf(
			HumanoidJoint.HIPS to 1.8f,
			HumanoidJoint.SPINE to 1.55f,
			HumanoidJoint.CHEST to 1.4f,
			HumanoidJoint.LEFT_UPPER_ARM to 0.9f,
			HumanoidJoint.LEFT_LOWER_ARM to 0.75f,
			HumanoidJoint.RIGHT_UPPER_ARM to 0.9f,
			HumanoidJoint.RIGHT_LOWER_ARM to 0.75f,
			HumanoidJoint.LEFT_UPPER_LEG to 1.15f,
			HumanoidJoint.LEFT_LOWER_LEG to 0.9f,
			HumanoidJoint.RIGHT_UPPER_LEG to 1.15f,
			HumanoidJoint.RIGHT_LOWER_LEG to 0.9f,
		)
	}
}
