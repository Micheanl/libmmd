package com.micheanl.libmmd.physics

import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.format.pmx.PmxModel
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector3fc
import physx.PxTopLevelFunctions
import physx.common.PxDefaultAllocator
import physx.common.PxDefaultCpuDispatcher
import physx.common.PxDefaultErrorCallback
import physx.common.PxFoundation
import physx.common.PxTolerancesScale
import physx.common.PxVec3
import physx.physics.PxFilterFlagEnum
import physx.physics.PxHitFlagEnum
import physx.physics.PxHitFlags
import physx.physics.PxFilterObjectFlagEnum
import physx.physics.PxPairFlagEnum
import physx.physics.PxRaycastBuffer10
import physx.physics.PxPhysics
import physx.physics.PxScene
import physx.physics.PxSceneDesc
import physx.physics.PxSceneFlagEnum
import physx.physics.PxSolverTypeEnum
import physx.support.PassThroughFilterShaderImpl

class PhysicsWorld(
	val configuration: PhysicsConfiguration = PhysicsConfiguration(),
) : AutoCloseable {
	constructor(workerThreads: Int) : this(PhysicsConfiguration(workerThreads = workerThreads))
	constructor(gravity: Vector3fc, workerThreads: Int) : this(
		PhysicsConfiguration(gravity = Vector3f(gravity), workerThreads = workerThreads),
	)

	val device: PhysicsDevice = PhysicsDeviceResolver.resolve(configuration)
	private val runtime = Runtime(Vector3f(configuration.gravity), configuration.workerThreads)
	private val rigs = LinkedHashSet<PhysicsRig>()
	private val colliders = LinkedHashSet<StaticCollider>()
	private val grabs = LinkedHashSet<PhysicsGrab>()
	private var accumulator = 0f
	private var nextRigId = 1
	private var closed = false

	val version: PhysicsVersion
		get() = runtime.version

	val rigCount: Int
		get() = rigs.size

	val staticColliderCount: Int
		get() = colliders.size

	val grabCount: Int
		get() = grabs.size

	fun attach(
		model: PmxModel,
		pose: Pose,
		transform: Matrix4fc,
		configuration: PhysicsAssetConfiguration = PhysicsAssetConfiguration(),
	): PhysicsRig? {
		checkOpen()
		if (!configuration.enabled) return null
		val asset = CharacterPhysicsAsset.prepare(model, pose.skeleton, configuration)
		if (asset.model.rigidBodies.isEmpty()) return null
		val rig = PhysicsRig(runtime, asset, allocateRigId(), pose, transform) { removed ->
			grabs.filter { it.rig === removed }.toList().forEach(PhysicsGrab::close)
			rigs.remove(removed)
		}
		rigs += rig
		return rig
	}

	private fun allocateRigId(): Int {
		val id = nextRigId
		nextRigId = if (nextRigId >= 0xff) 1 else nextRigId + 1
		return id
	}

	fun addStaticBox(
		center: Vector3fc,
		halfExtents: Vector3fc,
		friction: Float = 0.6f,
		restitution: Float = 0f,
		collisionGroup: Int = 15,
		collisionMask: Int = 0xffff,
	): StaticCollider {
		checkOpen()
		val collider = StaticCollider(
			runtime,
			center,
			halfExtents,
			friction,
			restitution,
			collisionGroup,
			collisionMask,
		) { colliders.remove(it) }
		colliders += collider
		return collider
	}

	fun grab(hit: PhysicsHit): PhysicsGrab {
		checkOpen()
		require(hit.rig in rigs) { "Physics hit belongs to another world" }
		val grab = PhysicsGrab(
			runtime = runtime,
			rig = hit.rig,
			rigidBodyIndex = hit.rigidBodyIndex,
			actor = hit.rig.rigidBodyActor(hit.rigidBodyIndex),
			worldPosition = hit.position,
		) { grabs.remove(it) }
		grabs += grab
		return grab
	}

	fun raycast(origin: Vector3fc, direction: Vector3fc, maxDistance: Float): PhysicsHit? {
		checkOpen()
		require(origin.isFinite) { "Ray origin must be finite" }
		require(direction.isFinite && direction.lengthSquared() > 0f) { "Ray direction must be finite and non-zero" }
		require(maxDistance.isFinite() && maxDistance > 0f) { "Ray distance must be finite and positive" }
		val normalized = Vector3f(direction).normalize()
		val nativeOrigin = PxVec3(origin.x(), origin.y(), origin.z())
		val nativeDirection = PxVec3(normalized.x, normalized.y, normalized.z)
		val hits = PxRaycastBuffer10()
		val hitFlags = PxHitFlags(PxHitFlagEnum.eDEFAULT.value.toShort())
		try {
			runtime.scene.raycast(nativeOrigin, nativeDirection, maxDistance, hits, hitFlags)
			if (!hits.hasAnyHits()) {
				return null
			}
			val hit = (0 until hits.nbAnyHits)
				.map(hits::getAnyHit)
				.minByOrNull { it.distance }
				?: return null
			val actor = hit.actor ?: return null
			val owner = rigs.firstNotNullOfOrNull { rig ->
				rig.rigidBodyIndex(actor.address)?.let { index -> rig to index }
			} ?: return null
			val position = hit.position
			val normal = hit.normal
			return PhysicsHit(
				rig = owner.first,
				rigidBodyIndex = owner.second,
				distance = hit.distance,
				position = Vector3f(position.x, position.y, position.z),
				normal = Vector3f(normal.x, normal.y, normal.z),
			)
		} finally {
			hitFlags.destroy()
			hits.destroy()
			nativeDirection.destroy()
			nativeOrigin.destroy()
		}
	}

	fun step(deltaSeconds: Float) {
		checkOpen()
		require(deltaSeconds.isFinite() && deltaSeconds >= 0f) { "deltaSeconds must be finite and non-negative" }
		if (rigs.isEmpty()) return
		rigs.forEach { it.prepare(deltaSeconds) }
		accumulator = (accumulator + deltaSeconds).coerceAtMost(configuration.maximumFrameSeconds)
		while (accumulator >= configuration.fixedStepSeconds) {
			runtime.scene.simulate(configuration.fixedStepSeconds)
			check(runtime.scene.fetchResults(true)) { "PhysX failed to fetch simulation results" }
			accumulator -= configuration.fixedStepSeconds
		}
		rigs.forEach(PhysicsRig::finish)
	}

	override fun close() {
		if (closed) return
		closed = true
		grabs.toList().forEach(PhysicsGrab::close)
		grabs.clear()
		rigs.toList().forEach(PhysicsRig::close)
		rigs.clear()
		colliders.toList().forEach(StaticCollider::close)
		colliders.clear()
		runtime.close()
	}

	private fun checkOpen() = check(!closed) { "Physics world is closed" }

	internal class Runtime(gravity: Vector3fc, workerThreads: Int) : AutoCloseable {
		val allocator = PxDefaultAllocator()
		val errorCallback = PxDefaultErrorCallback()
		val foundation: PxFoundation
		val physics: PxPhysics
		val dispatcher: PxDefaultCpuDispatcher
		private val filter = CollisionFilter()
		val scene: PxScene
		val version: PhysicsVersion

		init {
			require(workerThreads > 0) { "workerThreads must be positive" }
			val encodedVersion = PxTopLevelFunctions.getPHYSICS_VERSION()
			version = PhysicsVersion(
				major = encodedVersion ushr 24,
				minor = encodedVersion ushr 16 and 0xff,
				patch = encodedVersion ushr 8 and 0xff,
			)
			foundation = checkNotNull(PxTopLevelFunctions.CreateFoundation(encodedVersion, allocator, errorCallback)) {
				"Unable to create PhysX foundation"
			}
			val tolerances = PxTolerancesScale()
			try {
				physics = checkNotNull(PxTopLevelFunctions.CreatePhysics(encodedVersion, foundation, tolerances)) {
					"Unable to create PhysX runtime"
				}
				check(PxTopLevelFunctions.InitExtensions(physics)) { "Unable to initialize PhysX extensions" }
				dispatcher = checkNotNull(PxTopLevelFunctions.DefaultCpuDispatcherCreate(workerThreads)) {
					"Unable to create PhysX CPU dispatcher"
				}
				val sceneDescription = PxSceneDesc(tolerances)
				val gravityVector = PxVec3(gravity.x(), gravity.y(), gravity.z())
				try {
					sceneDescription.setGravity(gravityVector)
					sceneDescription.setCpuDispatcher(dispatcher)
					sceneDescription.setSolverType(PxSolverTypeEnum.eTGS)
					sceneDescription.flags.raise(PxSceneFlagEnum.eENABLE_CCD)
					sceneDescription.flags.raise(PxSceneFlagEnum.eENABLE_STABILIZATION)
					sceneDescription.flags.raise(PxSceneFlagEnum.eENABLE_ENHANCED_DETERMINISM)
					sceneDescription.flags.raise(PxSceneFlagEnum.eENABLE_FRICTION_EVERY_ITERATION)
					PxTopLevelFunctions.setupPassThroughFilterShader(sceneDescription, filter)
					scene = checkNotNull(physics.createScene(sceneDescription)) { "Unable to create PhysX scene" }
				} finally {
					gravityVector.destroy()
					sceneDescription.destroy()
				}
			} finally {
				tolerances.destroy()
			}
		}

		override fun close() {
			scene.release()
			filter.destroy()
			dispatcher.destroy()
			PxTopLevelFunctions.CloseExtensions()
			physics.release()
			foundation.release()
			errorCallback.destroy()
			allocator.destroy()
		}
	}

	private class CollisionFilter : PassThroughFilterShaderImpl() {
		override fun filterShader(
			attributes0: Int,
			filterData0w0: Int,
			filterData0w1: Int,
			filterData0w2: Int,
			filterData0w3: Int,
			attributes1: Int,
			filterData1w0: Int,
			filterData1w1: Int,
			filterData1w2: Int,
			filterData1w3: Int,
		): Int {
			if (filterData0w0 and filterData1w1 == 0 || filterData1w0 and filterData0w1 == 0) {
				return PxFilterFlagEnum.eSUPPRESS.value
			}
			if (CollisionMetadata.suppress(filterData0w3, filterData1w3)) return PxFilterFlagEnum.eSUPPRESS.value
			val trigger = attributes0 and PxFilterObjectFlagEnum.eTRIGGER.value != 0 ||
				attributes1 and PxFilterObjectFlagEnum.eTRIGGER.value != 0
			setOutputPairFlags(
				(if (trigger) PxPairFlagEnum.eTRIGGER_DEFAULT.value else PxPairFlagEnum.eCONTACT_DEFAULT.value) or
					filterData0w2 or filterData1w2,
			)
			return PxFilterFlagEnum.eDEFAULT.value
		}
	}

}

data class PhysicsVersion(val major: Int, val minor: Int, val patch: Int)

data class PhysicsHit(
	val rig: PhysicsRig,
	val rigidBodyIndex: Int,
	val distance: Float,
	val position: Vector3f,
	val normal: Vector3f,
)
