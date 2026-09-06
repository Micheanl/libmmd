package com.micheanl.libmmd.player

import com.micheanl.libmmd.animation.HumanoidJoint
import com.micheanl.libmmd.physics.PhysicsAssetConfiguration
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AvatarProfileTest {
	@Test
	fun `round trips editable calibration values`() {
		val profile = AvatarProfile(
			forwardYawDegrees = 180f,
			scaleMultiplier = 1.15f,
			groundOffset = 0.2f,
			sourceMotionHeight = 16f,
			boneMappings = mapOf(HumanoidJoint.LEFT_LOWER_ARM to "Arm.Lower.L"),
			footPlacement = FootPlacementProfile(maximumReachRatio = 0.2f),
			locomotion = LocomotionProfile(maximumStrideScale = 1.8f, maximumOrientationDegrees = 55f),
			physics = PhysicsAssetConfiguration(
				autoGenerateBodyColliders = true,
				skirtSelfCollision = false,
				solverPositionIterations = 18,
			),
			jointLimitsDegrees = mapOf(HumanoidJoint.LEFT_LOWER_ARM to 145f),
		)

		val decoded = AvatarProfileStore.decode(AvatarProfileStore.encode(profile))

		assertEquals(profile, decoded)
	}

	@Test
	fun `creates profile once without overwriting edits`() {
		val directory = Files.createTempDirectory("libmmd-avatar-profile")
		try {
			val path = directory.resolve("avatar-profile.json")
			val automatic = AvatarProfile(scaleMultiplier = 1.1f)
			assertEquals(automatic, AvatarProfileStore.loadOrCreate(path, automatic))
			Files.writeString(path, AvatarProfileStore.encode(AvatarProfile(scaleMultiplier = 1.5f)))

			val loaded = AvatarProfileStore.loadOrCreate(path, automatic)

			assertEquals(1.5f, loaded.scaleMultiplier)
			assertTrue(Files.isRegularFile(path))
		} finally {
			Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
		}
	}

	@Test
	fun `rejects unknown joints and unsafe values`() {
		assertFailsWith<IllegalArgumentException> {
			AvatarProfileStore.decode("""{"version":1,"boneMappings":{"left_wing":"wing"}}""")
		}
		assertFailsWith<IllegalArgumentException> {
			AvatarProfileStore.decode("""{"version":1,"scaleMultiplier":0}""")
		}
		assertFailsWith<IllegalArgumentException> {
			AvatarProfileStore.decode("""{"version":1.5}""")
		}
		assertFailsWith<IllegalArgumentException> {
			AvatarProfileStore.decode(
				"""{"version":1,"boneMappings":{"left_upper_arm":"arm","right_upper_arm":"arm"}}""",
			)
		}
	}
}
