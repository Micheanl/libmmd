package com.micheanl.libmmd.player

import com.micheanl.libmmd.animation.MotionState
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.animation.Skeleton
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PoseDynamics(skeleton: Skeleton) {
	private val center = skeleton.firstBone("センター", "Center", "center")
	private val lowerBody = skeleton.firstBone("下半身", "LowerBody", "lower_body")
	private val upperBody = skeleton.firstBone("上半身", "UpperBody", "upper_body")
	private val chest = skeleton.firstBone("上半身2", "UpperBody2", "upper_body_2")
	private val neck = skeleton.firstBone("首", "Neck", "neck")
	private val head = skeleton.firstBone("頭", "Head", "head")
	private val pitch = DampedValue(0.11f)
	private val turn = DampedValue(0.1f)
	private val forwardLean = DampedValue(0.18f)
	private val sideLean = DampedValue(0.16f)
	private val compression = DampedValue(0.12f)
	private var previousYaw = 0f
	private var previousForwardSpeed = 0f
	private var previousVerticalSpeed = 0f
	private var wasOnGround = true
	private var initialized = false

	fun apply(
		pose: Pose,
		state: MotionState?,
		pitchDegrees: Float,
		headYawDegrees: Float,
		bodyYawDegrees: Float,
		velocityX: Float,
		velocityY: Float,
		velocityZ: Float,
		onGround: Boolean,
		deltaSeconds: Float,
	) {
		val delta = deltaSeconds.coerceIn(0f, MAX_DELTA_SECONDS)
		val yaw = bodyYawDegrees * DEG_TO_RAD
		val forwardSpeed = -sin(yaw) * velocityX + cos(yaw) * velocityZ
		val sideSpeed = cos(yaw) * velocityX + sin(yaw) * velocityZ
		if (!initialized) {
			previousYaw = bodyYawDegrees
			previousForwardSpeed = forwardSpeed
			previousVerticalSpeed = velocityY
			wasOnGround = onGround
			initialized = true
		}
		val inverseDelta = if (delta > 0f) 1f / delta else 0f
		val acceleration = (forwardSpeed - previousForwardSpeed) * inverseDelta
		val turnRate = wrapDegrees(bodyYawDegrees - previousYaw) * DEG_TO_RAD * inverseDelta
		if (onGround && !wasOnGround && previousVerticalSpeed < -0.08f) {
			compression.impulse((-previousVerticalSpeed * 0.18f).coerceAtMost(0.9f))
		}

		val motionLean = when (state) {
			MotionState.SPRINT -> 0.14f
			MotionState.WALK -> 0.05f
			MotionState.SNEAK, MotionState.CRAWL -> 0.09f
			else -> 0f
		}
		val pitchAngle = pitch.step(pitchDegrees.coerceIn(-70f, 70f) * DEG_TO_RAD, delta)
		val turnAngle = turn.step(
			wrapDegrees(headYawDegrees - bodyYawDegrees).coerceIn(-75f, 75f) * DEG_TO_RAD,
			delta,
		)
		val leanAngle = forwardLean.step(
			(motionLean + acceleration * 0.012f).coerceIn(-0.08f, 0.2f),
			delta,
		)
		val sideAngle = sideLean.step(
			(-sideSpeed * 0.22f - turnRate * 0.035f).coerceIn(-0.16f, 0.16f),
			delta,
		)
		val compressionAmount = compression.step(0f, delta).coerceIn(-0.03f, 0.12f)

		center?.let { pose.translate(it, 0f, -compressionAmount, 0f) }
		lowerBody?.let { index ->
			pose.rotate(index, Quaternionf().rotationXYZ(-leanAngle * 0.18f, turnAngle * 0.08f, sideAngle * 0.18f))
		}
		upperBody?.let { index ->
			pose.rotate(index, Quaternionf().rotationXYZ(pitchAngle * 0.12f + leanAngle * 0.55f, -turnAngle * 0.22f, sideAngle * 0.45f))
		}
		chest?.let { index ->
			pose.rotate(index, Quaternionf().rotationXYZ(pitchAngle * 0.12f + leanAngle * 0.45f, -turnAngle * 0.23f, sideAngle * 0.37f))
		}
		neck?.let { index ->
			pose.rotate(index, Quaternionf().rotationXYZ(pitchAngle * 0.16f, -turnAngle * 0.18f, 0f))
		}
		head?.let { index ->
			pose.rotate(index, Quaternionf().rotationXYZ(pitchAngle * 0.6f, -turnAngle * 0.37f, 0f))
		}

		previousYaw = bodyYawDegrees
		previousForwardSpeed = forwardSpeed
		previousVerticalSpeed = velocityY
		wasOnGround = onGround
	}

	private fun Skeleton.firstBone(vararg names: String): Int? = names.firstNotNullOfOrNull(::indexOf)

	private fun wrapDegrees(value: Float): Float {
		var wrapped = value % 360f
		if (wrapped >= 180f) wrapped -= 360f
		if (wrapped < -180f) wrapped += 360f
		return wrapped
	}

	private class DampedValue(private val smoothTime: Float) {
		private var value = 0f
		private var velocity = 0f

		fun step(target: Float, deltaSeconds: Float): Float {
			if (deltaSeconds <= 0f) return value
			val omega = 2f / smoothTime
			val x = omega * deltaSeconds
			val decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
			val difference = value - target
			val change = (velocity + omega * difference) * deltaSeconds
			velocity = (velocity - omega * change) * decay
			value = target + (difference + change) * decay
			if (!value.isFinite() || !velocity.isFinite()) {
				value = target
				velocity = 0f
			}
			return value
		}

		fun impulse(amount: Float) {
			velocity += amount
		}
	}

	private companion object {
		const val DEG_TO_RAD = (PI / 180.0).toFloat()
		const val MAX_DELTA_SECONDS = 0.1f
	}
}
