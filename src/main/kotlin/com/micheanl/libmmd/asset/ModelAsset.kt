package com.micheanl.libmmd.asset

import com.micheanl.libmmd.animation.MorphSet
import com.micheanl.libmmd.animation.Skeleton
import com.micheanl.libmmd.format.pmx.PmxModel
import net.minecraft.resources.Identifier

data class ModelAsset(
	val id: Identifier,
	val model: PmxModel,
	val textureIds: List<Identifier>,
	val mesh: MeshData,
	val skeleton: Skeleton,
	val morphs: MorphSet,
)
