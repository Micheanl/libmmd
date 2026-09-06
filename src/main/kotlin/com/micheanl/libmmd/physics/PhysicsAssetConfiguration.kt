package com.micheanl.libmmd.physics

data class PhysicsAssetConfiguration(
	val enabled: Boolean = true,
	val autoGenerateBodyColliders: Boolean = false,
	val skirtSelfCollision: Boolean = true,
	val hairSelfCollision: Boolean = true,
	val body: BodyPhysicsConfiguration = BodyPhysicsConfiguration(
		staticFrictionMultiplier = 1.15f,
	),
	val skirt: BodyPhysicsConfiguration = BodyPhysicsConfiguration(
		staticFrictionMultiplier = 0.8f,
		dynamicFrictionMultiplier = 0.65f,
		linearDampingMultiplier = 1.15f,
		angularDampingMultiplier = 1.25f,
	),
	val hair: BodyPhysicsConfiguration = BodyPhysicsConfiguration(
		staticFrictionMultiplier = 0.7f,
		dynamicFrictionMultiplier = 0.55f,
		linearDampingMultiplier = 1.1f,
		angularDampingMultiplier = 1.2f,
	),
	val other: BodyPhysicsConfiguration = BodyPhysicsConfiguration(),
	val solverPositionIterations: Int = 16,
	val solverVelocityIterations: Int = 6,
	val groundedPositionIterations: Int = 20,
	val groundedVelocityIterations: Int = 8,
	val continuousCollision: Boolean = true,
	val automaticColliderRadiusRatio: Float = 0.18f,
	val secondaryMotion: SecondaryMotionConfiguration = SecondaryMotionConfiguration(),
	val legCollision: LegCollisionConfiguration = LegCollisionConfiguration(),
	val lod: SecondaryPhysicsLodConfiguration = SecondaryPhysicsLodConfiguration(),
) {
	init {
		require(solverPositionIterations in 1..255)
		require(solverVelocityIterations in 1..255)
		require(groundedPositionIterations in 1..255)
		require(groundedVelocityIterations in 1..255)
		require(automaticColliderRadiusRatio.isFinite() && automaticColliderRadiusRatio in 0.05f..0.45f)
	}

	internal fun settings(role: PhysicsBodyRole): BodyPhysicsConfiguration = when (role) {
		PhysicsBodyRole.BODY -> body
		PhysicsBodyRole.SKIRT -> skirt
		PhysicsBodyRole.HAIR -> hair
		PhysicsBodyRole.ACCESSORY, PhysicsBodyRole.OTHER -> other
	}
}

data class SecondaryMotionConfiguration(
	val enabled: Boolean = true,
	val smoothingHalfLife: Float = 0.08f,
	val skirtVelocityResponse: Float = 0.035f,
	val hairVelocityResponse: Float = 0.025f,
	val skirtAccelerationResponse: Float = 0.08f,
	val hairAccelerationResponse: Float = 0.055f,
	val maximumCharacterAcceleration: Float = 18f,
	val maximumAppliedAcceleration: Float = 3f,
) {
	init {
		listOf(
			smoothingHalfLife,
			skirtVelocityResponse,
			hairVelocityResponse,
			skirtAccelerationResponse,
			hairAccelerationResponse,
			maximumCharacterAcceleration,
			maximumAppliedAcceleration,
		).forEach { require(it.isFinite() && it >= 0f) }
		require(smoothingHalfLife > 0f)
		require(maximumCharacterAcceleration > 0f)
		require(maximumAppliedAcceleration > 0f)
	}
}

data class LegCollisionConfiguration(
	val enabled: Boolean = true,
	val predictionSeconds: Float = 0.06f,
	val maximumContactPadding: Float = 0.09f,
) {
	init {
		require(predictionSeconds.isFinite() && predictionSeconds in 0f..0.25f)
		require(maximumContactPadding.isFinite() && maximumContactPadding in 0f..0.5f)
	}
}

data class SecondaryPhysicsLodConfiguration(
	val enabled: Boolean = true,
	val hysteresis: Float = 2f,
	val skirtReducedDistance: Float = 16f,
	val skirtSuspendedDistance: Float = 32f,
	val hairReducedDistance: Float = 12f,
	val hairSuspendedDistance: Float = 24f,
	val reducedPositionIterations: Int = 8,
	val reducedVelocityIterations: Int = 2,
) {
	init {
		listOf(
			hysteresis,
			skirtReducedDistance,
			skirtSuspendedDistance,
			hairReducedDistance,
			hairSuspendedDistance,
		).forEach { require(it.isFinite() && it >= 0f) }
		require(skirtSuspendedDistance > skirtReducedDistance + hysteresis)
		require(hairSuspendedDistance > hairReducedDistance + hysteresis)
		require(reducedPositionIterations in 1..255)
		require(reducedVelocityIterations in 1..255)
	}
}

data class BodyPhysicsConfiguration(
	val staticFrictionMultiplier: Float = 1f,
	val dynamicFrictionMultiplier: Float = 1f,
	val restitutionMultiplier: Float = 1f,
	val linearDampingMultiplier: Float = 1f,
	val angularDampingMultiplier: Float = 1f,
) {
	init {
		listOf(
			staticFrictionMultiplier,
			dynamicFrictionMultiplier,
			restitutionMultiplier,
			linearDampingMultiplier,
			angularDampingMultiplier,
		).forEach { require(it.isFinite() && it >= 0f) }
	}
}

enum class PhysicsBodyRole {
	BODY,
	SKIRT,
	HAIR,
	ACCESSORY,
	OTHER,
}

enum class PhysicsDetailLevel {
	FULL,
	REDUCED,
	SUSPENDED,
}
