package com.micheanl.libmmd.deform

import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.opengl.GlBuffer
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenGlDeformableRuntime(
	override val asset: DeformableAsset,
	geometry: PmxGeometry,
	pose: Pose,
	private val output: GlBuffer,
	private val configuration: XpbdConfiguration = XpbdConfiguration(),
) : DeformableRuntime {
	private val animator = DeformableAnimator(asset, geometry)
	private val bodyCollisions = BodyCollisionRig(HumanoidRig.from(pose.skeleton))
	private val compute = XpbdComputeData.from(asset, configuration)
	private val animatedPositions = Array(asset.particles.size) { Vector3f() }
	private val motionCoupling = DeformableMotionCoupling()
	private val anchorBytes = ByteBuffer.allocateDirect(asset.particles.size * VECTOR_BYTES).order(ByteOrder.nativeOrder())
	private val capsuleBytes = ByteBuffer.allocateDirect(maxOf(bodyCollisions.colliderCount, 1) * CAPSULE_BYTES)
		.order(ByteOrder.nativeOrder())
	private val programs = intArrayOf(
		program("xpbd_integrate.comp"),
		program("xpbd_constraint.comp"),
		program("xpbd_volume.comp"),
		program("xpbd_capsule.comp"),
		program("xpbd_hash.comp"),
		program("xpbd_self_collision.comp"),
		program("xpbd_apply_collision.comp"),
		program("xpbd_output.comp"),
	)
	private val positions = buffer(compute.particleBytes(), GL43C.GL_DYNAMIC_DRAW)
	private val previousPositions = buffer(compute.particleBytes(), GL43C.GL_DYNAMIC_DRAW)
	private val previousStepPositions = buffer(compute.particleBytes(), GL43C.GL_DYNAMIC_DRAW)
	private val anchors = buffer(asset.particles.size * VECTOR_BYTES, GL43C.GL_DYNAMIC_DRAW)
	private val distanceConstraints = buffer(compute.distanceConstraintBytes(), GL43C.GL_STATIC_DRAW)
	private val distanceLambdas = buffer(maxOf(asset.distanceConstraints.size * Float.SIZE_BYTES, Float.SIZE_BYTES), GL43C.GL_DYNAMIC_DRAW)
	private val bendingConstraints = buffer(compute.bendingConstraintBytes(), GL43C.GL_STATIC_DRAW)
	private val bendingLambdas = buffer(maxOf(asset.bendingConstraints.size * Float.SIZE_BYTES, Float.SIZE_BYTES), GL43C.GL_DYNAMIC_DRAW)
	private val volumeConstraints = buffer(compute.volumeConstraintBytes(), GL43C.GL_STATIC_DRAW)
	private val volumeTriangleIndices = buffer(compute.volumeTriangleIndexBytes(), GL43C.GL_STATIC_DRAW)
	private val volumeParticleIndices = buffer(compute.volumeParticleIndexBytes(), GL43C.GL_STATIC_DRAW)
	private val volumeGradients = buffer(asset.particles.size * VECTOR_BYTES, GL43C.GL_DYNAMIC_DRAW)
	private val volumeLambdas = buffer(maxOf(asset.volumeConstraints.size * Float.SIZE_BYTES, Float.SIZE_BYTES), GL43C.GL_DYNAMIC_DRAW)
	private val capsules = buffer(maxOf(bodyCollisions.colliderCount * CAPSULE_BYTES, CAPSULE_BYTES), GL43C.GL_DYNAMIC_DRAW)
	private val hashCapacity = nextPowerOfTwo(maxOf(asset.particles.size * 2, 2))
	private val cellHeads = buffer(hashCapacity * Int.SIZE_BYTES, GL43C.GL_DYNAMIC_DRAW)
	private val nextParticles = buffer(asset.particles.size * Int.SIZE_BYTES, GL43C.GL_DYNAMIC_DRAW)
	private val metadata = buffer(compute.particleMetadataBytes(), GL43C.GL_STATIC_DRAW)
	private val neighbors = buffer(compute.neighborIndexBytes(), GL43C.GL_STATIC_DRAW)
	private val corrections = buffer(asset.particles.size * VECTOR_BYTES, GL43C.GL_DYNAMIC_DRAW)
	private val buffers = intArrayOf(
		positions,
		previousPositions,
		previousStepPositions,
		anchors,
		distanceConstraints,
		distanceLambdas,
		bendingConstraints,
		bendingLambdas,
		volumeConstraints,
		volumeTriangleIndices,
		volumeParticleIndices,
		volumeGradients,
		volumeLambdas,
		capsules,
		cellHeads,
		nextParticles,
		metadata,
		neighbors,
		corrections,
	)
	private var accumulator = 0f
	private var initialized = false
	private var healthy = true
	private var closed = false

	init {
		RenderSystem.assertOnRenderThread()
		require(GL.getCapabilities().OpenGL43) { "OpenGL 4.3 compute shaders are required" }
	}

	override val backend: DeformableBackend = DeformableBackend.OPENGL_COMPUTE

	override val interpolationAlpha: Float
		get() = (accumulator / configuration.fixedStepSeconds).coerceIn(0f, 1f)

	override val colliderCount: Int
		get() = bodyCollisions.colliderCount

	override fun updateMotion(input: DeformableMotionInput, deltaSeconds: Float) {
		motionCoupling.update(input, deltaSeconds, initialized)
	}

	override fun advance(deltaSeconds: Float, pose: Pose): Int {
		RenderSystem.assertOnRenderThread()
		check(!closed)
		require(deltaSeconds.isFinite() && deltaSeconds >= 0f)
		animator.sample(pose, animatedPositions)
		val currentCapsules = bodyCollisions.update(pose)
		packAnchors()
		upload(anchors, anchorBytes)
		packCapsules(currentCapsules)
		upload(capsules, capsuleBytes)
		if (!initialized) initializeState()
		accumulator = (accumulator + deltaSeconds).coerceAtMost(configuration.maximumFrameSeconds)
		val previousProgram = GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM)
		var steps = 0
		try {
			while (accumulator >= configuration.fixedStepSeconds) {
				step(currentCapsules.size)
				accumulator -= configuration.fixedStepSeconds
				steps++
			}
			writeOutput()
			GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT or GL43C.GL_TEXTURE_FETCH_BARRIER_BIT)
		} finally {
			GL43C.glUseProgram(previousProgram)
		}
		return steps
	}

	override fun offsetBytes(): ByteBuffer? = null

	override fun reset(pose: Pose) {
		RenderSystem.assertOnRenderThread()
		check(!closed)
		animator.sample(pose, animatedPositions)
		bodyCollisions.reset()
		packAnchors()
		upload(anchors, anchorBytes)
		initializeState()
		clear(output.handle(), GL43C.GL_RGBA32F, GL43C.GL_RGBA, GL43C.GL_FLOAT, ZERO_FLOATS)
		initialized = false
		accumulator = 0f
		motionCoupling.reset()
	}

	override fun allFinite(): Boolean = healthy && !closed

	override fun close() {
		if (closed) return
		RenderSystem.assertOnRenderThread()
		buffers.forEach(GL43C::glDeleteBuffers)
		programs.forEach(GL43C::glDeleteProgram)
		closed = true
	}

	private fun initializeState() {
		val bytes = ByteBuffer.allocateDirect(asset.particles.size * VECTOR_BYTES).order(ByteOrder.nativeOrder())
		asset.particles.forEachIndexed { index, particle ->
			val position = animatedPositions[index]
			bytes.putFloat(position.x).putFloat(position.y).putFloat(position.z).putFloat(particle.inverseMass)
		}
		bytes.flip()
		upload(positions, bytes)
		upload(previousPositions, bytes)
		upload(previousStepPositions, bytes)
		initialized = true
	}

	private fun step(capsuleCount: Int) {
		clear(distanceLambdas, GL43C.GL_R32F, GL43C.GL_RED, GL43C.GL_FLOAT, ZERO_FLOATS)
		clear(bendingLambdas, GL43C.GL_R32F, GL43C.GL_RED, GL43C.GL_FLOAT, ZERO_FLOATS)
		clear(volumeLambdas, GL43C.GL_R32F, GL43C.GL_RED, GL43C.GL_FLOAT, ZERO_FLOATS)
		use(programs[INTEGRATE]) {
			bind(0, positions)
			bind(1, previousPositions)
			bind(2, previousStepPositions)
			bind(3, anchors)
			uint("ParticleCount", asset.particles.size)
			float("DeltaSeconds", configuration.fixedStepSeconds)
			float("Damping", configuration.damping)
			vector(
				"Acceleration",
				configuration.gravity.x + motionCoupling.acceleration.x,
				configuration.gravity.y + motionCoupling.acceleration.y,
				configuration.gravity.z + motionCoupling.acceleration.z,
			)
			dispatch(asset.particles.size)
		}
		barrier()
		repeat(configuration.solverIterations) {
			solveConstraints(distanceConstraints, distanceLambdas, compute.distanceBatches, false)
			solveConstraints(bendingConstraints, bendingLambdas, compute.bendingBatches, false)
			solveVolumes()
			solveConstraints(distanceConstraints, distanceLambdas, compute.distanceBatches, true)
			if (capsuleCount > 0) solveCapsules(capsuleCount)
			if (configuration.selfCollision) solveSelfCollision()
		}
	}

	private fun solveVolumes() {
		if (asset.volumeConstraints.isEmpty()) return
		use(programs[VOLUME]) {
			bind(0, positions)
			bind(1, volumeConstraints)
			bind(2, volumeTriangleIndices)
			bind(3, volumeParticleIndices)
			bind(4, volumeGradients)
			bind(5, volumeLambdas)
			uint("ConstraintCount", asset.volumeConstraints.size)
			float("DeltaSeconds", configuration.fixedStepSeconds)
			GL43C.glDispatchCompute(asset.volumeConstraints.size, 1, 1)
		}
		barrier()
	}

	private fun solveConstraints(
		constraintBuffer: Int,
		lambdaBuffer: Int,
		batches: List<ComputeBatch>,
		hardLimit: Boolean,
	) {
		use(programs[CONSTRAINT]) {
			bind(0, positions)
			bind(1, constraintBuffer)
			bind(2, lambdaBuffer)
			float("DeltaSeconds", configuration.fixedStepSeconds)
			float("MaximumStretchRatio", configuration.maximumStretchRatio)
			integer("HardLimit", if (hardLimit) 1 else 0)
			batches.forEach { batch ->
				uint("FirstConstraint", batch.first)
				uint("ConstraintCount", batch.count)
				dispatch(batch.count)
				barrier()
			}
		}
	}

	private fun solveCapsules(capsuleCount: Int) {
		use(programs[CAPSULE]) {
			bind(0, positions)
			bind(1, previousPositions)
			bind(2, capsules)
			uint("ParticleCount", asset.particles.size)
			uint("CapsuleCount", capsuleCount)
			float("ParticleRadius", configuration.particleRadius)
			float("Friction", configuration.friction)
			dispatch(asset.particles.size)
		}
		barrier()
	}

	private fun solveSelfCollision() {
		clear(cellHeads, GL43C.GL_R32I, GL43C.GL_RED_INTEGER, GL43C.GL_INT, NEGATIVE_ONE)
		clear(corrections, GL43C.GL_RGBA32I, GL43C.GL_RGBA_INTEGER, GL43C.GL_INT, ZERO_INTS)
		use(programs[HASH]) {
			bind(0, positions)
			bind(1, cellHeads)
			bind(2, nextParticles)
			uint("ParticleCount", asset.particles.size)
			uint("HashMask", hashCapacity - 1)
			float("CellSize", configuration.particleRadius * 2f)
			dispatch(asset.particles.size)
		}
		barrier()
		use(programs[SELF_COLLISION]) {
			bind(0, positions)
			bind(1, cellHeads)
			bind(2, nextParticles)
			bind(3, metadata)
			bind(4, neighbors)
			bind(5, corrections)
			uint("ParticleCount", asset.particles.size)
			uint("HashMask", hashCapacity - 1)
			float("CellSize", configuration.particleRadius * 2f)
			float("MinimumDistance", configuration.particleRadius * 2f)
			dispatch(asset.particles.size)
		}
		barrier()
		use(programs[APPLY_COLLISION]) {
			bind(0, positions)
			bind(1, corrections)
			uint("ParticleCount", asset.particles.size)
			dispatch(asset.particles.size)
		}
		barrier()
	}

	private fun writeOutput() {
		use(programs[OUTPUT]) {
			bind(0, positions)
			bind(1, previousStepPositions)
			bind(2, anchors)
			bind(3, metadata)
			bind(4, output.handle())
			uint("ParticleCount", asset.particles.size)
			float("InterpolationAlpha", interpolationAlpha)
			dispatch(asset.particles.size)
		}
	}

	private fun packAnchors() {
		anchorBytes.clear()
		animatedPositions.forEach { anchorBytes.putFloat(it.x).putFloat(it.y).putFloat(it.z).putFloat(0f) }
		anchorBytes.flip()
	}

	private fun packCapsules(source: List<AnimatedCapsule>) {
		capsuleBytes.clear()
		source.forEach { capsule ->
			capsuleBytes.putFloat(capsule.previousStart.x)
				.putFloat(capsule.previousStart.y)
				.putFloat(capsule.previousStart.z)
				.putFloat(capsule.radius)
				.putFloat(capsule.previousEnd.x)
				.putFloat(capsule.previousEnd.y)
				.putFloat(capsule.previousEnd.z)
				.putFloat(capsule.friction)
				.putFloat(capsule.start.x)
				.putFloat(capsule.start.y)
				.putFloat(capsule.start.z)
				.putFloat(0f)
				.putFloat(capsule.end.x)
				.putFloat(capsule.end.y)
				.putFloat(capsule.end.z)
				.putFloat(0f)
		}
		capsuleBytes.flip()
	}

	private fun use(program: Int, block: () -> Unit) {
		GL43C.glUseProgram(program)
		block()
	}

	private fun bind(index: Int, buffer: Int) = GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, index, buffer)

	private fun uint(name: String, value: Int) = GL43C.glUniform1ui(location(name), value)

	private fun integer(name: String, value: Int) = GL43C.glUniform1i(location(name), value)

	private fun float(name: String, value: Float) = GL43C.glUniform1f(location(name), value)

	private fun vector(name: String, x: Float, y: Float, z: Float) = GL43C.glUniform3f(location(name), x, y, z)

	private fun location(name: String): Int = GL43C.glGetUniformLocation(GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM), name)

	private fun dispatch(count: Int) {
		if (count > 0) GL43C.glDispatchCompute((count + WORK_GROUP_SIZE - 1) / WORK_GROUP_SIZE, 1, 1)
	}

	private fun barrier() = GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)

	private fun upload(buffer: Int, bytes: ByteBuffer) {
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
		GL43C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, bytes)
	}

	private fun clear(buffer: Int, internalFormat: Int, format: Int, type: Int, value: ByteBuffer) {
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
		GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, internalFormat, format, type, value)
	}

	private fun buffer(bytes: ByteBuffer, usage: Int): Int {
		val buffer = GL43C.glGenBuffers()
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
		if (bytes.hasRemaining()) {
			GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, usage)
		} else {
			GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 4L, usage)
		}
		return buffer
	}

	private fun buffer(size: Int, usage: Int): Int {
		val buffer = GL43C.glGenBuffers()
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
		GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, maxOf(size, 4).toLong(), usage)
		return buffer
	}

	private fun program(resource: String): Int {
		val source = checkNotNull(javaClass.getResourceAsStream("/assets/libmmd/shaders/compute/$resource")) {
			"Missing compute shader $resource"
		}.bufferedReader().use { it.readText() }
		val shader = GL43C.glCreateShader(GL43C.GL_COMPUTE_SHADER)
		GL43C.glShaderSource(shader, source)
		GL43C.glCompileShader(shader)
		if (GL43C.glGetShaderi(shader, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
			val message = GL43C.glGetShaderInfoLog(shader)
			GL43C.glDeleteShader(shader)
			throw IllegalStateException("$resource compilation failed: $message")
		}
		val program = GL43C.glCreateProgram()
		GL43C.glAttachShader(program, shader)
		GL43C.glLinkProgram(program)
		GL43C.glDetachShader(program, shader)
		GL43C.glDeleteShader(shader)
		if (GL43C.glGetProgrami(program, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
			val message = GL43C.glGetProgramInfoLog(program)
			GL43C.glDeleteProgram(program)
			throw IllegalStateException("$resource link failed: $message")
		}
		return program
	}

	private fun nextPowerOfTwo(value: Int): Int {
		var result = 1
		while (result < value) result = result shl 1
		return result
	}

	private companion object {
		const val INTEGRATE = 0
		const val CONSTRAINT = 1
		const val VOLUME = 2
		const val CAPSULE = 3
		const val HASH = 4
		const val SELF_COLLISION = 5
		const val APPLY_COLLISION = 6
		const val OUTPUT = 7
		const val WORK_GROUP_SIZE = 128
		const val VECTOR_BYTES = 4 * Float.SIZE_BYTES
		const val CAPSULE_BYTES = 4 * VECTOR_BYTES
		val ZERO_FLOATS: ByteBuffer = BufferUtils.createByteBuffer(4 * Float.SIZE_BYTES)
		val ZERO_INTS: ByteBuffer = BufferUtils.createByteBuffer(4 * Int.SIZE_BYTES)
		val NEGATIVE_ONE: ByteBuffer = BufferUtils.createByteBuffer(Int.SIZE_BYTES).putInt(-1).flip()
	}
}
