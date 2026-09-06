package com.micheanl.libmmd.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

@Environment(EnvType.CLIENT)
class GpuMesh internal constructor(
	private val vertexBuffer: GpuBuffer,
	private val skinningBuffer: GpuBuffer,
	private val indexBuffer: GpuBuffer,
	val indexType: IndexType,
	val indexCount: Int,
) : AutoCloseable {
	val vertexFormat: VertexFormat
		get() = DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL

	fun draw(pass: RenderPass, firstIndex: Int = 0, indexCount: Int = this.indexCount - firstIndex) {
		require(firstIndex in 0..this.indexCount) { "firstIndex exceeds mesh indices" }
		require(indexCount in 0..this.indexCount - firstIndex) { "Draw range exceeds mesh indices" }
		RenderSystem.assertOnRenderThread()
		pass.setVertexBuffer(0, vertexBuffer.slice())
		pass.setVertexBuffer(1, skinningBuffer.slice())
		pass.setIndexBuffer(indexBuffer, indexType)
		pass.drawIndexed(indexCount, 1, firstIndex, 0, 0)
	}

	override fun close() {
		RenderSystem.assertOnRenderThread()
		try {
			indexBuffer.close()
		} finally {
			try {
				skinningBuffer.close()
			} finally {
				vertexBuffer.close()
			}
		}
	}
}
