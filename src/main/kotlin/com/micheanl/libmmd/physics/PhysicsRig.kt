package com.micheanl.libmmd.physics

import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.animation.FootContacts
import com.micheanl.libmmd.format.pmx.PmxJoint
import com.micheanl.libmmd.format.pmx.PmxJointType
import com.micheanl.libmmd.format.pmx.PmxRigidBody
import com.micheanl.libmmd.format.pmx.PmxRigidBodyMode
import com.micheanl.libmmd.format.pmx.PmxRigidBodyShape
import com.micheanl.libmmd.format.pmx.PmxVector3
import com.micheanl.libmmd.format.pmx.PmxVector3Range
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3fc
import physx.PxTopLevelFunctions
import physx.common.PxIDENTITYEnum
import physx.common.PxQuat
import physx.common.PxTransform
import physx.common.PxVec3
import physx.extensions.PxD6AxisEnum
import physx.extensions.PxD6DriveEnum
import physx.extensions.PxD6Joint
import physx.extensions.PxD6JointDrive
import physx.extensions.PxD6MotionEnum
import physx.extensions.PxJointAngularLimitPair
import physx.extensions.PxJointLimitPyramid
import physx.extensions.PxJointLinearLimitPair
import physx.extensions.PxRigidBodyExt
import physx.extensions.PxSpring
import physx.geometry.PxBoxGeometry
import physx.geometry.PxCapsuleGeometry
import physx.geometry.PxGeometry
import physx.geometry.PxSphereGeometry
import physx.physics.PxFilterData
import physx.physics.PxForceModeEnum
import physx.physics.PxMaterial
import physx.physics.PxRigidBodyFlagEnum
import physx.physics.PxRigidDynamic
import physx.physics.PxShape
import physx.physics.PxShapeFlagEnum
import physx.physics.PxShapeFlags
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class PhysicsRig internal constructor(
	private val runtime: PhysicsWorld.Runtime,
	private val asset: CharacterPhysicsAsset,
	private val rigId: Int,
	val pose: Pose,
	transform: Matrix4fc,
	private val onClose: (PhysicsRig) -> Unit,
) : AutoCloseable {
	private val model = asset.model
	private val configuration = asset.configuration
	private val modelTransform = Matrix4f(transform)
	private val inverseModelTransform = Matrix4f()
	private val bodies = ArrayList<BodyBinding>(model.rigidBodies.size)
	private val joints = ArrayList<PxD6Joint>(model.joints.size)
	private val temporaryPosition = PxVec3()
	private val secondaryNativeVector = PxVec3()
	private val temporaryRotation = PxQuat(PxIDENTITYEnum.PxIdentity)
	private val temporaryTransform = PxTransform(PxIDENTITYEnum.PxIdentity)
	private val temporaryMatrix = Matrix4f()
	private val secondaryMatrix = Matrix4f()
	private val temporaryVector = Vector3f()
	private val temporaryQuaternion = Quaternionf()
	private val previousCharacterVelocity = Vector3f()
	private val smoothedCharacterVelocity = Vector3f()
	private val smoothedCharacterAcceleration = Vector3f()
	private val motionScratch = Vector3f()
	private val unitScale = uniformScale(transform)
	private val collisionProfile = asset.collisions
	private var groundedSolver = false
	private var hasMotionSample = false
	private var closed = false

	val rigidBodyCount: Int
		get() = bodies.size

	val isClosed: Boolean
		get() = closed

	init {
		require(pose.skeleton.boneCount == model.bones.size) { "Pose and model use different skeleton sizes" }
		try {
			model.rigidBodies.forEachIndexed { index, body -> bodies += createBody(index, body) }
			model.joints.forEach { joint -> joints += createJoint(joint) }
		} catch (exception: Throwable) {
			releaseResources()
			throw exception
		}
	}

	fun applyImpulse(rigidBodyIndex: Int, impulse: Vector3fc) {
		checkOpen()
		require(impulse.isFinite) { "Impulse must be finite" }
		val binding = bodies[rigidBodyIndex]
		if (binding.source.mode == PmxRigidBodyMode.FOLLOW_BONE) return
		setTemporaryPosition(impulse.x(), impulse.y(), impulse.z())
		binding.actor.addForce(temporaryPosition, PxForceModeEnum.eIMPULSE, true)
	}

	fun applyImpulseAt(rigidBodyIndex: Int, impulse: Vector3fc, worldPosition: Vector3fc) {
		checkOpen()
		require(impulse.isFinite && worldPosition.isFinite) { "Impulse and position must be finite" }
		val binding = bodies[rigidBodyIndex]
		if (binding.source.mode == PmxRigidBodyMode.FOLLOW_BONE) return
		setTemporaryPosition(impulse.x(), impulse.y(), impulse.z())
		secondaryNativeVector.x = worldPosition.x()
		secondaryNativeVector.y = worldPosition.y()
		secondaryNativeVector.z = worldPosition.z()
		PxRigidBodyExt.addForceAtPos(
			binding.actor,
			temporaryPosition,
			secondaryNativeVector,
			PxForceModeEnum.eIMPULSE,
			true,
		)
	}

	fun rigidBodyTransform(rigidBodyIndex: Int, destination: Matrix4f = Matrix4f()): Matrix4f {
		checkOpen()
		return readTransform(bodies[rigidBodyIndex].actor, destination)
	}

	fun snapshot(rigidBodyIndex: Int): RigidBodySnapshot {
		checkOpen()
		val binding = bodies[rigidBodyIndex]
		return RigidBodySnapshot(
			index = rigidBodyIndex,
			name = binding.source.name.ifEmpty { binding.source.englishName },
			geometry = geometry(binding.source),
			dynamic = binding.source.mode != PmxRigidBodyMode.FOLLOW_BONE && !binding.lodKinematic,
			role = collisionProfile.role(rigidBodyIndex),
			detailLevel = binding.detailLevel,
			contactOffset = binding.shape.contactOffset,
			transform = readTransform(binding.actor, Matrix4f()),
		)
	}

	fun snapshots(): List<RigidBodySnapshot> {
		checkOpen()
		return bodies.indices.map(::snapshot)
	}

	fun teleport(transform: Matrix4fc) {
		relocate(transform, true)
	}

	fun follow(transform: Matrix4fc) {
		relocate(transform, false)
	}

	fun resetToPose() {
		checkOpen()
		pose.clearPhysics()
		bodies.forEach { binding ->
			binding.hasPreviousTarget = false
			val bodyTransform = if (binding.source.boneIndex >= 0) {
				pose.globalMatrix(binding.source.boneIndex, temporaryMatrix).mul(binding.boneOffset)
			} else {
				temporaryMatrix.set(binding.bindTransform)
			}
			secondaryMatrix.set(modelTransform).mul(bodyTransform)
			binding.actor.setGlobalPose(writeTransform(secondaryMatrix), true)
			if (binding.source.mode != PmxRigidBodyMode.FOLLOW_BONE) {
				setTemporaryPosition(0f, 0f, 0f)
				binding.actor.setLinearVelocity(temporaryPosition, false)
				binding.actor.setAngularVelocity(temporaryPosition, false)
			}
		}
	}

	fun updateCharacterMotion(velocity: Vector3fc, deltaSeconds: Float) {
		checkOpen()
		require(velocity.isFinite) { "Character velocity must be finite" }
		require(deltaSeconds.isFinite() && deltaSeconds > 0f) { "Delta seconds must be finite and positive" }
		val motion = configuration.secondaryMotion
		if (!hasMotionSample) {
			previousCharacterVelocity.set(velocity)
			smoothedCharacterVelocity.set(velocity)
			smoothedCharacterAcceleration.zero()
			hasMotionSample = true
			return
		}
		motionScratch.set(velocity).sub(previousCharacterVelocity).div(deltaSeconds)
		val acceleration = motionScratch.length()
		if (acceleration > motion.maximumCharacterAcceleration) {
			motionScratch.mul(motion.maximumCharacterAcceleration / acceleration)
		}
		val alpha = 1f - exp((-HALF_LIFE_FACTOR * deltaSeconds / motion.smoothingHalfLife).toDouble()).toFloat()
		smoothedCharacterVelocity.lerp(velocity, alpha)
		smoothedCharacterAcceleration.lerp(motionScratch, alpha)
		previousCharacterVelocity.set(velocity)
	}

	fun setViewerDistance(distance: Float) {
		checkOpen()
		require(distance.isFinite() && distance >= 0f) { "Viewer distance must be finite and non-negative" }
		if (!configuration.lod.enabled) return
		bodies.forEach { binding ->
			if (binding.source.mode == PmxRigidBodyMode.FOLLOW_BONE) return@forEach
			val thresholds = when (collisionProfile.role(binding.index)) {
				PhysicsBodyRole.SKIRT -> configuration.lod.skirtReducedDistance to configuration.lod.skirtSuspendedDistance
				PhysicsBodyRole.HAIR -> configuration.lod.hairReducedDistance to configuration.lod.hairSuspendedDistance
				else -> return@forEach
			}
			val next = detailLevel(binding.detailLevel, distance, thresholds.first, thresholds.second)
			if (next != binding.detailLevel) setDetailLevel(binding, next)
		}
	}

	fun setAnimationContacts(contacts: FootContacts) {
		checkOpen()
		val grounded = maxOf(contacts.left, contacts.right) >= 0.5f
		if (grounded == groundedSolver) return
		groundedSolver = grounded
		bodies.forEach { binding ->
			if (binding.source.mode != PmxRigidBodyMode.FOLLOW_BONE) {
				applySolverSettings(binding)
			}
		}
	}

	private fun relocate(transform: Matrix4fc, resetVelocity: Boolean) {
		checkOpen()
		require(abs(uniformScale(transform) - unitScale) <= SCALE_EPSILON * unitScale) {
			"Changing physics scale requires rebuilding the rig"
		}
		val delta = Matrix4f(transform).mul(Matrix4f(modelTransform).invert())
		bodies.forEach { binding ->
			readTransform(binding.actor, temporaryMatrix)
			secondaryMatrix.set(delta).mul(temporaryMatrix)
			binding.actor.setGlobalPose(writeTransform(secondaryMatrix), true)
			if (resetVelocity && binding.source.mode != PmxRigidBodyMode.FOLLOW_BONE) {
				setTemporaryPosition(0f, 0f, 0f)
				binding.actor.setLinearVelocity(temporaryPosition, false)
				binding.actor.setAngularVelocity(temporaryPosition, false)
			}
			if (resetVelocity) binding.hasPreviousTarget = false
		}
		modelTransform.set(transform)
	}

	internal fun prepare(deltaSeconds: Float) {
		pose.clearPhysics()
		bodies.forEach { binding ->
			if (binding.source.mode == PmxRigidBodyMode.PHYSICS && !binding.lodKinematic) return@forEach
			val boneIndex = binding.source.boneIndex
			val bodyTransform = if (boneIndex >= 0) {
				pose.globalMatrix(boneIndex, temporaryMatrix).mul(binding.boneOffset)
			} else {
				temporaryMatrix.set(binding.bindTransform)
			}
			secondaryMatrix.set(modelTransform).mul(bodyTransform)
			updateLegPrediction(binding, secondaryMatrix, deltaSeconds)
			if (binding.source.mode == PmxRigidBodyMode.FOLLOW_BONE || binding.lodKinematic) {
				binding.actor.setKinematicTarget(writeTransform(secondaryMatrix))
			} else if (boneIndex >= 0) {
				secondaryMatrix.getTranslation(temporaryVector)
				readTransform(binding.actor, temporaryMatrix).setTranslation(temporaryVector)
				binding.actor.setGlobalPose(writeTransform(temporaryMatrix), true)
				setTemporaryPosition(0f, 0f, 0f)
				binding.actor.setLinearVelocity(temporaryPosition, false)
			}
		}
		applySecondaryMotion()
	}

	internal fun finish() {
		inverseModelTransform.set(modelTransform).invert()
		bodies.forEach { binding ->
			val boneIndex = binding.source.boneIndex
			if (boneIndex < 0 || binding.source.mode == PmxRigidBodyMode.FOLLOW_BONE || binding.lodKinematic) return@forEach
			readTransform(binding.actor, temporaryMatrix)
			secondaryMatrix.set(inverseModelTransform).mul(temporaryMatrix)
			secondaryMatrix.getTranslation(temporaryVector)
			secondaryMatrix.getUnnormalizedRotation(temporaryQuaternion).normalize()
			secondaryMatrix.identity()
				.translation(temporaryVector)
				.rotate(temporaryQuaternion)
				.mul(binding.inverseBoneOffset)
			pose.setPhysicsTransform(
				boneIndex,
				secondaryMatrix,
				rotationOnly = binding.source.mode == PmxRigidBodyMode.PHYSICS_WITH_BONE,
			)
		}
	}

	private fun applySecondaryMotion() {
		val motion = configuration.secondaryMotion
		if (!motion.enabled || !hasMotionSample) return
		val speed = smoothedCharacterVelocity.length()
		bodies.forEach { binding ->
			if (binding.source.mode == PmxRigidBodyMode.FOLLOW_BONE || binding.lodKinematic) return@forEach
			val velocityResponse: Float
			val accelerationResponse: Float
			when (collisionProfile.role(binding.index)) {
				PhysicsBodyRole.SKIRT -> {
					velocityResponse = motion.skirtVelocityResponse
					accelerationResponse = motion.skirtAccelerationResponse
				}

				PhysicsBodyRole.HAIR -> {
					velocityResponse = motion.hairVelocityResponse
					accelerationResponse = motion.hairAccelerationResponse
				}

				else -> return@forEach
			}
			motionScratch.set(smoothedCharacterVelocity).mul(-speed * velocityResponse)
				.fma(-accelerationResponse, smoothedCharacterAcceleration)
			val magnitude = motionScratch.length()
			if (magnitude > motion.maximumAppliedAcceleration) {
				motionScratch.mul(motion.maximumAppliedAcceleration / magnitude)
			}
			if (motionScratch.lengthSquared() <= FORCE_EPSILON) return@forEach
			setTemporaryPosition(motionScratch.x, motionScratch.y, motionScratch.z)
			binding.actor.addForce(temporaryPosition, PxForceModeEnum.eACCELERATION, true)
		}
	}

	private fun updateLegPrediction(binding: BodyBinding, target: Matrix4fc, deltaSeconds: Float) {
		val settings = configuration.legCollision
		if (!settings.enabled || !binding.legCollider) return
		target.getTranslation(temporaryVector)
		val speed = if (binding.hasPreviousTarget && deltaSeconds > 0f) {
			temporaryVector.distance(binding.previousTargetPosition) / deltaSeconds
		} else {
			0f
		}
		binding.previousTargetPosition.set(temporaryVector)
		binding.hasPreviousTarget = true
		val padding = (speed * settings.predictionSeconds).coerceAtMost(settings.maximumContactPadding)
		binding.shape.setContactOffset(binding.baseContactOffset + padding)
	}

	private fun detailLevel(
		current: PhysicsDetailLevel,
		distance: Float,
		reducedDistance: Float,
		suspendedDistance: Float,
	): PhysicsDetailLevel {
		val hysteresis = configuration.lod.hysteresis
		return when (current) {
			PhysicsDetailLevel.FULL -> if (distance > reducedDistance + hysteresis) PhysicsDetailLevel.REDUCED else current
			PhysicsDetailLevel.REDUCED -> when {
				distance < reducedDistance - hysteresis -> PhysicsDetailLevel.FULL
				distance > suspendedDistance + hysteresis -> PhysicsDetailLevel.SUSPENDED
				else -> current
			}

			PhysicsDetailLevel.SUSPENDED -> if (distance < suspendedDistance - hysteresis) PhysicsDetailLevel.REDUCED else current
		}
	}

	private fun setDetailLevel(binding: BodyBinding, level: PhysicsDetailLevel) {
		val suspended = level == PhysicsDetailLevel.SUSPENDED
		if (binding.lodKinematic != suspended) {
			setTemporaryPosition(0f, 0f, 0f)
			if (suspended) {
				binding.actor.setLinearVelocity(temporaryPosition, false)
				binding.actor.setAngularVelocity(temporaryPosition, false)
				binding.actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_CCD, false)
				binding.actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, true)
			} else {
				binding.actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, false)
				binding.actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_CCD, configuration.continuousCollision)
				binding.actor.setLinearVelocity(temporaryPosition, false)
				binding.actor.setAngularVelocity(temporaryPosition, false)
			}
			binding.lodKinematic = suspended
			binding.hasPreviousTarget = false
		}
		binding.detailLevel = level
		applySolverSettings(binding)
	}

	private fun applySolverSettings(binding: BodyBinding) {
		val reduced = binding.detailLevel != PhysicsDetailLevel.FULL
		binding.actor.setSolverIterationCounts(
			if (reduced) configuration.lod.reducedPositionIterations
			else if (groundedSolver) configuration.groundedPositionIterations else configuration.solverPositionIterations,
			if (reduced) configuration.lod.reducedVelocityIterations
			else if (groundedSolver) configuration.groundedVelocityIterations else configuration.solverVelocityIterations,
		)
	}

	internal fun rigidBodyIndex(actorAddress: Long): Int? {
		val index = bodies.indexOfFirst { it.actor.address == actorAddress }
		return index.takeIf { it >= 0 }
	}

	internal fun rigidBodyActor(rigidBodyIndex: Int): PxRigidDynamic = bodies[rigidBodyIndex].actor

	override fun close() {
		if (closed) return
		closed = true
		onClose(this)
		releaseResources()
	}

	private fun createBody(index: Int, source: PmxRigidBody): BodyBinding {
		validateBody(index, source)
		val settings = configuration.settings(collisionProfile.role(index))
		val bindTransform = bodyTransform(source)
		val worldTransform = Matrix4f(modelTransform).mul(bindTransform)
		val material = checkNotNull(runtime.physics.createMaterial(
			(source.friction * settings.staticFrictionMultiplier).coerceIn(0f, MAX_FRICTION),
			(source.friction * settings.dynamicFrictionMultiplier).coerceIn(0f, MAX_FRICTION),
			(source.restitution * settings.restitutionMultiplier).coerceIn(0f, 1f),
		)) { "Unable to create material for rigid body $index" }
		var shape: PxShape? = null
		var actor: PxRigidDynamic? = null
		var added = false
		try {
			val geometry = createGeometry(source)
			val flags = PxShapeFlags(
				(PxShapeFlagEnum.eSIMULATION_SHAPE.value or PxShapeFlagEnum.eSCENE_QUERY_SHAPE.value).toByte(),
			)
			try {
				shape = checkNotNull(runtime.physics.createShape(geometry, material, true, flags)) {
					"Unable to create shape for rigid body $index"
				}
			} finally {
				flags.destroy()
				geometry.destroy()
			}
			if (source.shape == PmxRigidBodyShape.CAPSULE) {
				val localRotation = Quaternionf().rotateZ((Math.PI * 0.5).toFloat())
				temporaryMatrix.identity().rotate(localRotation)
				shape.setLocalPose(writeTransform(temporaryMatrix))
			}
			val filterData = PxFilterData(
				1 shl source.collisionGroup,
				collisionProfile.mask(index),
				0,
				collisionProfile.metadata(index, rigId),
			)
			try {
				shape.setSimulationFilterData(filterData)
				shape.setQueryFilterData(filterData)
			} finally {
				filterData.destroy()
			}
			actor = checkNotNull(runtime.physics.createRigidDynamic(writeTransform(worldTransform))) {
				"Unable to create rigid body $index"
			}
			actor.setName(source.name.ifEmpty { source.englishName })
			check(actor.attachShape(shape)) { "Unable to attach shape to rigid body $index" }
			actor.setLinearDamping((source.linearDamping * settings.linearDampingMultiplier).coerceAtLeast(0f))
			actor.setAngularDamping((source.angularDamping * settings.angularDampingMultiplier).coerceAtLeast(0f))
			actor.setSolverIterationCounts(configuration.solverPositionIterations, configuration.solverVelocityIterations)
			PxRigidBodyExt.setMassAndUpdateInertia(actor, source.mass.coerceAtLeast(MINIMUM_MASS))
			if (source.mode == PmxRigidBodyMode.FOLLOW_BONE) {
				actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, true)
				actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_SPECULATIVE_CCD, configuration.continuousCollision)
			} else {
				actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_CCD, configuration.continuousCollision)
				actor.setMaxLinearVelocity(MAX_LINEAR_VELOCITY)
				actor.setMaxAngularVelocity(MAX_ANGULAR_VELOCITY)
				actor.setMaxDepenetrationVelocity(MAX_DEPENETRATION_VELOCITY)
			}
			check(runtime.scene.addActor(actor)) { "Unable to add rigid body $index to PhysX scene" }
			added = true
			val boneOffset = if (source.boneIndex >= 0) {
				val absolute = modelBonePosition(source.boneIndex)
				Matrix4f().translation(absolute).invert().mul(bindTransform)
			} else {
				Matrix4f(bindTransform)
			}
			return BodyBinding(
				index = index,
				source = source,
				actor = actor,
				shape = shape,
				material = material,
				bindTransform = bindTransform,
				boneOffset = boneOffset,
				inverseBoneOffset = Matrix4f(boneOffset).invert(),
				baseContactOffset = shape.contactOffset,
				legCollider = collisionProfile.isLeg(index),
			)
		} catch (exception: Throwable) {
			if (added && actor != null) runtime.scene.removeActor(actor)
			actor?.release()
			shape?.release()
			material.release()
			throw exception
		}
	}

	private fun createJoint(source: PmxJoint): PxD6Joint {
		val firstActor = bodies.getOrNull(source.firstRigidBodyIndex)?.actor
		val secondActor = bodies.getOrNull(source.secondRigidBodyIndex)?.actor
		val jointWorld = Matrix4f(modelTransform).mul(jointTransform(source))
		val firstFrame = localJointFrame(firstActor, jointWorld)
		val secondFrame = localJointFrame(secondActor, jointWorld)
		val firstNative = PxTransform(PxIDENTITYEnum.PxIdentity)
		val secondNative = PxTransform(PxIDENTITYEnum.PxIdentity)
		var joint: PxD6Joint? = null
		try {
			writeTransform(firstFrame, firstNative)
			writeTransform(secondFrame, secondNative)
			joint = checkNotNull(
				PxTopLevelFunctions.D6JointCreate(runtime.physics, firstActor, firstNative, secondActor, secondNative),
			) { "Unable to create joint '${source.name}'" }
			joint.setName(source.name.ifEmpty { source.englishName })
			configureJoint(joint, source)
			return joint
		} catch (exception: Throwable) {
			joint?.release()
			throw exception
		} finally {
			firstNative.destroy()
			secondNative.destroy()
		}
	}

	private fun configureJoint(joint: PxD6Joint, source: PmxJoint) {
		val translation = convertTranslationRange(source.translationLimits, unitScale)
		val rotation = convertRotationRange(source.rotationLimits)
		val allLinear = source.type == PmxJointType.SPRING_SIX_DOF || source.type == PmxJointType.SIX_DOF
		configureLinearAxis(joint, PxD6AxisEnum.eX, if (allLinear || source.type == PmxJointType.SLIDER) translation.x else null)
		configureLinearAxis(joint, PxD6AxisEnum.eY, if (allLinear) translation.y else null)
		configureLinearAxis(joint, PxD6AxisEnum.eZ, if (allLinear) translation.z else null)

		when (source.type) {
			PmxJointType.POINT_TO_POINT -> {
				joint.setMotion(PxD6AxisEnum.eTWIST, PxD6MotionEnum.eFREE)
				joint.setMotion(PxD6AxisEnum.eSWING1, PxD6MotionEnum.eFREE)
				joint.setMotion(PxD6AxisEnum.eSWING2, PxD6MotionEnum.eFREE)
			}

			PmxJointType.SLIDER -> {
				joint.setMotion(PxD6AxisEnum.eTWIST, PxD6MotionEnum.eLOCKED)
				joint.setMotion(PxD6AxisEnum.eSWING1, PxD6MotionEnum.eLOCKED)
				joint.setMotion(PxD6AxisEnum.eSWING2, PxD6MotionEnum.eLOCKED)
			}

			PmxJointType.HINGE -> configureAngular(joint, rotation, twistOnly = true)
			else -> configureAngular(joint, rotation, twistOnly = false)
		}

		if (source.type == PmxJointType.SPRING_SIX_DOF) configureDrives(joint, source)
	}

	private fun configureLinearAxis(joint: PxD6Joint, axis: PxD6AxisEnum, range: Range?) {
		if (range == null || abs(range.maximum - range.minimum) <= LIMIT_EPSILON) {
			joint.setMotion(axis, PxD6MotionEnum.eLOCKED)
			return
		}
		if (range.minimum > range.maximum) {
			joint.setMotion(axis, PxD6MotionEnum.eFREE)
			return
		}
		joint.setMotion(axis, PxD6MotionEnum.eLIMITED)
		val spring = PxSpring(0f, 0f)
		val limit = PxJointLinearLimitPair(range.minimum, range.maximum, spring)
		try {
			joint.setLinearLimit(axis, limit)
		} finally {
			limit.destroy()
			spring.destroy()
		}
	}

	private fun configureAngular(joint: PxD6Joint, range: AxisRanges, twistOnly: Boolean) {
		configureTwist(joint, range.x)
		if (twistOnly) {
			joint.setMotion(PxD6AxisEnum.eSWING1, PxD6MotionEnum.eLOCKED)
			joint.setMotion(PxD6AxisEnum.eSWING2, PxD6MotionEnum.eLOCKED)
			return
		}
		val swing1Limited = configureAngularMotion(joint, PxD6AxisEnum.eSWING1, range.y)
		val swing2Limited = configureAngularMotion(joint, PxD6AxisEnum.eSWING2, range.z)
		if (swing1Limited && swing2Limited) {
			val limit = PxJointLimitPyramid(
				range.y.minimum.coerceIn(-MAX_SWING, MAX_SWING),
				range.y.maximum.coerceIn(-MAX_SWING, MAX_SWING),
				range.z.minimum.coerceIn(-MAX_SWING, MAX_SWING),
				range.z.maximum.coerceIn(-MAX_SWING, MAX_SWING),
			)
			try {
				joint.setPyramidSwingLimit(limit)
			} finally {
				limit.destroy()
			}
		}
	}

	private fun configureTwist(joint: PxD6Joint, range: Range) {
		if (!configureAngularMotion(joint, PxD6AxisEnum.eTWIST, range)) return
		val limit = PxJointAngularLimitPair(
			range.minimum.coerceIn(-MAX_TWIST, MAX_TWIST),
			range.maximum.coerceIn(-MAX_TWIST, MAX_TWIST),
		)
		try {
			joint.setTwistLimit(limit)
		} finally {
			limit.destroy()
		}
	}

	private fun configureAngularMotion(joint: PxD6Joint, axis: PxD6AxisEnum, range: Range): Boolean {
		return when {
			abs(range.maximum - range.minimum) <= LIMIT_EPSILON -> {
				joint.setMotion(axis, PxD6MotionEnum.eLOCKED)
				false
			}

			range.minimum > range.maximum -> {
				joint.setMotion(axis, PxD6MotionEnum.eFREE)
				false
			}

			else -> {
				joint.setMotion(axis, PxD6MotionEnum.eLIMITED)
				true
			}
		}
	}

	private fun configureDrives(joint: PxD6Joint, source: PmxJoint) {
		setDrive(joint, PxD6DriveEnum.eX, source.translationSpring.x)
		setDrive(joint, PxD6DriveEnum.eY, source.translationSpring.y)
		setDrive(joint, PxD6DriveEnum.eZ, source.translationSpring.z)
		setDrive(joint, PxD6DriveEnum.eTWIST, source.rotationSpring.x)
		setDrive(joint, PxD6DriveEnum.eSWING, maxOf(abs(source.rotationSpring.y), abs(source.rotationSpring.z)))
		val target = PxTransform(PxIDENTITYEnum.PxIdentity)
		try {
			joint.setDrivePosition(target)
		} finally {
			target.destroy()
		}
	}

	private fun setDrive(joint: PxD6Joint, axis: PxD6DriveEnum, sourceStiffness: Float) {
		val stiffness = abs(sourceStiffness)
		if (stiffness <= LIMIT_EPSILON) return
		val drive = PxD6JointDrive(stiffness, 2f * sqrt(stiffness), Float.MAX_VALUE, true)
		try {
			joint.setDrive(axis, drive)
		} finally {
			drive.destroy()
		}
	}

	private fun createGeometry(source: PmxRigidBody): PxGeometry = when (source.shape) {
		PmxRigidBodyShape.SPHERE -> PxSphereGeometry(source.size.x * unitScale)
		PmxRigidBodyShape.BOX -> PxBoxGeometry(
			source.size.x * unitScale,
			source.size.y * unitScale,
			source.size.z * unitScale,
		)

		PmxRigidBodyShape.CAPSULE -> PxCapsuleGeometry(source.size.x * unitScale, source.size.y * unitScale * 0.5f)
	}

	private fun geometry(source: PmxRigidBody): BodyGeometry = when (source.shape) {
		PmxRigidBodyShape.SPHERE -> BodyGeometry.Sphere(source.size.x * unitScale)
		PmxRigidBodyShape.BOX -> BodyGeometry.Box(
			halfX = source.size.x * unitScale,
			halfY = source.size.y * unitScale,
			halfZ = source.size.z * unitScale,
		)

		PmxRigidBodyShape.CAPSULE -> BodyGeometry.Capsule(
			radius = source.size.x * unitScale,
			halfHeight = source.size.y * unitScale * 0.5f,
		)
	}

	private fun bodyTransform(source: PmxRigidBody): Matrix4f = Matrix4f()
		.translation(source.position.x, source.position.y, -source.position.z)
		.rotate(convertRotation(source.rotation))

	private fun jointTransform(source: PmxJoint): Matrix4f = Matrix4f()
		.translation(source.position.x, source.position.y, -source.position.z)
		.rotate(convertRotation(source.rotation))

	private fun convertRotation(euler: PmxVector3): Quaternionf {
		val source = Quaternionf().rotationXYZ(euler.x, euler.y, euler.z)
		return Quaternionf(-source.x, -source.y, source.z, source.w).normalize()
	}

	private fun modelBonePosition(boneIndex: Int): Vector3f {
		temporaryVector.zero()
		var current = boneIndex
		while (current >= 0) {
			val bone = pose.skeleton.bones[current]
			temporaryVector.add(bone.bindTranslation.x, bone.bindTranslation.y, bone.bindTranslation.z)
			current = bone.parentIndex
		}
		return Vector3f(temporaryVector)
	}

	private fun localJointFrame(actor: PxRigidDynamic?, jointWorld: Matrix4fc): Matrix4f {
		if (actor == null) return Matrix4f(jointWorld)
		return readTransform(actor, Matrix4f()).invert().mul(jointWorld)
	}

	private fun writeTransform(source: Matrix4fc, destination: PxTransform = temporaryTransform): PxTransform {
		source.getTranslation(temporaryVector)
		source.getUnnormalizedRotation(temporaryQuaternion).normalize()
		setTemporaryPosition(temporaryVector.x, temporaryVector.y, temporaryVector.z)
		temporaryRotation.setX(temporaryQuaternion.x)
		temporaryRotation.setY(temporaryQuaternion.y)
		temporaryRotation.setZ(temporaryQuaternion.z)
		temporaryRotation.setW(temporaryQuaternion.w)
		destination.setP(temporaryPosition)
		destination.setQ(temporaryRotation)
		return destination
	}

	private fun setTemporaryPosition(x: Float, y: Float, z: Float) {
		temporaryPosition.x = x
		temporaryPosition.y = y
		temporaryPosition.z = z
	}

	private fun readTransform(actor: PxRigidDynamic, destination: Matrix4f): Matrix4f {
		val source = actor.globalPose
		val position = source.p
		val rotation = source.q
		return destination.identity()
			.translation(position.x, position.y, position.z)
			.rotate(Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w))
	}

	private fun validateBody(index: Int, source: PmxRigidBody) {
		require(source.boneIndex in -1 until pose.skeleton.boneCount) { "Rigid body $index has an invalid bone index" }
		require(source.collisionGroup in 0 until COLLISION_GROUP_COUNT) { "Rigid body $index has an invalid collision group" }
		require(source.size.x.isFinite() && source.size.y.isFinite() && source.size.z.isFinite()) {
			"Rigid body $index size must be finite"
		}
		val validSize = when (source.shape) {
			PmxRigidBodyShape.SPHERE -> source.size.x > 0f
			PmxRigidBodyShape.BOX -> source.size.x > 0f && source.size.y > 0f && source.size.z > 0f
			PmxRigidBodyShape.CAPSULE -> source.size.x > 0f && source.size.y >= 0f
		}
		require(validSize) { "Rigid body $index has an invalid size for ${source.shape.name.lowercase()}" }
		listOf(
			source.mass,
			source.linearDamping,
			source.angularDamping,
			source.restitution,
			source.friction,
		).forEach { require(it.isFinite()) { "Rigid body $index properties must be finite" } }
	}

	private fun releaseResources() {
		joints.asReversed().forEach { it.release() }
		joints.clear()
		bodies.asReversed().forEach { binding ->
			runtime.scene.removeActor(binding.actor)
			binding.actor.release()
			binding.shape.release()
			binding.material.release()
		}
		bodies.clear()
		temporaryTransform.destroy()
		temporaryRotation.destroy()
		secondaryNativeVector.destroy()
		temporaryPosition.destroy()
	}

	private fun checkOpen() = check(!closed) { "Physics rig is closed" }

	private fun uniformScale(transform: Matrix4fc): Float {
		val scale = transform.getScale(Vector3f())
		require(scale.x > 0f && scale.y > 0f && scale.z > 0f && scale.isFinite) {
			"Physics transform scale must be finite and positive"
		}
		val maximum = maxOf(scale.x, scale.y, scale.z)
		require(maximum - minOf(scale.x, scale.y, scale.z) <= SCALE_EPSILON * maximum) {
			"Physics requires a uniform model scale"
		}
		return (scale.x + scale.y + scale.z) / 3f
	}

	private data class BodyBinding(
		val index: Int,
		val source: PmxRigidBody,
		val actor: PxRigidDynamic,
		val shape: PxShape,
		val material: PxMaterial,
		val bindTransform: Matrix4f,
		val boneOffset: Matrix4f,
		val inverseBoneOffset: Matrix4f,
		val baseContactOffset: Float,
		val legCollider: Boolean,
		val previousTargetPosition: Vector3f = Vector3f(),
		var hasPreviousTarget: Boolean = false,
		var detailLevel: PhysicsDetailLevel = PhysicsDetailLevel.FULL,
		var lodKinematic: Boolean = false,
	)

	private data class Range(val minimum: Float, val maximum: Float)

	private data class AxisRanges(val x: Range, val y: Range, val z: Range)

	private fun convertTranslationRange(source: PmxVector3Range, scale: Float): AxisRanges = AxisRanges(
		x = Range(source.minimum.x * scale, source.maximum.x * scale),
		y = Range(source.minimum.y * scale, source.maximum.y * scale),
		z = Range(-source.maximum.z * scale, -source.minimum.z * scale),
	)

	private fun convertRotationRange(source: PmxVector3Range): AxisRanges = AxisRanges(
		x = Range(-source.maximum.x, -source.minimum.x),
		y = Range(-source.maximum.y, -source.minimum.y),
		z = Range(source.minimum.z, source.maximum.z),
	)

	private companion object {
		const val COLLISION_GROUP_COUNT = 16
		const val MINIMUM_MASS = 1.0e-4f
		const val MAX_FRICTION = 10f
		const val MAX_LINEAR_VELOCITY = 20f
		const val MAX_ANGULAR_VELOCITY = 50f
		const val MAX_DEPENETRATION_VELOCITY = 4f
		const val LIMIT_EPSILON = 1.0e-6f
		const val SCALE_EPSILON = 1.0e-4f
		const val FORCE_EPSILON = 1.0e-8f
		const val HALF_LIFE_FACTOR = 0.6931472f
		const val MAX_TWIST = 3.13f
		const val MAX_SWING = 1.56f
	}
}
