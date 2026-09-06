package com.micheanl.libmmd.asset

import com.micheanl.libmmd.deform.DeformableAsset
import com.micheanl.libmmd.deform.DeformableRegionExtractor
import net.minecraft.resources.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

fun interface RenderBackend<T : AutoCloseable> {
	fun upload(asset: ModelAsset): T
}

data class RenderAsset<T : AutoCloseable>(
	val source: ModelAsset,
	val renderData: T,
	val deformable: DeformableAsset = DeformableAsset.empty(source.model.geometry.vertexCount),
)

class RenderRepository<T : AutoCloseable>(
	private val models: ModelRepository,
	private val renderExecutor: Executor,
	private val backend: RenderBackend<T>,
) {
	private val assets = ConcurrentHashMap<Identifier, CompletableFuture<RenderAsset<T>>>()

	val cachedAssetCount: Int
		get() = assets.size

	fun load(id: Identifier): CompletableFuture<RenderAsset<T>> {
		val future = assets.computeIfAbsent(id) { modelId ->
			models.load(modelId).thenApplyAsync(
				{ model -> RenderAsset(model, backend.upload(model), DeformableRegionExtractor.extract(model.model)) },
				renderExecutor,
			)
		}
		future.whenComplete { _, error ->
			if (error != null) {
				assets.remove(id, future)
			}
		}
		return future
	}

	fun invalidate(id: Identifier): CompletableFuture<Void> {
		val future = assets.remove(id) ?: return CompletableFuture.completedFuture(null)
		return release(future)
	}

	fun clear(): CompletableFuture<Void> {
		val releases = assets.entries.toList().mapNotNull { (id, future) ->
			if (assets.remove(id, future)) release(future) else null
		}
		return CompletableFuture.allOf(*releases.toTypedArray())
	}

	private fun release(future: CompletableFuture<RenderAsset<T>>): CompletableFuture<Void> {
		val released = CompletableFuture<Void>()
		future.whenComplete { asset, error ->
			if (error != null || asset == null) {
				released.complete(null)
			} else {
				renderExecutor.execute {
					try {
						asset.renderData.close()
						released.complete(null)
					} catch (exception: Throwable) {
						released.completeExceptionally(exception)
					}
				}
			}
		}
		return released
	}
}
