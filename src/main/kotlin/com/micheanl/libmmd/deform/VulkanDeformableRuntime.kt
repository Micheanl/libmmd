package com.micheanl.libmmd.deform

import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.vulkan.VulkanCommandPool
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer
import org.joml.Vector3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.util.vma.VmaAllocationInfo
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkComputePipelineCreateInfo
import org.lwjgl.vulkan.VkDescriptorBufferInfo
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolSize
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkMemoryBarrier
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkPushConstantRange
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import org.lwjgl.vulkan.VkSubmitInfo
import org.lwjgl.vulkan.VkWriteDescriptorSet
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VulkanDeformableRuntime(
	override val asset: DeformableAsset,
	geometry: PmxGeometry,
	pose: Pose,
	private val device: VulkanDevice,
	private val output: VulkanGpuBuffer,
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
	private val pushConstants = ByteBuffer.allocateDirect(PUSH_CONSTANT_BYTES).order(ByteOrder.nativeOrder())
	private val positions = storage("positions", compute.particleBytes())
	private val previousPositions = storage("previous positions", compute.particleBytes())
	private val previousStepPositions = storage("previous step positions", compute.particleBytes())
	private val anchors = storage("anchors", asset.particles.size * VECTOR_BYTES)
	private val distanceConstraints = storage("distance constraints", compute.distanceConstraintBytes())
	private val distanceLambdas = storage("distance lambdas", asset.distanceConstraints.size * Float.SIZE_BYTES)
	private val bendingConstraints = storage("bending constraints", compute.bendingConstraintBytes())
	private val bendingLambdas = storage("bending lambdas", asset.bendingConstraints.size * Float.SIZE_BYTES)
	private val volumeConstraints = storage("volume constraints", compute.volumeConstraintBytes())
	private val volumeTriangleIndices = storage("volume triangles", compute.volumeTriangleIndexBytes())
	private val volumeParticleIndices = storage("volume particles", compute.volumeParticleIndexBytes())
	private val volumeGradients = storage("volume gradients", asset.particles.size * VECTOR_BYTES)
	private val volumeLambdas = storage("volume lambdas", asset.volumeConstraints.size * Float.SIZE_BYTES)
	private val capsules = storage("capsules", bodyCollisions.colliderCount * CAPSULE_BYTES)
	private val hashCapacity = nextPowerOfTwo(maxOf(asset.particles.size * 2, 2))
	private val cellHeads = storage("cell heads", hashCapacity * Int.SIZE_BYTES)
	private val nextParticles = storage("next particles", asset.particles.size * Int.SIZE_BYTES)
	private val metadata = storage("metadata", compute.particleMetadataBytes())
	private val neighbors = storage("neighbors", compute.neighborIndexBytes())
	private val corrections = storage("corrections", asset.particles.size * VECTOR_BYTES)
	private val outputStorage = storage(
		"output",
		asset.vertexCount * VECTOR_BYTES,
		VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
	)
	private val dummy = storage("dummy", VECTOR_BYTES)
	private val storageBuffers = listOf(
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
		outputStorage,
		dummy,
	)
	private val descriptorLayout = createDescriptorLayout()
	private val pipelineLayout = createPipelineLayout()
	private val descriptorPool = createDescriptorPool()
	private val integrateSet = descriptorSet(mapOf(0 to positions, 1 to previousPositions, 2 to previousStepPositions, 3 to anchors))
	private val distanceSet = descriptorSet(mapOf(0 to positions, 1 to distanceConstraints, 2 to distanceLambdas))
	private val bendingSet = descriptorSet(mapOf(0 to positions, 1 to bendingConstraints, 2 to bendingLambdas))
	private val volumeSet = descriptorSet(
		mapOf(
			0 to positions,
			1 to volumeConstraints,
			2 to volumeTriangleIndices,
			3 to volumeParticleIndices,
			4 to volumeGradients,
			5 to volumeLambdas,
		),
	)
	private val capsuleSet = descriptorSet(mapOf(0 to positions, 1 to previousPositions, 2 to capsules))
	private val hashSet = descriptorSet(mapOf(0 to positions, 1 to cellHeads, 2 to nextParticles))
	private val selfCollisionSet = descriptorSet(
		mapOf(0 to positions, 1 to cellHeads, 2 to nextParticles, 3 to metadata, 4 to neighbors, 5 to corrections),
	)
	private val applyCollisionSet = descriptorSet(mapOf(0 to positions, 1 to corrections))
	private val outputSet = descriptorSet(
		mapOf(0 to positions, 1 to previousStepPositions, 2 to anchors, 3 to metadata, 4 to outputStorage),
	)
	private val pipelines = arrayOf(
		pipeline("xpbd_integrate.comp"),
		pipeline("xpbd_constraint.comp"),
		pipeline("xpbd_volume.comp"),
		pipeline("xpbd_capsule.comp"),
		pipeline("xpbd_hash.comp"),
		pipeline("xpbd_self_collision.comp"),
		pipeline("xpbd_apply_collision.comp"),
		pipeline("xpbd_output.comp"),
	)
	private val commandPool = VulkanCommandPool(device, device.graphicsQueue())
	private val fence = createFence()
	private var accumulator = 0f
	private var initialized = false
	private var inFlight = false
	private var closed = false

	override val backend: DeformableBackend = DeformableBackend.VULKAN_COMPUTE

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
		awaitPrevious()
		animator.sample(pose, animatedPositions)
		val currentCapsules = bodyCollisions.update(pose)
		packAnchors()
		anchors.write(anchorBytes)
		packCapsules(currentCapsules)
		capsules.write(capsuleBytes)
		if (!initialized) initializeState()
		accumulator = (accumulator + deltaSeconds).coerceAtMost(configuration.maximumFrameSeconds)
		var steps = 0
		val command = beginCommands()
		hostBarrier(command)
		while (accumulator >= configuration.fixedStepSeconds) {
			step(command, currentCapsules.size)
			accumulator -= configuration.fixedStepSeconds
			steps++
		}
		writeOutput(command)
		copyOutput(command)
		endAndSubmit(command)
		return steps
	}

	override fun offsetBytes(): ByteBuffer? = null

	override fun reset(pose: Pose) {
		RenderSystem.assertOnRenderThread()
		check(!closed)
		awaitPrevious()
		animator.sample(pose, animatedPositions)
		bodyCollisions.reset()
		packAnchors()
		anchors.write(anchorBytes)
		initializeState()
		outputStorage.clear()
		initialized = false
		accumulator = 0f
		motionCoupling.reset()
	}

	override fun allFinite(): Boolean = !closed

	override fun close() {
		if (closed) return
		RenderSystem.assertOnRenderThread()
		awaitPrevious()
		VK13.vkDestroyFence(device.vkDevice(), fence, null)
		commandPool.destroy()
		pipelines.forEach { VK13.vkDestroyPipeline(device.vkDevice(), it, null) }
		VK13.vkDestroyDescriptorPool(device.vkDevice(), descriptorPool, null)
		VK13.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null)
		VK13.vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorLayout, null)
		storageBuffers.forEach(Storage::close)
		closed = true
	}

	private fun initializeState() {
		val bytes = ByteBuffer.allocateDirect(asset.particles.size * VECTOR_BYTES).order(ByteOrder.nativeOrder())
		asset.particles.forEachIndexed { index, particle ->
			val position = animatedPositions[index]
			bytes.putFloat(position.x).putFloat(position.y).putFloat(position.z).putFloat(particle.inverseMass)
		}
		bytes.flip()
		positions.write(bytes)
		previousPositions.write(bytes)
		previousStepPositions.write(bytes)
		initialized = true
	}

	private fun step(command: VkCommandBuffer, capsuleCount: Int) {
		VK13.vkCmdFillBuffer(command, distanceLambdas.buffer, 0, VK13.VK_WHOLE_SIZE, 0)
		VK13.vkCmdFillBuffer(command, bendingLambdas.buffer, 0, VK13.VK_WHOLE_SIZE, 0)
		VK13.vkCmdFillBuffer(command, volumeLambdas.buffer, 0, VK13.VK_WHOLE_SIZE, 0)
		transferBarrier(command)
		pushConstants.clear()
		pushConstants.putInt(0, asset.particles.size)
		pushConstants.putFloat(4, configuration.fixedStepSeconds)
		pushConstants.putFloat(8, configuration.damping)
		pushConstants.putFloat(16, configuration.gravity.x + motionCoupling.acceleration.x)
		pushConstants.putFloat(20, configuration.gravity.y + motionCoupling.acceleration.y)
		pushConstants.putFloat(24, configuration.gravity.z + motionCoupling.acceleration.z)
		dispatch(command, pipelines[INTEGRATE], integrateSet, asset.particles.size, pushConstants, PUSH_CONSTANT_BYTES)
		computeBarrier(command)
		repeat(configuration.solverIterations) {
			solveConstraints(command, distanceSet, compute.distanceBatches, false)
			solveConstraints(command, bendingSet, compute.bendingBatches, false)
			solveVolumes(command)
			solveConstraints(command, distanceSet, compute.distanceBatches, true)
			if (capsuleCount > 0) solveCapsules(command, capsuleCount)
			if (configuration.selfCollision) solveSelfCollision(command)
		}
	}

	private fun solveConstraints(
		command: VkCommandBuffer,
		set: Long,
		batches: List<ComputeBatch>,
		hardLimit: Boolean,
	) {
		batches.forEach { batch ->
			pushConstants.clear()
			pushConstants.putInt(0, batch.first)
			pushConstants.putInt(4, batch.count)
			pushConstants.putFloat(8, configuration.fixedStepSeconds)
			pushConstants.putFloat(12, configuration.maximumStretchRatio)
			pushConstants.putInt(16, if (hardLimit) 1 else 0)
			dispatch(command, pipelines[CONSTRAINT], set, batch.count, pushConstants, 20)
			computeBarrier(command)
		}
	}

	private fun solveVolumes(command: VkCommandBuffer) {
		if (asset.volumeConstraints.isEmpty()) return
		pushConstants.clear()
		pushConstants.putInt(0, asset.volumeConstraints.size)
		pushConstants.putFloat(4, configuration.fixedStepSeconds)
		dispatchGroups(command, pipelines[VOLUME], volumeSet, asset.volumeConstraints.size, pushConstants, 8)
		computeBarrier(command)
	}

	private fun solveCapsules(command: VkCommandBuffer, capsuleCount: Int) {
		pushConstants.clear()
		pushConstants.putInt(0, asset.particles.size)
		pushConstants.putInt(4, capsuleCount)
		pushConstants.putFloat(8, configuration.particleRadius)
		pushConstants.putFloat(12, configuration.friction)
		dispatch(command, pipelines[CAPSULE], capsuleSet, asset.particles.size, pushConstants, 16)
		computeBarrier(command)
	}

	private fun solveSelfCollision(command: VkCommandBuffer) {
		VK13.vkCmdFillBuffer(command, cellHeads.buffer, 0, VK13.VK_WHOLE_SIZE, -1)
		VK13.vkCmdFillBuffer(command, corrections.buffer, 0, VK13.VK_WHOLE_SIZE, 0)
		transferBarrier(command)
		pushConstants.clear()
		pushConstants.putInt(0, asset.particles.size)
		pushConstants.putInt(4, hashCapacity - 1)
		pushConstants.putFloat(8, configuration.particleRadius * 2f)
		dispatch(command, pipelines[HASH], hashSet, asset.particles.size, pushConstants, 12)
		computeBarrier(command)
		pushConstants.putFloat(12, configuration.particleRadius * 2f)
		dispatch(command, pipelines[SELF_COLLISION], selfCollisionSet, asset.particles.size, pushConstants, 16)
		computeBarrier(command)
		pushConstants.clear()
		pushConstants.putInt(0, asset.particles.size)
		dispatch(command, pipelines[APPLY_COLLISION], applyCollisionSet, asset.particles.size, pushConstants, 4)
		computeBarrier(command)
	}

	private fun writeOutput(command: VkCommandBuffer) {
		pushConstants.clear()
		pushConstants.putInt(0, asset.particles.size)
		pushConstants.putFloat(4, interpolationAlpha)
		dispatch(command, pipelines[OUTPUT], outputSet, asset.particles.size, pushConstants, 8)
		computeBarrier(command)
	}

	private fun copyOutput(command: VkCommandBuffer) {
		MemoryStack.stackPush().use { stack ->
			val barrier = VkMemoryBarrier.calloc(1, stack)
				.`sType$Default`()
				.srcAccessMask(VK13.VK_ACCESS_SHADER_WRITE_BIT)
				.dstAccessMask(VK13.VK_ACCESS_TRANSFER_READ_BIT)
			VK13.vkCmdPipelineBarrier(
				command,
				VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
				VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
				0,
				barrier,
				null,
				null,
			)
			val copy = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(outputStorage.size)
			VK13.vkCmdCopyBuffer(command, outputStorage.buffer, output.vkBuffer(), copy)
			barrier.srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT)
			VK13.vkCmdPipelineBarrier(
				command,
				VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK13.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
				0,
				barrier,
				null,
				null,
			)
		}
	}

	private fun dispatch(
		command: VkCommandBuffer,
		pipeline: Long,
		set: Long,
		count: Int,
		constants: ByteBuffer,
		constantSize: Int,
	) = dispatchGroups(command, pipeline, set, (count + WORK_GROUP_SIZE - 1) / WORK_GROUP_SIZE, constants, constantSize)

	private fun dispatchGroups(
		command: VkCommandBuffer,
		pipeline: Long,
		set: Long,
		groups: Int,
		constants: ByteBuffer,
		constantSize: Int,
	) {
		if (groups <= 0) return
		MemoryStack.stackPush().use { stack ->
			VK13.vkCmdBindPipeline(command, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)
			VK13.vkCmdBindDescriptorSets(
				command,
				VK13.VK_PIPELINE_BIND_POINT_COMPUTE,
				pipelineLayout,
				0,
				stack.longs(set),
				null,
			)
			constants.position(0).limit(constantSize)
			VK13.vkCmdPushConstants(command, pipelineLayout, VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, constants)
			VK13.vkCmdDispatch(command, groups, 1, 1)
		}
	}

	private fun hostBarrier(command: VkCommandBuffer) = barrier(
		command,
		VK13.VK_PIPELINE_STAGE_HOST_BIT,
		VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		VK13.VK_ACCESS_HOST_WRITE_BIT,
		VK13.VK_ACCESS_SHADER_READ_BIT or VK13.VK_ACCESS_SHADER_WRITE_BIT,
	)

	private fun transferBarrier(command: VkCommandBuffer) = barrier(
		command,
		VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
		VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
		VK13.VK_ACCESS_SHADER_READ_BIT or VK13.VK_ACCESS_SHADER_WRITE_BIT,
	)

	private fun computeBarrier(command: VkCommandBuffer) = barrier(
		command,
		VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		VK13.VK_ACCESS_SHADER_WRITE_BIT,
		VK13.VK_ACCESS_SHADER_READ_BIT or VK13.VK_ACCESS_SHADER_WRITE_BIT,
	)

	private fun barrier(command: VkCommandBuffer, sourceStage: Int, targetStage: Int, sourceAccess: Int, targetAccess: Int) {
		MemoryStack.stackPush().use { stack ->
			val barrier = VkMemoryBarrier.calloc(1, stack)
				.`sType$Default`()
				.srcAccessMask(sourceAccess)
				.dstAccessMask(targetAccess)
			VK13.vkCmdPipelineBarrier(command, sourceStage, targetStage, 0, barrier, null, null)
		}
	}

	private fun beginCommands(): VkCommandBuffer {
		commandPool.reset()
		val command = commandPool.allocateBuffer()
		MemoryStack.stackPush().use { stack ->
			val begin = VkCommandBufferBeginInfo.calloc(stack)
				.`sType$Default`()
				.flags(VK13.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
			checkVk(VK13.vkBeginCommandBuffer(command, begin), "begin compute commands")
		}
		return command
	}

	private fun endAndSubmit(command: VkCommandBuffer) {
		checkVk(VK13.vkEndCommandBuffer(command), "end compute commands")
		MemoryStack.stackPush().use { stack ->
			val submit = VkSubmitInfo.calloc(1, stack)
				.`sType$Default`()
				.pCommandBuffers(stack.pointers(command.address()))
			checkVk(VK13.vkQueueSubmit(device.graphicsQueue().vkQueue(), submit, fence), "submit compute commands")
		}
		inFlight = true
	}

	private fun awaitPrevious() {
		if (!inFlight) return
		MemoryStack.stackPush().use { stack ->
			checkVk(VK13.vkWaitForFences(device.vkDevice(), stack.longs(fence), true, Long.MAX_VALUE), "wait for compute")
			checkVk(VK13.vkResetFences(device.vkDevice(), stack.longs(fence)), "reset compute fence")
		}
		inFlight = false
	}

	private fun createFence(): Long = MemoryStack.stackPush().use { stack ->
		val info = VkFenceCreateInfo.calloc(stack).`sType$Default`()
		val result = stack.mallocLong(1)
		checkVk(VK13.vkCreateFence(device.vkDevice(), info, null, result), "create compute fence")
		result[0]
	}

	private fun createDescriptorLayout(): Long = MemoryStack.stackPush().use { stack ->
		val bindings = VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDINGS, stack)
		for (binding in 0 until DESCRIPTOR_BINDINGS) {
			bindings[binding]
				.binding(binding)
				.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(1)
				.stageFlags(VK13.VK_SHADER_STAGE_COMPUTE_BIT)
		}
		val info = VkDescriptorSetLayoutCreateInfo.calloc(stack).`sType$Default`().pBindings(bindings)
		val result = stack.mallocLong(1)
		checkVk(VK13.vkCreateDescriptorSetLayout(device.vkDevice(), info, null, result), "create descriptor layout")
		result[0]
	}

	private fun createPipelineLayout(): Long = MemoryStack.stackPush().use { stack ->
		val range = VkPushConstantRange.calloc(1, stack)
			.stageFlags(VK13.VK_SHADER_STAGE_COMPUTE_BIT)
			.offset(0)
			.size(PUSH_CONSTANT_BYTES)
		val info = VkPipelineLayoutCreateInfo.calloc(stack)
			.`sType$Default`()
			.pSetLayouts(stack.longs(descriptorLayout))
			.pPushConstantRanges(range)
		val result = stack.mallocLong(1)
		checkVk(VK13.vkCreatePipelineLayout(device.vkDevice(), info, null, result), "create compute pipeline layout")
		result[0]
	}

	private fun createDescriptorPool(): Long = MemoryStack.stackPush().use { stack ->
		val sizes = VkDescriptorPoolSize.calloc(1, stack)
			.type(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
			.descriptorCount(DESCRIPTOR_SET_COUNT * DESCRIPTOR_BINDINGS)
		val info = VkDescriptorPoolCreateInfo.calloc(stack)
			.`sType$Default`()
			.maxSets(DESCRIPTOR_SET_COUNT)
			.pPoolSizes(sizes)
		val result = stack.mallocLong(1)
		checkVk(VK13.vkCreateDescriptorPool(device.vkDevice(), info, null, result), "create descriptor pool")
		result[0]
	}

	private fun descriptorSet(mapping: Map<Int, Storage>): Long = MemoryStack.stackPush().use { stack ->
		val allocate = VkDescriptorSetAllocateInfo.calloc(stack)
			.`sType$Default`()
			.descriptorPool(descriptorPool)
			.pSetLayouts(stack.longs(descriptorLayout))
		val result = stack.mallocLong(1)
		checkVk(VK13.vkAllocateDescriptorSets(device.vkDevice(), allocate, result), "allocate descriptor set")
		val set = result[0]
		val writes = VkWriteDescriptorSet.calloc(DESCRIPTOR_BINDINGS, stack)
		for (binding in 0 until DESCRIPTOR_BINDINGS) {
			val info = VkDescriptorBufferInfo.calloc(1, stack)
				.buffer(mapping[binding]?.buffer ?: dummy.buffer)
				.offset(0)
				.range(VK13.VK_WHOLE_SIZE)
			writes[binding]
				.`sType$Default`()
				.dstSet(set)
				.dstBinding(binding)
				.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.pBufferInfo(info)
		}
		VK13.vkUpdateDescriptorSets(device.vkDevice(), writes, null)
		set
	}

	private fun pipeline(resource: String): Long {
		val code = VulkanComputeShaderCompiler.compile(resource)
		try {
			return MemoryStack.stackPush().use { stack ->
				val moduleInfo = VkShaderModuleCreateInfo.calloc(stack).`sType$Default`().pCode(code)
				val moduleResult = stack.mallocLong(1)
				checkVk(VK13.vkCreateShaderModule(device.vkDevice(), moduleInfo, null, moduleResult), "create $resource module")
				val module = moduleResult[0]
				try {
					val stage = VkPipelineShaderStageCreateInfo.calloc(stack)
						.`sType$Default`()
						.stage(VK13.VK_SHADER_STAGE_COMPUTE_BIT)
						.module(module)
						.pName(stack.UTF8("main"))
					val pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
						.`sType$Default`()
						.stage(stage)
						.layout(pipelineLayout)
					val result = stack.mallocLong(1)
					checkVk(
						VK13.vkCreateComputePipelines(device.vkDevice(), VK13.VK_NULL_HANDLE, pipelineInfo, null, result),
						"create $resource pipeline",
					)
					result[0]
				} finally {
					VK13.vkDestroyShaderModule(device.vkDevice(), module, null)
				}
			}
		} finally {
			MemoryUtil.memFree(code)
		}
	}

	private fun storage(
		name: String,
		size: Int,
		usage: Int = VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
	): Storage = Storage(name, maxOf(size, Int.SIZE_BYTES).toLong(), usage)

	private fun storage(name: String, bytes: ByteBuffer): Storage =
		Storage(name, maxOf(bytes.remaining(), Int.SIZE_BYTES).toLong(), VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).also {
			if (bytes.hasRemaining()) it.write(bytes)
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

	private fun nextPowerOfTwo(value: Int): Int {
		var result = 1
		while (result < value) result = result shl 1
		return result
	}

	private fun checkVk(result: Int, operation: String) {
		check(result == VK13.VK_SUCCESS) { "$operation failed with Vulkan result $result" }
	}

	private inner class Storage(name: String, val size: Long, usage: Int) : AutoCloseable {
		val buffer: Long
		private val allocation: Long
		private val mapped: Long

		init {
			MemoryStack.stackPush().use { stack ->
				val bufferInfo = VkBufferCreateInfo.calloc(stack)
					.`sType$Default`()
					.size(size)
					.usage(usage or VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
					.sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE)
				val allocationInfo = VmaAllocationCreateInfo.calloc(stack)
					.usage(Vma.VMA_MEMORY_USAGE_AUTO)
					.flags(
						Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT or
							Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT,
					)
				val bufferResult = stack.mallocLong(1)
				val allocationResult = stack.mallocPointer(1)
				val details = VmaAllocationInfo.calloc(stack)
				checkVk(
					Vma.vmaCreateBuffer(
						device.vma(),
						bufferInfo,
						allocationInfo,
						bufferResult,
						allocationResult,
						details,
					),
					"allocate $name",
				)
				buffer = bufferResult[0]
				allocation = allocationResult[0]
				mapped = details.pMappedData()
			}
			check(mapped != 0L) { "Unable to map $name" }
			clear()
		}

		fun write(bytes: ByteBuffer) {
			require(bytes.remaining().toLong() <= size)
			MemoryUtil.memCopy(MemoryUtil.memAddress(bytes), mapped, bytes.remaining().toLong())
			Vma.vmaFlushAllocation(device.vma(), allocation, 0, bytes.remaining().toLong())
		}

		fun clear() {
			MemoryUtil.memSet(mapped, 0, size)
			Vma.vmaFlushAllocation(device.vma(), allocation, 0, size)
		}

		override fun close() = Vma.vmaDestroyBuffer(device.vma(), buffer, allocation)
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
		const val DESCRIPTOR_BINDINGS = 6
		const val DESCRIPTOR_SET_COUNT = 9
		const val PUSH_CONSTANT_BYTES = 32
	}
}
