package com.micheanl.libmmd.physics

import org.joml.Vector3f

enum class PhysicsProcessor {
	AUTO,
	CPU,
	GPU,
}

data class PhysicsConfiguration(
	val gravity: Vector3f = Vector3f(0f, -9.81f, 0f),
	val workerThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4),
	val fixedStepSeconds: Float = 1f / 60f,
	val maximumFrameSeconds: Float = 0.25f,
	val preferredProcessor: PhysicsProcessor = PhysicsProcessor.AUTO,
	val allowCpuFallback: Boolean = true,
) {
	init {
		require(gravity.isFinite) { "Gravity must be finite" }
		require(workerThreads > 0) { "Worker threads must be positive" }
		require(fixedStepSeconds.isFinite() && fixedStepSeconds > 0f) { "Fixed step must be finite and positive" }
		require(maximumFrameSeconds.isFinite() && maximumFrameSeconds >= fixedStepSeconds) {
			"Maximum frame time must be finite and at least one fixed step"
		}
	}
}

data class PhysicsDevice(
	val requestedProcessor: PhysicsProcessor,
	val activeProcessor: PhysicsProcessor,
	val gpuAvailable: Boolean,
	val fallbackReason: String?,
)

internal object PhysicsDeviceResolver {
	private const val GPU_UNAVAILABLE = "physx-jni 2.7.2 does not include CUDA bindings"

	fun resolve(configuration: PhysicsConfiguration): PhysicsDevice {
		if (configuration.preferredProcessor == PhysicsProcessor.GPU && !configuration.allowCpuFallback) {
			throw IllegalStateException(GPU_UNAVAILABLE)
		}
		return PhysicsDevice(
			requestedProcessor = configuration.preferredProcessor,
			activeProcessor = PhysicsProcessor.CPU,
			gpuAvailable = false,
			fallbackReason = GPU_UNAVAILABLE.takeIf { configuration.preferredProcessor == PhysicsProcessor.GPU },
		)
	}
}
