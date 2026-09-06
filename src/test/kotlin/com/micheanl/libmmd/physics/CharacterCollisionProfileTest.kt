package com.micheanl.libmmd.physics

import com.micheanl.libmmd.format.pmx.PmxRigidBody
import com.micheanl.libmmd.format.pmx.PmxRigidBodyMode
import com.micheanl.libmmd.format.pmx.PmxRigidBodyShape
import com.micheanl.libmmd.format.pmx.PmxVector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterCollisionProfileTest {
	@Test
	fun `opens bilateral body collisions and secondary self collisions`() {
		val bodies = listOf(
			body("Leg", 0, 0xffff, PmxRigidBodyMode.FOLLOW_BONE),
			body("qunzi_0", 2, 0xffff, PmxRigidBodyMode.PHYSICS),
			body("Hair", 6, 0xffff, PmxRigidBodyMode.PHYSICS),
		)
		val profile = CharacterCollisionProfile(bodies, listOf("左足", "qunzi_0", "Hair"))

		assertTrue(profile.mask(0) and (1 shl 2) != 0)
		assertTrue(profile.mask(0) and (1 shl 6) != 0)
		assertTrue(profile.mask(1) and (1 shl 0) != 0)
		assertTrue(profile.mask(1) and (1 shl 2) != 0)
		assertTrue(profile.mask(2) and (1 shl 0) != 0)
		assertTrue(profile.mask(2) and (1 shl 6) != 0)
		assertEquals(PhysicsBodyRole.HAIR, profile.role(2))
	}

	@Test
	fun `suppresses adjacent bodies only inside the same secondary chain`() {
		val first = CollisionMetadata.encode(7, PhysicsBodyRole.SKIRT, 20, 2)
		val adjacent = CollisionMetadata.encode(7, PhysicsBodyRole.SKIRT, 20, 3)
		val distant = CollisionMetadata.encode(7, PhysicsBodyRole.SKIRT, 20, 5)
		val anotherRig = CollisionMetadata.encode(8, PhysicsBodyRole.SKIRT, 20, 3)

		assertTrue(CollisionMetadata.suppress(first, adjacent))
		assertTrue(!CollisionMetadata.suppress(first, distant))
		assertTrue(!CollisionMetadata.suppress(first, anotherRig))
	}

	private fun body(name: String, group: Int, exclusionMask: Int, mode: PmxRigidBodyMode) = PmxRigidBody(
		name = name,
		englishName = name,
		boneIndex = 0,
		collisionGroup = group,
		collisionExclusionMask = exclusionMask,
		shape = PmxRigidBodyShape.CAPSULE,
		size = PmxVector3(1f, 1f, 1f),
		position = PmxVector3(0f, 0f, 0f),
		rotation = PmxVector3(0f, 0f, 0f),
		mass = 1f,
		linearDamping = 0f,
		angularDamping = 0f,
		restitution = 0f,
		friction = 0.5f,
		mode = mode,
	)
}
