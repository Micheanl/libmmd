package com.micheanl.libmmd.render

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.vertex.VertexFormat

internal object ModelVertexFormats {
	val skinning: VertexFormat = VertexFormat.builder(0)
		.addAttribute("BoneIndices", GpuFormat.RGBA32_SINT)
		.addAttribute("BoneWeights", GpuFormat.RGBA32_FLOAT)
		.build()
}
