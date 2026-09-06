package com.micheanl.libmmd.deform

import com.micheanl.libmmd.Engine
import com.micheanl.libmmd.mixin.client.FrontendGpuDeviceAccessor
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.backend.opengl.GlBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import org.lwjgl.opengl.GL

object DeformableRuntimeFactory {
	fun create(
		asset: DeformableAsset,
		geometry: PmxGeometry,
		pose: Pose,
		output: GpuBuffer,
	): DeformableRuntime {
		RenderSystem.assertOnRenderThread()
		val deviceBackend = ((RenderSystem.getDevice() as? FrontendGpuDevice) as? FrontendGpuDeviceAccessor)
			?.`libmmd$getBackend`()
		val backendName = deviceBackend?.javaClass?.name
		if (output is VulkanGpuBuffer && deviceBackend is VulkanDevice) {
			try {
				return VulkanDeformableRuntime(asset, geometry, pose, deviceBackend, output)
			} catch (failure: RuntimeException) {
				Engine.logger.warn("Vulkan deformable compute unavailable; using CPU solver: {}", failure.message)
			}
		}
		if (
			output is GlBuffer &&
			backendName?.contains("opengl", ignoreCase = true) == true &&
			GL.getCapabilities().OpenGL43
		) {
			try {
				return OpenGlDeformableRuntime(asset, geometry, pose, output)
			} catch (failure: RuntimeException) {
				Engine.logger.warn("OpenGL deformable compute unavailable; using CPU solver: {}", failure.message)
			}
		}
		val selected = asset.withinParticleBudget(MAXIMUM_CPU_PARTICLES)
		return CpuDeformableRuntime(selected, geometry, pose)
	}

	private const val MAXIMUM_CPU_PARTICLES = 1_000
}
