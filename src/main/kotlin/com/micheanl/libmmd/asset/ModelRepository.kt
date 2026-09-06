package com.micheanl.libmmd.asset

import com.micheanl.libmmd.animation.MorphSet
import com.micheanl.libmmd.animation.Skeleton
import com.micheanl.libmmd.format.pmx.PmxDecodeLimits
import com.micheanl.libmmd.format.pmx.PmxDecoder
import net.minecraft.resources.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class ModelRepository(
	private val resources: ResourceSource,
	private val decodeExecutor: Executor,
	private val decodeLimits: PmxDecodeLimits = PmxDecodeLimits(),
	private val maxModelBytes: Int = DEFAULT_MAX_MODEL_BYTES,
	private val textureIds: (Identifier, String, Int) -> Identifier = { modelId, reference, _ ->
		ResourcePath.resolve(modelId, reference)
	},
) : AutoCloseable {
	private val models = ConcurrentHashMap<Identifier, CompletableFuture<ModelAsset>>()

	init {
		require(maxModelBytes in 1 until Int.MAX_VALUE) { "maxModelBytes must be between 1 and ${Int.MAX_VALUE - 1}" }
	}

	val cachedModelCount: Int
		get() = models.size

	fun load(id: Identifier): CompletableFuture<ModelAsset> {
		val future = models.computeIfAbsent(id) { modelId ->
			CompletableFuture.supplyAsync({ decode(modelId) }, decodeExecutor)
		}
		future.whenComplete { _, error ->
			if (error != null) {
				models.remove(id, future)
			}
		}
		return future
	}

	fun invalidate(id: Identifier): Boolean = models.remove(id) != null

	fun clear() {
		models.clear()
	}

	override fun close() = clear()

	private fun decode(id: Identifier): ModelAsset {
		val bytes = resources.read(id, maxModelBytes)
		if (bytes.size > maxModelBytes) {
			throw AssetLoadException("Resource $id exceeds the $maxModelBytes byte limit")
		}
		val model = PmxDecoder.readModel(bytes, decodeLimits)
		val resolvedTextureIds = model.texturePaths.mapIndexed { index, reference ->
			try {
				textureIds(id, reference, index)
			} catch (exception: IllegalArgumentException) {
				throw AssetLoadException("Invalid texture reference '$reference' in $id", exception)
			}
		}
		return ModelAsset(
			id,
			model,
			resolvedTextureIds,
			MeshData.from(model.geometry),
			Skeleton.from(model.bones),
			MorphSet.from(model),
		)
	}

	private companion object {
		const val DEFAULT_MAX_MODEL_BYTES = 256 * 1024 * 1024
	}
}
