package com.micheanl.libmmd.render

import com.micheanl.libmmd.animation.MorphWeights
import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.asset.RenderAsset
import com.micheanl.libmmd.deform.DeformableMotionInput
import com.micheanl.libmmd.deform.DeformableRuntime
import com.micheanl.libmmd.deform.DeformableRuntimeFactory
import com.micheanl.libmmd.physics.PhysicsRig
import com.micheanl.libmmd.physics.PhysicsWorld
import com.micheanl.libmmd.physics.PhysicsAssetConfiguration
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import org.joml.Matrix4f
import org.joml.Matrix4fc
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Environment(EnvType.CLIENT)
class ModelInstance internal constructor(
	val asset: RenderAsset<GpuMesh>,
	transform: Matrix4fc,
	physicsWorld: PhysicsWorld?,
	physicsConfiguration: PhysicsAssetConfiguration,
) {
	val pose = Pose(asset.source.skeleton)
	val morphWeights = MorphWeights(asset.source.morphs)
	val physics: PhysicsRig?
	val deformable: DeformableRuntime?
	internal val transform = Matrix4f(transform)
	private val boneTransforms: GpuBuffer
	private val morphOffsets: GpuBuffer
	private val softBodyOffsets: GpuBuffer

	val hasDynamicDeformation: Boolean
		get() = physics != null || deformable != null

	init {
		boneTransforms = createBoneTransforms()
		morphOffsets = try {
			createMorphOffsets()
		} catch (exception: Throwable) {
			boneTransforms.close()
			throw exception
		}
		softBodyOffsets = try {
			createSoftBodyOffsets()
		} catch (exception: Throwable) {
			try {
				morphOffsets.close()
			} finally {
				boneTransforms.close()
			}
			throw exception
		}
		deformable = asset.deformable
			.takeUnless { it.isEmpty }
			?.let { DeformableRuntimeFactory.create(it, asset.source.model.geometry, pose, softBodyOffsets) }
		try {
			physics = physicsWorld?.attach(asset.source.model, pose, transform, physicsConfiguration)
		} catch (exception: Throwable) {
			try {
				deformable?.close()
			} finally {
				try {
					softBodyOffsets.close()
				} finally {
					try {
						morphOffsets.close()
					} finally {
						boneTransforms.close()
					}
				}
			}
			throw exception
		}
	}

	fun setTransform(transform: Matrix4fc): ModelInstance = apply {
		physics?.teleport(transform)
		this.transform.set(transform)
	}

	fun setRenderTransform(transform: Matrix4fc): ModelInstance = apply {
		this.transform.set(transform)
	}

	fun followTransform(transform: Matrix4fc): ModelInstance = apply {
		physics?.follow(transform)
		this.transform.set(transform)
	}

	fun updateDeformableMotion(input: DeformableMotionInput, deltaSeconds: Float) {
		deformable?.updateMotion(input, deltaSeconds)
	}

	internal fun advanceDeformable(deltaSeconds: Float) {
		deformable?.advance(deltaSeconds, pose)
	}

	fun updatePose() {
		RenderSystem.assertOnRenderThread()
		val encoder = RenderSystem.getDevice().createCommandEncoder()
		encoder.writeToBuffer(boneTransforms.slice(), pose.paletteBytes())
		encoder.submit()
	}

	fun updateMorphs() {
		RenderSystem.assertOnRenderThread()
		if (!morphWeights.needsUpload) return
		val encoder = RenderSystem.getDevice().createCommandEncoder()
		encoder.writeToBuffer(morphOffsets.slice(), morphWeights.vertexOffsetBytes())
		encoder.submit()
	}

	fun updateDeformation() {
		RenderSystem.assertOnRenderThread()
		val encoder = RenderSystem.getDevice().createCommandEncoder()
		encoder.writeToBuffer(boneTransforms.slice(), pose.paletteBytes())
		if (morphWeights.needsUpload) {
			encoder.writeToBuffer(morphOffsets.slice(), morphWeights.vertexOffsetBytes())
		}
		deformable?.offsetBytes()?.let { encoder.writeToBuffer(softBodyOffsets.slice(), it) }
		encoder.submit()
	}

	internal fun bindDeformation(renderPass: RenderPass) {
		renderPass.setUniform("BoneTransforms", boneTransforms)
		renderPass.setUniform("MorphOffsets", morphOffsets)
		renderPass.setUniform("SoftBodyOffsets", softBodyOffsets)
	}

	internal fun close() {
		RenderSystem.assertOnRenderThread()
		try {
			physics?.close()
		} finally {
			try {
				deformable?.close()
			} finally {
				try {
					softBodyOffsets.close()
				} finally {
					try {
						morphOffsets.close()
					} finally {
						boneTransforms.close()
					}
				}
			}
		}
	}

	private fun createBoneTransforms(): GpuBuffer {
		RenderSystem.assertOnRenderThread()
		return RenderSystem.getDevice().createBuffer(
			{ "${asset.source.id} instance bone transforms" },
			GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER or GpuBuffer.USAGE_COPY_DST,
			pose.paletteBytes(),
		)
	}

	private fun createMorphOffsets(): GpuBuffer {
		RenderSystem.assertOnRenderThread()
		return RenderSystem.getDevice().createBuffer(
			{ "${asset.source.id} instance morph offsets" },
			GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER or GpuBuffer.USAGE_COPY_DST,
			morphWeights.vertexOffsetBytes(),
		)
	}

	private fun createSoftBodyOffsets(): GpuBuffer {
		RenderSystem.assertOnRenderThread()
		val size = asset.source.model.geometry.vertexCount * VECTOR_BYTES
		val bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
		repeat(size / Float.SIZE_BYTES) { bytes.putFloat(0f) }
		bytes.flip()
		return RenderSystem.getDevice().createBuffer(
			{ "${asset.source.id} instance soft body offsets" },
			GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER or GpuBuffer.USAGE_COPY_DST,
			bytes,
		)
	}

	private companion object {
		const val VECTOR_BYTES = 4 * Float.SIZE_BYTES
	}
}
