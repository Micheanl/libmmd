package com.micheanl.libmmd.player

import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTransformTest {
	@Test
	fun `keeps MMD forward and left aligned with the player`() {
		val south = PlayerModels.playerTransform(0f, 0f, 0f, 0f, 1f, 0f)
		val southForward = south.transformDirection(Vector3f(0f, 0f, 1f))
		val southLeft = south.transformDirection(Vector3f(1f, 0f, 0f))

		assertEquals(1f, southForward.z, 0.0001f)
		assertEquals(1f, southLeft.x, 0.0001f)

		val west = PlayerModels.playerTransform(0f, 0f, 0f, 90f, 1f, 0f)
		val westForward = west.transformDirection(Vector3f(0f, 0f, 1f))
		val westLeft = west.transformDirection(Vector3f(1f, 0f, 0f))

		assertEquals(-1f, westForward.x, 0.0001f)
		assertEquals(1f, westLeft.z, 0.0001f)
	}

	@Test
	fun `applies profile forward correction and ground offset`() {
		val transform = PlayerModels.playerTransform(0f, 0f, 0f, 0f, 2f, -0.5f, 180f)

		val forward = transform.transformDirection(Vector3f(0f, 0f, 1f))
		val ground = transform.transformPosition(Vector3f(0f, -0.5f, 0f))

		assertEquals(-2f, forward.z, 0.0001f)
		assertEquals(0f, ground.y, 0.0001f)
	}
}
