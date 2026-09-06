package com.micheanl.libmmd.animation

import org.joml.Quaternionf
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.atan2

data class GroundSample(val height: Float, val normal: Vector3f)

fun interface GroundSampler {
	fun sample(localPosition: Vector3fc): GroundSample?
}

data class FootContacts(val left: Float, val right: Float) {
	companion object {
		val NONE = FootContacts(0f, 0f)
	}
}

data class FootLocks(val left: Boolean, val right: Boolean) {
	companion object {
		val NONE = FootLocks(false, false)
	}
}

data class LocomotionParameters(
	val planarSpeed: Float = 0f,
	val forwardSpeed: Float = 0f,
	val sideSpeed: Float = 0f,
	val sprinting: Boolean = false,
) {
	init {
		require(planarSpeed.isFinite() && planarSpeed >= 0f)
		require(forwardSpeed.isFinite() && sideSpeed.isFinite())
	}
}

data class ProceduralPoseConfiguration(
	val footPlacementEnabled: Boolean = true,
	val maximumFootReachRatio: Float = 0.14f,
	val maximumPelvisOffsetRatio: Float = 0.055f,
	val contactHalfLife: Float = 0.035f,
	val pelvisHalfLife: Float = 0.06f,
	val walkReferenceSpeed: Float = 0.1f,
	val sprintReferenceSpeed: Float = 0.14f,
	val minimumStrideScale: Float = 0.65f,
	val maximumStrideScale: Float = 1.5f,
	val maximumOrientationRadians: Float = radians(65f),
	val maximumFootLockRatio: Float = 0.2f,
	val jointLimitsRadians: Map<HumanoidJoint, Float> = defaultJointLimits(),
) {
	init {
		require(maximumFootReachRatio.isFinite() && maximumFootReachRatio in 0.01f..0.5f)
		require(maximumPelvisOffsetRatio.isFinite() && maximumPelvisOffsetRatio in 0f..0.2f)
		require(contactHalfLife.isFinite() && contactHalfLife in 0.001f..1f)
		require(pelvisHalfLife.isFinite() && pelvisHalfLife in 0.001f..1f)
		require(walkReferenceSpeed.isFinite() && walkReferenceSpeed > 0f)
		require(sprintReferenceSpeed.isFinite() && sprintReferenceSpeed >= walkReferenceSpeed)
		require(minimumStrideScale.isFinite() && minimumStrideScale in 0.1f..1f)
		require(maximumStrideScale.isFinite() && maximumStrideScale in 1f..3f)
		require(maximumOrientationRadians.isFinite() && maximumOrientationRadians in 0f..PI.toFloat())
		require(maximumFootLockRatio.isFinite() && maximumFootLockRatio in 0.01f..0.5f)
		require(jointLimitsRadians.values.all { it.isFinite() && it > 0f && it < PI.toFloat() })
	}

	companion object {
		fun defaultJointLimits(): Map<HumanoidJoint, Float> = mapOf(
			HumanoidJoint.NECK to radians(70f),
			HumanoidJoint.HEAD to radians(85f),
			HumanoidJoint.LEFT_SHOULDER to radians(100f),
			HumanoidJoint.RIGHT_SHOULDER to radians(100f),
			HumanoidJoint.LEFT_LOWER_ARM to radians(155f),
			HumanoidJoint.RIGHT_LOWER_ARM to radians(155f),
			HumanoidJoint.LEFT_LOWER_LEG to radians(165f),
			HumanoidJoint.RIGHT_LOWER_LEG to radians(165f),
		)

		private fun radians(degrees: Float): Float = (degrees * PI / 180.0).toFloat()
	}
}

class ProceduralPoseSolver(
	private val rig: HumanoidRig,
	neutralGroundHeight: Float,
	private val configuration: ProceduralPoseConfiguration = ProceduralPoseConfiguration(),
) {
	private val left = FootChain.create(rig, true, neutralGroundHeight)
	private val right = FootChain.create(rig, false, neutralGroundHeight)
	private val center = rig[HumanoidJoint.CENTER] ?: rig[HumanoidJoint.HIPS]
	private val hips = rig[HumanoidJoint.HIPS]
	private val spine = rig[HumanoidJoint.SPINE]
	private val leftLock = FootLock()
	private val rightLock = FootLock()
	private var leftContact = 0f
	private var rightContact = 0f
	private var pelvisOffset = 0f

	var contacts: FootContacts = FootContacts.NONE
		private set
	var locks: FootLocks = FootLocks.NONE
		private set
	var strideScale: Float = 1f
		private set
	var orientationRadians: Float = 0f
		private set

	fun apply(
		pose: Pose,
		state: MotionState?,
		onGround: Boolean,
		deltaSeconds: Float,
		groundSampler: GroundSampler? = null,
		locomotion: LocomotionParameters = LocomotionParameters(),
		modelTransform: Matrix4fc? = null,
	) {
		require(pose.skeleton === rig.skeleton) { "Procedural solver rig and pose use different skeletons" }
		require(deltaSeconds.isFinite() && deltaSeconds >= 0f) { "deltaSeconds must be finite and non-negative" }
		applyLocomotionWarp(pose, state, locomotion)
		applyJointLimits(pose)
		if (!configuration.footPlacementEnabled) {
			leftContact = 0f
			rightContact = 0f
			contacts = FootContacts.NONE
			leftLock.release()
			rightLock.release()
			locks = FootLocks.NONE
			pelvisOffset = 0f
			return
		}
		val leftTarget = contactTarget(pose, left, state, onGround)
		val rightTarget = contactTarget(pose, right, state, onGround)
		val alpha = decayAlpha(deltaSeconds, configuration.contactHalfLife)
		leftContact += (leftTarget - leftContact) * alpha
		rightContact += (rightTarget - rightContact) * alpha
		contacts = FootContacts(leftContact, rightContact)
		if (!onGround || state !in GROUNDED_LOCOMOTION_WITH_IDLE || modelTransform == null) {
			leftLock.release()
			rightLock.release()
		} else {
			applyFootLock(pose, left, leftContact, modelTransform, leftLock)
			applyFootLock(pose, right, rightContact, modelTransform, rightLock)
		}
		locks = FootLocks(leftLock.locked, rightLock.locked)
		if (groundSampler == null || !onGround) {
			pelvisOffset += (0f - pelvisOffset) * decayAlpha(deltaSeconds, configuration.pelvisHalfLife)
			center?.let { pose.translate(it, 0f, pelvisOffset, 0f) }
			return
		}

		val leftCorrection = solveFoot(pose, left, leftContact, groundSampler)
		val rightCorrection = solveFoot(pose, right, rightContact, groundSampler)
		val weight = leftContact + rightContact
		val pelvisTarget = if (weight > 0.001f) {
			((leftCorrection * leftContact + rightCorrection * rightContact) / weight)
				.coerceIn(-configuration.maximumPelvisOffsetRatio * rig.height, configuration.maximumPelvisOffsetRatio * rig.height)
		} else {
			0f
		}
		pelvisOffset += (pelvisTarget - pelvisOffset) * decayAlpha(deltaSeconds, configuration.pelvisHalfLife)
		center?.let { pose.translate(it, 0f, pelvisOffset, 0f) }
	}

	private fun applyLocomotionWarp(pose: Pose, state: MotionState?, locomotion: LocomotionParameters) {
		if (state !in GROUNDED_LOCOMOTION || locomotion.planarSpeed <= 0.0001f) {
			strideScale = 1f
			orientationRadians = 0f
			return
		}
		val referenceSpeed = when {
			locomotion.sprinting || state == MotionState.SPRINT -> configuration.sprintReferenceSpeed
			state == MotionState.SNEAK -> configuration.walkReferenceSpeed * 0.65f
			else -> configuration.walkReferenceSpeed
		}
		strideScale = (locomotion.planarSpeed / referenceSpeed)
			.coerceIn(configuration.minimumStrideScale, configuration.maximumStrideScale)
		val forwardMagnitude = abs(locomotion.forwardSpeed).coerceAtLeast(0.0001f)
		orientationRadians = atan2(locomotion.sideSpeed, forwardMagnitude)
			.coerceIn(-configuration.maximumOrientationRadians, configuration.maximumOrientationRadians)
		val direction = if (locomotion.forwardSpeed < -0.001f) -1f else 1f
		left?.let { warpFoot(pose, it, strideScale, orientationRadians, direction) }
		right?.let { warpFoot(pose, it, strideScale, orientationRadians, direction) }
		hips?.let { pose.rotate(it, Quaternionf().rotateY(orientationRadians * 0.35f)) }
		spine?.let { pose.rotate(it, Quaternionf().rotateY(-orientationRadians * 0.12f)) }
	}

	private fun warpFoot(pose: Pose, chain: FootChain, scale: Float, directionAngle: Float, direction: Float) {
		val translation = pose.translation(chain.controller)
		translation.z *= scale * direction
		val x = translation.x
		val z = translation.z
		val sine = kotlin.math.sin(directionAngle)
		val cosine = kotlin.math.cos(directionAngle)
		translation.x = cosine * x + sine * z
		translation.z = -sine * x + cosine * z
		pose.setTranslation(chain.controller, translation)
	}

	private fun applyFootLock(
		pose: Pose,
		chain: FootChain?,
		contact: Float,
		modelTransform: Matrix4fc,
		lock: FootLock,
	) {
		if (chain == null || contact <= LOCK_RELEASE_THRESHOLD) {
			lock.release()
			return
		}
		val footLocal = pose.globalMatrix(chain.foot).getTranslation(Vector3f())
		val footWorld = modelTransform.transformPosition(Vector3f(footLocal))
		if (!lock.locked) {
			if (contact >= LOCK_CAPTURE_THRESHOLD) lock.capture(footWorld)
			return
		}
		val desiredLocal = Matrix4f(modelTransform).invert().transformPosition(Vector3f(lock.position))
		val correction = desiredLocal.sub(footLocal).setComponent(1, 0f)
		val maximum = chain.length * configuration.maximumFootLockRatio
		val distance = correction.length()
		if (distance > maximum * LOCK_BREAK_MULTIPLIER) {
			lock.release()
			return
		}
		if (distance > maximum) correction.mul(maximum / distance)
		pose.translate(chain.controller, correction.x * contact, 0f, correction.z * contact)
	}

	private fun solveFoot(pose: Pose, chain: FootChain?, contact: Float, sampler: GroundSampler): Float {
		if (chain == null || contact <= 0.001f) return 0f
		val footPosition = pose.globalMatrix(chain.foot).getTranslation(Vector3f())
		val ground = sampler.sample(footPosition) ?: return 0f
		if (!ground.height.isFinite() || !ground.normal.isFinite || ground.normal.lengthSquared() <= 1e-8f) return 0f
		val desiredFootHeight = ground.height + chain.soleOffset
		val correction = (desiredFootHeight - footPosition.y)
			.coerceIn(-chain.length * configuration.maximumFootReachRatio, chain.length * configuration.maximumFootReachRatio)
		pose.translate(chain.controller, 0f, correction * contact, 0f)
		val normal = Vector3f(ground.normal).normalize()
		if (normal.dot(UP) >= MIN_GROUND_NORMAL_Y) {
			val slope = Quaternionf().rotationTo(UP, normal)
			pose.rotate(chain.foot, Quaternionf().slerp(slope, contact))
		}
		return correction
	}

	private fun contactTarget(pose: Pose, chain: FootChain?, state: MotionState?, onGround: Boolean): Float {
		if (!onGround || chain == null) return 0f
		if (state == MotionState.IDLE) return 1f
		if (state !in GROUNDED_LOCOMOTION_WITH_IDLE) return 0f
		val lift = pose.translation(chain.controller).y.coerceAtLeast(0f)
		return 1f - smoothStep(chain.length * 0.015f, chain.length * 0.07f, lift)
	}

	private fun applyJointLimits(pose: Pose) {
		configuration.jointLimitsRadians.forEach { (joint, maximum) ->
			val index = rig[joint] ?: return@forEach
			val rotation = pose.rotation(index)
			val angle = 2f * acos(rotation.w.coerceIn(-1f, 1f)).let { minOf(it, (2f * PI - it).toFloat()) }
			if (angle > maximum) {
				pose.setRotation(index, Quaternionf().slerp(rotation, maximum / angle))
			}
		}
	}

	private data class FootChain(
		val controller: Int,
		val foot: Int,
		val length: Float,
		val soleOffset: Float,
	) {
		companion object {
			fun create(rig: HumanoidRig, left: Boolean, neutralGroundHeight: Float): FootChain? {
				val upper = if (left) HumanoidJoint.LEFT_UPPER_LEG else HumanoidJoint.RIGHT_UPPER_LEG
				val lower = if (left) HumanoidJoint.LEFT_LOWER_LEG else HumanoidJoint.RIGHT_LOWER_LEG
				val footJoint = if (left) HumanoidJoint.LEFT_FOOT else HumanoidJoint.RIGHT_FOOT
				val controllerJoint = if (left) HumanoidJoint.LEFT_FOOT_IK else HumanoidJoint.RIGHT_FOOT_IK
				val foot = rig[footJoint] ?: return null
				val controller = rig[controllerJoint] ?: return null
				val length = (rig.length(upper) + rig.length(lower)).coerceAtLeast(rig.height * 0.1f)
				return FootChain(controller, foot, length, rig.restPosition(footJoint).y - neutralGroundHeight)
			}
		}
	}

	private class FootLock {
		val position = Vector3f()
		var locked = false
			private set

		fun capture(worldPosition: Vector3fc) {
			position.set(worldPosition)
			locked = true
		}

		fun release() {
			locked = false
		}
	}

	companion object {
		private const val MIN_GROUND_NORMAL_Y = 0.55f
		private const val LOCK_CAPTURE_THRESHOLD = 0.7f
		private const val LOCK_RELEASE_THRESHOLD = 0.25f
		private const val LOCK_BREAK_MULTIPLIER = 2.5f
		private val UP = Vector3f(0f, 1f, 0f)
		private val GROUNDED_LOCOMOTION = setOf(MotionState.WALK, MotionState.SPRINT, MotionState.SNEAK)
		private val GROUNDED_LOCOMOTION_WITH_IDLE = GROUNDED_LOCOMOTION + setOf(
			MotionState.IDLE,
			MotionState.START,
			MotionState.STOP,
			MotionState.TURN_LEFT,
			MotionState.TURN_RIGHT,
		)

		private fun smoothStep(start: Float, end: Float, value: Float): Float {
			val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
			return t * t * (3f - 2f * t)
		}

		private fun decayAlpha(deltaSeconds: Float, halfLife: Float): Float =
			if (deltaSeconds == 0f) 0f else 1f - exp(-0.6931472f * deltaSeconds / halfLife)

	}
}
