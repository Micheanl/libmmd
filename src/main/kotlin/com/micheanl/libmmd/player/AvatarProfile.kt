package com.micheanl.libmmd.player

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.micheanl.libmmd.animation.HumanoidJoint
import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.ProceduralPoseConfiguration
import com.micheanl.libmmd.physics.BodyPhysicsConfiguration
import com.micheanl.libmmd.physics.LegCollisionConfiguration
import com.micheanl.libmmd.physics.PhysicsAssetConfiguration
import com.micheanl.libmmd.physics.SecondaryMotionConfiguration
import com.micheanl.libmmd.physics.SecondaryPhysicsLodConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.PI

data class AvatarProfile(
	val forwardYawDegrees: Float = 0f,
	val scaleMultiplier: Float = 1f,
	val groundOffset: Float = 0f,
	val sourceMotionHeight: Float? = null,
	val boneMappings: Map<HumanoidJoint, String> = emptyMap(),
	val footPlacement: FootPlacementProfile = FootPlacementProfile(),
	val locomotion: LocomotionProfile = LocomotionProfile(),
	val physics: PhysicsAssetConfiguration = defaultPlayerPhysics(),
	val jointLimitsDegrees: Map<HumanoidJoint, Float> = defaultJointLimitsDegrees(),
) {
	init {
		require(forwardYawDegrees.isFinite() && forwardYawDegrees in -360f..360f)
		require(scaleMultiplier.isFinite() && scaleMultiplier in 0.05f..20f)
		require(groundOffset.isFinite())
		require(sourceMotionHeight == null || sourceMotionHeight.isFinite() && sourceMotionHeight > 0f)
		require(boneMappings.values.all { it.isNotBlank() && it.length <= 128 })
		require(boneMappings.values.size == boneMappings.values.toSet().size) { "Each semantic joint must use a distinct bone" }
		require(jointLimitsDegrees.values.all { it.isFinite() && it in 1f..179f })
	}

	fun proceduralConfiguration(): ProceduralPoseConfiguration {
		val limits = ProceduralPoseConfiguration.defaultJointLimits().toMutableMap()
		jointLimitsDegrees.forEach { (joint, degrees) -> limits[joint] = (degrees * PI / 180.0).toFloat() }
		return ProceduralPoseConfiguration(
			footPlacementEnabled = footPlacement.enabled,
			maximumFootReachRatio = footPlacement.maximumReachRatio,
			maximumPelvisOffsetRatio = footPlacement.maximumPelvisOffsetRatio,
			contactHalfLife = footPlacement.contactHalfLife,
			pelvisHalfLife = footPlacement.pelvisHalfLife,
			walkReferenceSpeed = locomotion.walkReferenceSpeed,
			sprintReferenceSpeed = locomotion.sprintReferenceSpeed,
			minimumStrideScale = locomotion.minimumStrideScale,
			maximumStrideScale = locomotion.maximumStrideScale,
			maximumOrientationRadians = (locomotion.maximumOrientationDegrees * PI / 180.0).toFloat(),
			maximumFootLockRatio = locomotion.maximumFootLockRatio,
			jointLimitsRadians = limits,
		)
	}

	fun physicsConfiguration(): PhysicsAssetConfiguration = physics

	companion object {
		fun defaultPlayerPhysics(): PhysicsAssetConfiguration = PhysicsAssetConfiguration(autoGenerateBodyColliders = true)

		fun automatic(rig: HumanoidRig): AvatarProfile = AvatarProfile(
			boneMappings = HumanoidJoint.entries.mapNotNull { joint ->
				rig[joint]?.let { joint to rig.skeleton.bones[it].name }
			}.toMap(),
		)

		fun defaultJointLimitsDegrees(): Map<HumanoidJoint, Float> = mapOf(
			HumanoidJoint.NECK to 70f,
			HumanoidJoint.HEAD to 85f,
			HumanoidJoint.LEFT_SHOULDER to 100f,
			HumanoidJoint.RIGHT_SHOULDER to 100f,
			HumanoidJoint.LEFT_LOWER_ARM to 155f,
			HumanoidJoint.RIGHT_LOWER_ARM to 155f,
			HumanoidJoint.LEFT_LOWER_LEG to 165f,
			HumanoidJoint.RIGHT_LOWER_LEG to 165f,
		)
	}
}

data class FootPlacementProfile(
	val enabled: Boolean = true,
	val maximumReachRatio: Float = 0.14f,
	val maximumPelvisOffsetRatio: Float = 0.055f,
	val contactHalfLife: Float = 0.035f,
	val pelvisHalfLife: Float = 0.06f,
) {
	init {
		require(maximumReachRatio.isFinite() && maximumReachRatio in 0.01f..0.5f)
		require(maximumPelvisOffsetRatio.isFinite() && maximumPelvisOffsetRatio in 0f..0.2f)
		require(contactHalfLife.isFinite() && contactHalfLife in 0.001f..1f)
		require(pelvisHalfLife.isFinite() && pelvisHalfLife in 0.001f..1f)
	}
}

data class LocomotionProfile(
	val walkReferenceSpeed: Float = 0.1f,
	val sprintReferenceSpeed: Float = 0.14f,
	val minimumStrideScale: Float = 0.65f,
	val maximumStrideScale: Float = 1.5f,
	val maximumOrientationDegrees: Float = 65f,
	val maximumFootLockRatio: Float = 0.2f,
) {
	init {
		require(walkReferenceSpeed.isFinite() && walkReferenceSpeed > 0f)
		require(sprintReferenceSpeed.isFinite() && sprintReferenceSpeed >= walkReferenceSpeed)
		require(minimumStrideScale.isFinite() && minimumStrideScale in 0.1f..1f)
		require(maximumStrideScale.isFinite() && maximumStrideScale in 1f..3f)
		require(maximumOrientationDegrees.isFinite() && maximumOrientationDegrees in 0f..180f)
		require(maximumFootLockRatio.isFinite() && maximumFootLockRatio in 0.01f..0.5f)
	}
}

object AvatarProfileStore {
	private const val VERSION = 1
	private const val MAX_PROFILE_BYTES = 64 * 1024L
	private val gson = GsonBuilder().setPrettyPrinting().create()

	fun loadOrCreate(path: Path, automaticProfile: AvatarProfile): AvatarProfile {
		if (Files.isRegularFile(path)) return read(path)
		write(path, automaticProfile)
		return automaticProfile
	}

	fun read(path: Path): AvatarProfile {
		require(Files.size(path) <= MAX_PROFILE_BYTES) { "Avatar profile exceeds $MAX_PROFILE_BYTES bytes" }
		return decode(Files.readString(path, StandardCharsets.UTF_8))
	}

	fun decode(json: String): AvatarProfile {
		val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
			?: throw IllegalArgumentException("Avatar profile root must be an object")
		require(root.int("version", VERSION) == VERSION) { "Unsupported avatar profile version" }
		return AvatarProfile(
			forwardYawDegrees = root.float("forwardYawDegrees", 0f),
			scaleMultiplier = root.float("scaleMultiplier", 1f),
			groundOffset = root.float("groundOffset", 0f),
			sourceMotionHeight = root.optionalFloat("sourceMotionHeight"),
			boneMappings = root.jointStringMap("boneMappings"),
			footPlacement = root.footPlacement(),
			locomotion = root.locomotion(),
			physics = root.physics(),
			jointLimitsDegrees = root.jointFloatMap("jointLimitsDegrees", AvatarProfile.defaultJointLimitsDegrees()),
		)
	}

	fun encode(profile: AvatarProfile): String {
		val root = JsonObject()
		root.addProperty("version", VERSION)
		root.addProperty("forwardYawDegrees", profile.forwardYawDegrees)
		root.addProperty("scaleMultiplier", profile.scaleMultiplier)
		root.addProperty("groundOffset", profile.groundOffset)
		profile.sourceMotionHeight?.let { root.addProperty("sourceMotionHeight", it) }
		root.add("boneMappings", JsonObject().also { target ->
			profile.boneMappings.toSortedMap(compareBy(HumanoidJoint::name)).forEach { (joint, bone) ->
				target.addProperty(joint.key(), bone)
			}
		})
		root.add("footPlacement", JsonObject().also { target ->
			target.addProperty("enabled", profile.footPlacement.enabled)
			target.addProperty("maximumReachRatio", profile.footPlacement.maximumReachRatio)
			target.addProperty("maximumPelvisOffsetRatio", profile.footPlacement.maximumPelvisOffsetRatio)
			target.addProperty("contactHalfLife", profile.footPlacement.contactHalfLife)
			target.addProperty("pelvisHalfLife", profile.footPlacement.pelvisHalfLife)
		})
		root.add("locomotion", JsonObject().also { target ->
			target.addProperty("walkReferenceSpeed", profile.locomotion.walkReferenceSpeed)
			target.addProperty("sprintReferenceSpeed", profile.locomotion.sprintReferenceSpeed)
			target.addProperty("minimumStrideScale", profile.locomotion.minimumStrideScale)
			target.addProperty("maximumStrideScale", profile.locomotion.maximumStrideScale)
			target.addProperty("maximumOrientationDegrees", profile.locomotion.maximumOrientationDegrees)
			target.addProperty("maximumFootLockRatio", profile.locomotion.maximumFootLockRatio)
		})
		root.add("physics", physics(profile.physics))
		root.add("jointLimitsDegrees", JsonObject().also { target ->
			profile.jointLimitsDegrees.toSortedMap(compareBy(HumanoidJoint::name)).forEach { (joint, limit) ->
				target.addProperty(joint.key(), limit)
			}
		})
		return gson.toJson(root) + "\n"
	}

	fun write(path: Path, profile: AvatarProfile) {
		val parent = path.toAbsolutePath().normalize().parent
		Files.createDirectories(parent)
		val temporary = Files.createTempFile(parent, ".avatar-profile-", ".json.tmp")
		try {
			Files.writeString(temporary, encode(profile), StandardCharsets.UTF_8)
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(temporary, path)
			}
		} finally {
			Files.deleteIfExists(temporary)
		}
	}

	private fun JsonObject.footPlacement(): FootPlacementProfile {
		val value = get("footPlacement") ?: return FootPlacementProfile()
		require(value.isJsonObject) { "footPlacement must be an object" }
		val source = value.asJsonObject
		return FootPlacementProfile(
			enabled = source.boolean("enabled", true),
			maximumReachRatio = source.float("maximumReachRatio", 0.14f),
			maximumPelvisOffsetRatio = source.float("maximumPelvisOffsetRatio", 0.055f),
			contactHalfLife = source.float("contactHalfLife", 0.035f),
			pelvisHalfLife = source.float("pelvisHalfLife", 0.06f),
		)
	}

	private fun JsonObject.locomotion(): LocomotionProfile {
		val value = get("locomotion") ?: return LocomotionProfile()
		require(value.isJsonObject) { "locomotion must be an object" }
		val source = value.asJsonObject
		return LocomotionProfile(
			walkReferenceSpeed = source.float("walkReferenceSpeed", 0.1f),
			sprintReferenceSpeed = source.float("sprintReferenceSpeed", 0.14f),
			minimumStrideScale = source.float("minimumStrideScale", 0.65f),
			maximumStrideScale = source.float("maximumStrideScale", 1.5f),
			maximumOrientationDegrees = source.float("maximumOrientationDegrees", 65f),
			maximumFootLockRatio = source.float("maximumFootLockRatio", 0.2f),
		)
	}

	private fun JsonObject.physics(): PhysicsAssetConfiguration {
		val defaults = AvatarProfile.defaultPlayerPhysics()
		val value = get("physics") ?: return defaults
		require(value.isJsonObject) { "physics must be an object" }
		val source = value.asJsonObject
		return PhysicsAssetConfiguration(
			enabled = source.boolean("enabled", defaults.enabled),
			autoGenerateBodyColliders = source.boolean("autoGenerateBodyColliders", defaults.autoGenerateBodyColliders),
			skirtSelfCollision = source.boolean("skirtSelfCollision", defaults.skirtSelfCollision),
			hairSelfCollision = source.boolean("hairSelfCollision", defaults.hairSelfCollision),
			body = source.bodyPhysics("body", defaults.body),
			skirt = source.bodyPhysics("skirt", defaults.skirt),
			hair = source.bodyPhysics("hair", defaults.hair),
			other = source.bodyPhysics("other", defaults.other),
			solverPositionIterations = source.int("solverPositionIterations", defaults.solverPositionIterations),
			solverVelocityIterations = source.int("solverVelocityIterations", defaults.solverVelocityIterations),
			groundedPositionIterations = source.int("groundedPositionIterations", defaults.groundedPositionIterations),
			groundedVelocityIterations = source.int("groundedVelocityIterations", defaults.groundedVelocityIterations),
			continuousCollision = source.boolean("continuousCollision", defaults.continuousCollision),
			automaticColliderRadiusRatio = source.float(
				"automaticColliderRadiusRatio",
				defaults.automaticColliderRadiusRatio,
			),
			secondaryMotion = source.secondaryMotion(defaults.secondaryMotion),
			legCollision = source.legCollision(defaults.legCollision),
			lod = source.physicsLod(defaults.lod),
		)
	}

	private fun JsonObject.bodyPhysics(name: String, defaults: BodyPhysicsConfiguration): BodyPhysicsConfiguration {
		val value = get(name) ?: return defaults
		require(value.isJsonObject) { "physics.$name must be an object" }
		val source = value.asJsonObject
		return BodyPhysicsConfiguration(
			staticFrictionMultiplier = source.float("staticFrictionMultiplier", defaults.staticFrictionMultiplier),
			dynamicFrictionMultiplier = source.float("dynamicFrictionMultiplier", defaults.dynamicFrictionMultiplier),
			restitutionMultiplier = source.float("restitutionMultiplier", defaults.restitutionMultiplier),
			linearDampingMultiplier = source.float("linearDampingMultiplier", defaults.linearDampingMultiplier),
			angularDampingMultiplier = source.float("angularDampingMultiplier", defaults.angularDampingMultiplier),
		)
	}

	private fun physics(source: PhysicsAssetConfiguration): JsonObject = JsonObject().also { target ->
		target.addProperty("enabled", source.enabled)
		target.addProperty("autoGenerateBodyColliders", source.autoGenerateBodyColliders)
		target.addProperty("skirtSelfCollision", source.skirtSelfCollision)
		target.addProperty("hairSelfCollision", source.hairSelfCollision)
		target.add("body", bodyPhysics(source.body))
		target.add("skirt", bodyPhysics(source.skirt))
		target.add("hair", bodyPhysics(source.hair))
		target.add("other", bodyPhysics(source.other))
		target.addProperty("solverPositionIterations", source.solverPositionIterations)
		target.addProperty("solverVelocityIterations", source.solverVelocityIterations)
		target.addProperty("groundedPositionIterations", source.groundedPositionIterations)
		target.addProperty("groundedVelocityIterations", source.groundedVelocityIterations)
		target.addProperty("continuousCollision", source.continuousCollision)
		target.addProperty("automaticColliderRadiusRatio", source.automaticColliderRadiusRatio)
		target.add("secondaryMotion", secondaryMotion(source.secondaryMotion))
		target.add("legCollision", legCollision(source.legCollision))
		target.add("lod", physicsLod(source.lod))
	}

	private fun bodyPhysics(source: BodyPhysicsConfiguration): JsonObject = JsonObject().also { target ->
		target.addProperty("staticFrictionMultiplier", source.staticFrictionMultiplier)
		target.addProperty("dynamicFrictionMultiplier", source.dynamicFrictionMultiplier)
		target.addProperty("restitutionMultiplier", source.restitutionMultiplier)
		target.addProperty("linearDampingMultiplier", source.linearDampingMultiplier)
		target.addProperty("angularDampingMultiplier", source.angularDampingMultiplier)
	}

	private fun JsonObject.secondaryMotion(defaults: SecondaryMotionConfiguration): SecondaryMotionConfiguration {
		val value = get("secondaryMotion") ?: return defaults
		require(value.isJsonObject) { "physics.secondaryMotion must be an object" }
		val source = value.asJsonObject
		return SecondaryMotionConfiguration(
			enabled = source.boolean("enabled", defaults.enabled),
			smoothingHalfLife = source.float("smoothingHalfLife", defaults.smoothingHalfLife),
			skirtVelocityResponse = source.float("skirtVelocityResponse", defaults.skirtVelocityResponse),
			hairVelocityResponse = source.float("hairVelocityResponse", defaults.hairVelocityResponse),
			skirtAccelerationResponse = source.float("skirtAccelerationResponse", defaults.skirtAccelerationResponse),
			hairAccelerationResponse = source.float("hairAccelerationResponse", defaults.hairAccelerationResponse),
			maximumCharacterAcceleration = source.float(
				"maximumCharacterAcceleration",
				defaults.maximumCharacterAcceleration,
			),
			maximumAppliedAcceleration = source.float("maximumAppliedAcceleration", defaults.maximumAppliedAcceleration),
		)
	}

	private fun JsonObject.legCollision(defaults: LegCollisionConfiguration): LegCollisionConfiguration {
		val value = get("legCollision") ?: return defaults
		require(value.isJsonObject) { "physics.legCollision must be an object" }
		val source = value.asJsonObject
		return LegCollisionConfiguration(
			enabled = source.boolean("enabled", defaults.enabled),
			predictionSeconds = source.float("predictionSeconds", defaults.predictionSeconds),
			maximumContactPadding = source.float("maximumContactPadding", defaults.maximumContactPadding),
		)
	}

	private fun JsonObject.physicsLod(defaults: SecondaryPhysicsLodConfiguration): SecondaryPhysicsLodConfiguration {
		val value = get("lod") ?: return defaults
		require(value.isJsonObject) { "physics.lod must be an object" }
		val source = value.asJsonObject
		return SecondaryPhysicsLodConfiguration(
			enabled = source.boolean("enabled", defaults.enabled),
			hysteresis = source.float("hysteresis", defaults.hysteresis),
			skirtReducedDistance = source.float("skirtReducedDistance", defaults.skirtReducedDistance),
			skirtSuspendedDistance = source.float("skirtSuspendedDistance", defaults.skirtSuspendedDistance),
			hairReducedDistance = source.float("hairReducedDistance", defaults.hairReducedDistance),
			hairSuspendedDistance = source.float("hairSuspendedDistance", defaults.hairSuspendedDistance),
			reducedPositionIterations = source.int("reducedPositionIterations", defaults.reducedPositionIterations),
			reducedVelocityIterations = source.int("reducedVelocityIterations", defaults.reducedVelocityIterations),
		)
	}

	private fun secondaryMotion(source: SecondaryMotionConfiguration): JsonObject = JsonObject().also { target ->
		target.addProperty("enabled", source.enabled)
		target.addProperty("smoothingHalfLife", source.smoothingHalfLife)
		target.addProperty("skirtVelocityResponse", source.skirtVelocityResponse)
		target.addProperty("hairVelocityResponse", source.hairVelocityResponse)
		target.addProperty("skirtAccelerationResponse", source.skirtAccelerationResponse)
		target.addProperty("hairAccelerationResponse", source.hairAccelerationResponse)
		target.addProperty("maximumCharacterAcceleration", source.maximumCharacterAcceleration)
		target.addProperty("maximumAppliedAcceleration", source.maximumAppliedAcceleration)
	}

	private fun legCollision(source: LegCollisionConfiguration): JsonObject = JsonObject().also { target ->
		target.addProperty("enabled", source.enabled)
		target.addProperty("predictionSeconds", source.predictionSeconds)
		target.addProperty("maximumContactPadding", source.maximumContactPadding)
	}

	private fun physicsLod(source: SecondaryPhysicsLodConfiguration): JsonObject = JsonObject().also { target ->
		target.addProperty("enabled", source.enabled)
		target.addProperty("hysteresis", source.hysteresis)
		target.addProperty("skirtReducedDistance", source.skirtReducedDistance)
		target.addProperty("skirtSuspendedDistance", source.skirtSuspendedDistance)
		target.addProperty("hairReducedDistance", source.hairReducedDistance)
		target.addProperty("hairSuspendedDistance", source.hairSuspendedDistance)
		target.addProperty("reducedPositionIterations", source.reducedPositionIterations)
		target.addProperty("reducedVelocityIterations", source.reducedVelocityIterations)
	}

	private fun JsonObject.jointStringMap(name: String): Map<HumanoidJoint, String> {
		val value = get(name) ?: return emptyMap()
		require(value.isJsonObject) { "$name must be an object" }
		return value.asJsonObject.entrySet().associate { (key, element) ->
			require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$name.$key must be a string" }
			joint(key) to element.asString
		}
	}

	private fun JsonObject.jointFloatMap(
		name: String,
		default: Map<HumanoidJoint, Float>,
	): Map<HumanoidJoint, Float> {
		val value = get(name) ?: return default
		require(value.isJsonObject) { "$name must be an object" }
		return value.asJsonObject.entrySet().associate { (key, element) ->
			require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$name.$key must be a number" }
			joint(key) to element.asFloat
		}
	}

	private fun JsonObject.float(name: String, default: Float): Float {
		val value = get(name) ?: return default
		require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be a number" }
		return value.asFloat
	}

	private fun JsonObject.optionalFloat(name: String): Float? {
		val value = get(name) ?: return null
		if (value.isJsonNull) return null
		require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be a number" }
		return value.asFloat
	}

	private fun JsonObject.int(name: String, default: Int): Int {
		val value = get(name) ?: return default
		require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
		val number = value.asDouble
		require(number.isFinite() && number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
			"$name must be an integer"
		}
		return number.toInt()
	}

	private fun JsonObject.boolean(name: String, default: Boolean): Boolean {
		val value = get(name) ?: return default
		require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
		return value.asBoolean
	}

	private fun joint(key: String): HumanoidJoint = try {
		HumanoidJoint.valueOf(key.uppercase())
	} catch (_: IllegalArgumentException) {
		throw IllegalArgumentException("Unknown humanoid joint '$key'")
	}

	private fun HumanoidJoint.key(): String = name.lowercase()
}
