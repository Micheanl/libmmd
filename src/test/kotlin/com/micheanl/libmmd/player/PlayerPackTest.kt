package com.micheanl.libmmd.player

import com.micheanl.libmmd.animation.MotionState
import com.micheanl.libmmd.asset.AssetLoadException
import com.micheanl.libmmd.format.vmd.VmdDecoder
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerPackTest {
	@Test
	fun `discovers complete player folders with loose and archived motions`() {
		val root = createTempDirectory("libmmd-player-pack")
		try {
			val profile = root.resolve("角色").createDirectories()
			profile.resolve("model.PMX").writeBytes(byteArrayOf(1))
			val textures = profile.resolve("Textures").createDirectories()
			textures.resolve("身体.png").writeBytes(byteArrayOf(2, 3, 4))
			val animations = profile.resolve("animations").createDirectories()
			animations.resolve("idle.vmd").writeBytes(byteArrayOf(5))
			ZipOutputStream(Files.newOutputStream(root.resolve("default-animation.zip"))).use { zip ->
				zip.putNextEntry(ZipEntry("walk.vmd"))
				zip.write(byteArrayOf(6, 7))
				zip.closeEntry()
			}

			val pack = requireNotNull(PlayerPack.discover(root))
			assertEquals(profile.resolve("model.PMX"), pack.modelFile)
			assertNull(pack.compiledModel())
			val compiled = profile.resolve("model.mmdpack")
			compiled.writeBytes(byteArrayOf(8))
			assertEquals(compiled, pack.compiledModel())
			assertTrue(MotionState.IDLE in pack.availableMotions())
			assertTrue(MotionState.WALK in pack.availableMotions())
			assertContentEquals(byteArrayOf(2, 3, 4), pack.readTexture("textures\\身体.png", 16))
			assertFailsWith<AssetLoadException> { pack.readTexture("../outside.png", 16) }
		} finally {
			root.toFile().deleteRecursively()
		}
	}

	@Test
	fun `loads every bundled default motion`() {
		val root = createTempDirectory("libmmd-default-motions")
		try {
			root.resolve("player.pmx").writeBytes(byteArrayOf(1))
			val pack = requireNotNull(PlayerPack.discover(root))

			assertEquals(MotionState.entries.toSet(), pack.availableMotions())
			MotionState.entries.forEach { state ->
				val bytes = pack.motionSource().read(
					Identifier.fromNamespaceAndPath("libmmd", "animations/${state.name.lowercase()}"),
					64 * 1024 * 1024,
				)
				VmdDecoder.readMotion(bytes)
			}
		} finally {
			root.toFile().deleteRecursively()
		}
	}
}
