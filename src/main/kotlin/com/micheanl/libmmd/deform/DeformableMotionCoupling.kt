package com.micheanl.libmmd.deform

import org.joml.Vector3f

class DeformableMotionCoupling {
	private val previousVelocity = Vector3f()
	val acceleration = Vector3f()

	fun update(input: DeformableMotionInput, deltaSeconds: Float, active: Boolean) {
		require(deltaSeconds.isFinite() && deltaSeconds > 0f)
		if (!active) {
			previousVelocity.set(input.localVelocity)
			return
		}
		acceleration.set(input.localVelocity).sub(previousVelocity).div(deltaSeconds).negate()
		val magnitude = acceleration.length()
		if (magnitude > MAXIMUM_ACCELERATION) acceleration.mul(MAXIMUM_ACCELERATION / magnitude)
		val speed = input.localVelocity.length()
		acceleration.fma(-speed * AIR_RESPONSE, input.localVelocity)
		acceleration.add(
			-input.angularVelocityRadians * input.localVelocity.z * TURN_RESPONSE,
			if (!input.grounded) -input.localVelocity.y * AIRBORNE_RESPONSE else 0f,
			input.angularVelocityRadians * input.localVelocity.x * TURN_RESPONSE,
		)
		previousVelocity.set(input.localVelocity)
	}

	fun reset() {
		previousVelocity.zero()
		acceleration.zero()
	}

	private companion object {
		const val MAXIMUM_ACCELERATION = 24f
		const val AIR_RESPONSE = 0.025f
		const val TURN_RESPONSE = 0.35f
		const val AIRBORNE_RESPONSE = 0.2f
	}
}
