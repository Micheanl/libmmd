package com.micheanl.libmmd.player

import com.micheanl.libmmd.animation.MotionState
import com.micheanl.libmmd.asset.AssetLoadException
import com.micheanl.libmmd.asset.FileResourceSource
import com.micheanl.libmmd.asset.ResourceSource
import net.minecraft.resources.Identifier
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.extension

class PlayerPack private constructor(
	val root: Path,
	val modelFile: Path,
	private val profileRoot: Path,
) {
	private val canonicalProfileRoot = profileRoot.toRealPath()
	val modelSource: ResourceSource = FileResourceSource(modelFile)
	val profileFile: Path = modelFile.parent.resolve("avatar-profile.json")

	fun compiledModel(): Path? {
		val fileName = modelFile.fileName.toString()
		val stem = fileName.substringBeforeLast('.', fileName)
		return listOf(
			modelFile.resolveSibling("$stem.mmdpack"),
			profileRoot.resolve(".cache/$stem.mmdpack"),
		).firstOrNull { compiled ->
			Files.isRegularFile(compiled) && Files.getLastModifiedTime(compiled) >= Files.getLastModifiedTime(modelFile)
		}
	}

	fun availableMotions(): Set<MotionState> {
		val names = HashSet<String>()
		animationDirectories().filter(Files::isDirectory).forEach { directory ->
			Files.list(directory).use { paths ->
				paths.filter(Files::isRegularFile).map { it.fileName.toString() }.forEach(names::add)
			}
		}
		animationArchives().filter(Files::isRegularFile).forEach { archive ->
			ZipFile(archive.toFile()).use { zip ->
				zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name.substringAfterLast('/') }.forEach(names::add)
			}
		}
		bundledArchive()?.use { input -> collectEntryNames(input, names) }
		return MotionState.entries.filterTo(LinkedHashSet()) { it.fileName in names }
	}

	fun motionSource(): ResourceSource = ResourceSource { id, maxBytes ->
		val key = id.path.substringAfterLast('/')
		val state = MotionState.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
			?: throw AssetLoadException("Unknown motion key '$key'")
		readMotion(state.fileName, maxBytes)
	}

	fun readTexture(reference: String, maxBytes: Int): ByteArray {
		val normalized = reference.replace('\\', '/').trim()
		if (normalized.isEmpty()) throw AssetLoadException("Texture reference is empty")
		val texture = resolveTexture(normalized) ?: throw AssetLoadException(
			"Texture '$reference' is missing or outside the player folder",
		)
		if (!Files.isRegularFile(texture)) {
			throw AssetLoadException("Texture '$reference' is missing or outside the player folder")
		}
		val canonicalTexture = texture.toRealPath()
		if (!canonicalTexture.startsWith(canonicalProfileRoot)) {
			throw AssetLoadException("Texture '$reference' is missing or outside the player folder")
		}
		return readLimited(Files.newInputStream(canonicalTexture), maxBytes, reference)
	}

	private fun resolveTexture(reference: String): Path? {
		val relative = Path.of(reference)
		if (relative.isAbsolute || relative.any { it.toString() == ".." }) return null
		var current = modelFile.parent
		for (component in relative) {
			if (component.toString() == ".") continue
			val exact = current.resolve(component)
			if (Files.exists(exact)) {
				current = exact
				continue
			}
			if (!Files.isDirectory(current)) return null
			val matches = Files.list(current).use { paths ->
				paths.filter { it.fileName.toString().equals(component.toString(), ignoreCase = true) }
					.limit(2)
					.toList()
			}
			if (matches.size != 1) return null
			current = matches.single()
		}
		return current
	}

	private fun readMotion(fileName: String, maxBytes: Int): ByteArray {
		animationDirectories().forEach { directory ->
			val file = directory.resolve(fileName)
			if (Files.isRegularFile(file)) return readLimited(Files.newInputStream(file), maxBytes, fileName)
		}
		animationArchives().forEach { archive ->
			if (!Files.isRegularFile(archive)) return@forEach
			ZipFile(archive.toFile()).use { zip ->
				val entry = zip.getEntry(fileName) ?: zip.getEntry("animations/$fileName")
				if (entry != null && !entry.isDirectory) return readLimited(zip.getInputStream(entry), maxBytes, fileName)
			}
		}
		bundledArchive()?.use { input ->
			ZipInputStream(input).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					if (!entry.isDirectory && entry.name.substringAfterLast('/') == fileName) {
						return readLimited(zip, maxBytes, fileName)
					}
				}
			}
		}
		throw AssetLoadException("Motion '$fileName' was not found")
	}

	private fun animationDirectories(): List<Path> = listOf(
		profileRoot.resolve("animations"),
		root.resolve("animations"),
	).distinct()

	private fun animationArchives(): List<Path> = listOf(
		profileRoot.resolve(DEFAULT_ANIMATION_ARCHIVE),
		root.resolve(DEFAULT_ANIMATION_ARCHIVE),
	).distinct()

	private fun bundledArchive(): InputStream? = javaClass.getResourceAsStream("/assets/libmmd/$DEFAULT_ANIMATION_ARCHIVE")

	private fun collectEntryNames(input: InputStream, destination: MutableSet<String>) {
		ZipInputStream(input).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				if (!entry.isDirectory) destination += entry.name.substringAfterLast('/')
			}
		}
	}

	private fun readLimited(input: InputStream, maxBytes: Int, name: String): ByteArray {
		require(maxBytes >= 0) { "maxBytes must be non-negative" }
		return input.use {
			val bytes = it.readNBytes(maxBytes + 1)
			if (bytes.size > maxBytes) throw AssetLoadException("Resource '$name' exceeds the $maxBytes byte limit")
			bytes
		}
	}

	companion object {
		private const val DEFAULT_ANIMATION_ARCHIVE = "default-animation.zip"

		fun discover(root: Path): PlayerPack? {
			val normalizedRoot = root.toAbsolutePath().normalize()
			if (!Files.isDirectory(normalizedRoot)) return null
			val preferred = listOf(
				normalizedRoot.resolve("player.pmx"),
				normalizedRoot.resolve("models/player.pmx"),
			).firstOrNull(Files::isRegularFile)
			val model = preferred ?: Files.find(
				normalizedRoot,
				4,
				{ path, attributes -> attributes.isRegularFile && path.extension.equals("pmx", ignoreCase = true) },
			).use { paths ->
				paths.sorted(
					compareBy<Path> { it.fileName.toString().equals("model.pmx", ignoreCase = true).not() }
						.thenBy { normalizedRoot.relativize(it).toString() },
				).findFirst().orElse(null)
			} ?: return null
			val relative = normalizedRoot.relativize(model)
			val profileRoot = if (relative.nameCount <= 1) normalizedRoot else normalizedRoot.resolve(relative.getName(0))
			return PlayerPack(normalizedRoot, model, profileRoot.normalize())
		}
	}
}
