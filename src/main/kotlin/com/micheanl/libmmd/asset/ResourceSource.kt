package com.micheanl.libmmd.asset

import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import java.nio.file.Files
import java.nio.file.Path

fun interface ResourceSource {
	fun read(id: Identifier, maxBytes: Int): ByteArray
}

class MinecraftResourceSource(
	private val resources: ResourceManager,
) : ResourceSource {
	override fun read(id: Identifier, maxBytes: Int): ByteArray = resources.open(id).use { input ->
		val bytes = input.readNBytes(maxBytes + 1)
		if (bytes.size > maxBytes) {
			throw AssetLoadException("Resource $id exceeds the $maxBytes byte limit")
		}
		bytes
	}
}

class FileResourceSource(
	private val file: Path,
) : ResourceSource {
	override fun read(id: Identifier, maxBytes: Int): ByteArray {
		require(maxBytes >= 0) { "maxBytes must be non-negative" }
		return Files.newInputStream(file).use { input ->
			val bytes = input.readNBytes(maxBytes + 1)
			if (bytes.size > maxBytes) throw AssetLoadException("Resource $id exceeds the $maxBytes byte limit")
			bytes
		}
	}
}
