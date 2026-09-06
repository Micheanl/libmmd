package com.micheanl.libmmd.render

import com.micheanl.libmmd.NativeRuntime
import com.micheanl.libmmd.asset.AssetLoadException
import com.micheanl.libmmd.asset.MeshData
import com.micheanl.libmmd.asset.MeshIndexType
import com.micheanl.libmmd.asset.ModelAsset
import com.micheanl.libmmd.asset.RenderBackend
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.IndexType
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer

@Environment(EnvType.CLIENT)
class MCRenderBackend(
	private val device: GpuDevice,
) : RenderBackend<GpuMesh> {
	constructor() : this(RenderSystem.getDevice())

	override fun upload(asset: ModelAsset): GpuMesh {
		return upload(
			asset.id,
			asset.mesh.vertexBytes(),
			asset.mesh.skinningBytes(),
			asset.mesh.indexBytes(),
			when (asset.mesh.indexType) {
				MeshIndexType.SHORT -> IndexType.SHORT
				MeshIndexType.INT -> IndexType.INT
			},
			asset.mesh.indexCount,
		)
	}

	fun upload(asset: ModelAsset, mesh: NativeRuntime.RenderMesh): GpuMesh {
		check(mesh.vertexCount() == asset.model.geometry.vertexCount)
		check(mesh.vertexStride() == MeshData.VERTEX_STRIDE)
		check(mesh.skinningStride() == MeshData.SKINNING_STRIDE)
		val indexType = when (mesh.indexStride()) {
			Short.SIZE_BYTES -> IndexType.SHORT
			Int.SIZE_BYTES -> IndexType.INT
			else -> error("Unsupported native index stride ${mesh.indexStride()}")
		}
		return upload(
			asset.id,
			mesh.vertices().asByteBuffer(),
			mesh.skinning().asByteBuffer(),
			mesh.indices().asByteBuffer(),
			indexType,
			mesh.indexCount(),
		)
	}

	private fun upload(
		id: Identifier,
		vertices: ByteBuffer,
		skinning: ByteBuffer,
		indices: ByteBuffer,
		indexType: IndexType,
		indexCount: Int,
	): GpuMesh {
		RenderSystem.assertOnRenderThread()
		if (indexCount == 0) throw AssetLoadException("Model $id has no triangles to upload")
		check(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.vertexSize == MeshData.VERTEX_STRIDE)
		check(ModelVertexFormats.skinning.vertexSize == MeshData.SKINNING_STRIDE)

		val buffers = ArrayList<GpuBuffer>(3)
		try {
			val vertexBuffer = device.createBuffer(
				{ "$id vertices" },
				GpuBuffer.USAGE_VERTEX,
				vertices,
			).also(buffers::add)
			val skinningBuffer = device.createBuffer(
				{ "$id skinning" },
				GpuBuffer.USAGE_VERTEX,
				skinning,
			).also(buffers::add)
			val indexBuffer = device.createBuffer(
				{ "$id indices" },
				GpuBuffer.USAGE_INDEX,
				indices,
			).also(buffers::add)
			return GpuMesh(
				vertexBuffer = vertexBuffer,
				skinningBuffer = skinningBuffer,
				indexBuffer = indexBuffer,
				indexType = indexType,
				indexCount = indexCount,
			)
		} catch (exception: Throwable) {
			buffers.asReversed().forEach(GpuBuffer::close)
			throw exception
		}
	}
}
