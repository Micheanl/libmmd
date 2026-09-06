package com.micheanl.libmmd.asset

import net.minecraft.resources.Identifier
import java.util.ArrayDeque

object ResourcePath {
	fun resolve(modelId: Identifier, reference: String): Identifier {
		val normalizedReference = reference.replace('\\', '/')
		require(normalizedReference.isNotBlank()) { "Resource reference must not be blank" }
		require(!normalizedReference.startsWith('/')) { "Absolute resource paths are not allowed: $reference" }
		require(':' !in normalizedReference) { "Namespaced resource references are not allowed: $reference" }

		val segments = ArrayDeque<String>()
		modelId.path.substringBeforeLast('/', "")
			.split('/')
			.filterTo(segments) { it.isNotEmpty() }

		for (segment in normalizedReference.split('/')) {
			when (segment) {
				"", "." -> Unit
				".." -> require(segments.pollLast() != null) {
					"Resource path escapes namespace root: $reference"
				}
				else -> segments.addLast(segment)
			}
		}

		require(segments.isNotEmpty()) { "Resource reference must resolve to a file: $reference" }
		return requireNotNull(Identifier.tryBuild(modelId.namespace, segments.joinToString("/"))) {
			"Invalid Minecraft resource path: $reference"
		}
	}
}
