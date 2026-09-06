package com.micheanl.libmmd.asset

import com.micheanl.libmmd.format.vmd.VmdDecodeLimits
import com.micheanl.libmmd.format.vmd.VmdDecoder
import net.minecraft.resources.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class MotionRepository(
	private val resources: ResourceSource,
	private val decodeExecutor: Executor,
	private val decodeLimits: VmdDecodeLimits = VmdDecodeLimits(),
	private val maxMotionBytes: Int = DEFAULT_MAX_MOTION_BYTES,
) : AutoCloseable {
	private val motions = ConcurrentHashMap<Identifier, CompletableFuture<MotionAsset>>()

	init {
		require(maxMotionBytes in 1 until Int.MAX_VALUE) { "maxMotionBytes must be between 1 and ${Int.MAX_VALUE - 1}" }
	}

	val cachedMotionCount: Int
		get() = motions.size

	fun load(id: Identifier): CompletableFuture<MotionAsset> {
		val future = motions.computeIfAbsent(id) { motionId ->
			CompletableFuture.supplyAsync({ decode(motionId) }, decodeExecutor)
		}
		future.whenComplete { _, error ->
			if (error != null) motions.remove(id, future)
		}
		return future
	}

	fun invalidate(id: Identifier): Boolean = motions.remove(id) != null

	fun clear() = motions.clear()

	override fun close() = clear()

	private fun decode(id: Identifier): MotionAsset {
		val bytes = resources.read(id, maxMotionBytes)
		if (bytes.size > maxMotionBytes) {
			throw AssetLoadException("Resource $id exceeds the $maxMotionBytes byte limit")
		}
		return MotionAsset(id, VmdDecoder.readMotion(bytes, decodeLimits))
	}

	private companion object {
		const val DEFAULT_MAX_MOTION_BYTES = 64 * 1024 * 1024
	}
}
