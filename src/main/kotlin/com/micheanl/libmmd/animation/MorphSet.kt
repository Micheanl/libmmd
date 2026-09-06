package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxFlipMorphOffset
import com.micheanl.libmmd.format.pmx.PmxBoneMorphOffset
import com.micheanl.libmmd.format.pmx.PmxGroupMorphOffset
import com.micheanl.libmmd.format.pmx.PmxMaterial
import com.micheanl.libmmd.format.pmx.PmxMaterialMorphOffset
import com.micheanl.libmmd.format.pmx.PmxModel
import com.micheanl.libmmd.format.pmx.PmxMorph
import com.micheanl.libmmd.format.pmx.PmxMorphType
import com.micheanl.libmmd.format.pmx.PmxUvMorphOffset
import com.micheanl.libmmd.format.pmx.PmxVertexMorphOffset
import org.joml.Quaternionf

class MorphSet private constructor(
	val vertexCount: Int,
	val boneCount: Int,
	internal val materials: List<PmxMaterial>,
	internal val targets: List<MorphTarget>,
) {
	private val targetIndices = buildMap {
		targets.forEachIndexed { index, target ->
			putIfAbsent(target.name, index)
			if (target.englishName.isNotEmpty()) putIfAbsent(target.englishName, index)
		}
	}

	val targetCount: Int
		get() = targets.size

	val materialCount: Int
		get() = materials.size

	fun indexOf(name: String): Int? = targetIndices[name]

	internal fun resolve(source: FloatArray, composed: FloatArray, destination: FloatArray) {
		source.copyInto(composed)
		targets.forEachIndexed { index, target ->
			when (target.type) {
				PmxMorphType.FLIP -> applyFlip(target, composed[index], composed)
				PmxMorphType.GROUP -> applyNestedFlips(target, composed[index], composed)
				else -> Unit
			}
		}
		destination.fill(0f)
		targets.forEachIndexed { index, target ->
			val weight = composed[index]
			when (target.type) {
				PmxMorphType.GROUP -> resolveGroup(target, weight, destination)
				PmxMorphType.FLIP -> Unit
				else -> destination[index] += weight
			}
		}
	}

	private fun applyFlip(target: MorphTarget, weight: Float, composed: FloatArray) {
		val selectedIndex = (((target.links.size + 1) * weight.coerceIn(0f, 1f)).toInt() - 1)
			.coerceIn(-1, target.links.lastIndex)
		val link = target.links.getOrNull(selectedIndex) ?: return
		composed[link.targetIndex] = link.influence
		val selected = targets[link.targetIndex]
		when (selected.type) {
			PmxMorphType.FLIP -> applyFlip(selected, link.influence, composed)
			PmxMorphType.GROUP -> applyNestedFlips(selected, link.influence, composed)
			else -> Unit
		}
	}

	private fun applyNestedFlips(target: MorphTarget, weight: Float, composed: FloatArray) {
		target.links.forEach { link ->
			val child = targets[link.targetIndex]
			when (child.type) {
				PmxMorphType.FLIP -> applyFlip(child, weight * link.influence, composed)
				PmxMorphType.GROUP -> applyNestedFlips(child, weight * link.influence, composed)
				else -> Unit
			}
		}
	}

	private fun resolveGroup(target: MorphTarget, weight: Float, destination: FloatArray) {
		if (weight == 0f) return
		target.links.forEach { link ->
			val child = targets[link.targetIndex]
			when (child.type) {
				PmxMorphType.GROUP -> resolveGroup(child, weight * link.influence, destination)
				PmxMorphType.FLIP -> Unit
				else -> destination[link.targetIndex] += weight * link.influence
			}
		}
	}

	companion object {
		fun from(model: PmxModel): MorphSet = from(model.geometry.vertexCount, model.materials, model.bones.size, model.morphs)

		fun from(vertexCount: Int, morphs: List<PmxMorph>): MorphSet = from(vertexCount, emptyList(), 0, morphs)

		fun from(vertexCount: Int, materials: List<PmxMaterial>, morphs: List<PmxMorph>): MorphSet =
			from(vertexCount, materials, 0, morphs)

		fun from(vertexCount: Int, materials: List<PmxMaterial>, boneCount: Int, morphs: List<PmxMorph>): MorphSet {
			require(vertexCount >= 0) { "vertexCount must be non-negative" }
			require(boneCount >= 0) { "boneCount must be non-negative" }
			val targets = morphs.map { morph -> createTarget(morph, vertexCount, materials.size, boneCount, morphs.size) }
			validateDependencies(targets)
			return MorphSet(vertexCount, boneCount, materials, targets)
		}

		private fun createTarget(
			morph: PmxMorph,
			vertexCount: Int,
			materialCount: Int,
			boneCount: Int,
			morphCount: Int,
		): MorphTarget {
			val links = when (morph.type) {
				PmxMorphType.GROUP -> morph.offsets.map { source ->
					require(source is PmxGroupMorphOffset) { "Morph '${morph.name}' contains an incompatible offset" }
					link(source.morphIndex, source.influence, morph.name, morphCount)
				}

				PmxMorphType.FLIP -> morph.offsets.map { source ->
					require(source is PmxFlipMorphOffset) { "Morph '${morph.name}' contains an incompatible offset" }
					link(source.morphIndex, source.influence, morph.name, morphCount)
				}

				else -> emptyList()
			}
			val vertexDeltas = if (morph.type == PmxMorphType.VERTEX) {
				morph.offsets.map { source ->
					require(source is PmxVertexMorphOffset) { "Morph '${morph.name}' contains an incompatible offset" }
					requireVertex(source.vertexIndex, vertexCount, morph.name)
					VertexDelta(source.vertexIndex, source.translation.x, source.translation.y, -source.translation.z)
				}
			} else {
				emptyList()
			}
			val uvDeltas = if (morph.type == PmxMorphType.UV) {
				morph.offsets.map { source ->
					require(source is PmxUvMorphOffset) { "Morph '${morph.name}' contains an incompatible offset" }
					requireVertex(source.vertexIndex, vertexCount, morph.name)
					UvDelta(source.vertexIndex, source.displacement.x, source.displacement.y)
				}
			} else {
				emptyList()
			}
			val boneDeltas = if (morph.type == PmxMorphType.BONE) {
				morph.offsets.map { source ->
					require(source is PmxBoneMorphOffset) { "Morph '${morph.name}' contains an incompatible offset" }
					require(source.boneIndex in 0 until boneCount) {
						"Morph '${morph.name}' bone ${source.boneIndex} is outside 0 until $boneCount"
					}
					val rotation = Quaternionf(-source.rotation.x, -source.rotation.y, source.rotation.z, source.rotation.w)
					if (!rotation.isFinite || rotation.lengthSquared() < 1.0e-12f) rotation.identity() else rotation.normalize()
					BoneDelta(
						boneIndex = source.boneIndex,
						x = source.translation.x,
						y = source.translation.y,
						z = -source.translation.z,
						rotation = rotation,
					)
				}
			} else {
				emptyList()
			}
			val materialDeltas = if (morph.type == PmxMorphType.MATERIAL) {
				morph.offsets.map { source ->
					require(source is PmxMaterialMorphOffset) { "Morph '${morph.name}' contains an incompatible offset" }
					require(source.materialIndex in -1 until materialCount) {
						"Morph '${morph.name}' material ${source.materialIndex} is outside -1 until $materialCount"
					}
					MaterialDelta(source)
				}
			} else {
				emptyList()
			}
			return MorphTarget(morph.name, morph.englishName, morph.type, links, vertexDeltas, uvDeltas, boneDeltas, materialDeltas)
		}

		private fun link(index: Int, influence: Float, name: String, morphCount: Int): MorphLink {
			require(index in 0 until morphCount) { "Morph '$name' target $index is outside 0 until $morphCount" }
			require(influence.isFinite()) { "Morph '$name' influence must be finite" }
			return MorphLink(index, influence)
		}

		private fun requireVertex(index: Int, vertexCount: Int, name: String) {
			require(index in 0 until vertexCount) { "Morph '$name' vertex $index is outside 0 until $vertexCount" }
		}

		private fun validateDependencies(targets: List<MorphTarget>) {
			val states = ByteArray(targets.size)
			fun visit(index: Int) {
				when (states[index].toInt()) {
					1 -> throw IllegalArgumentException("Morph dependency cycle contains '${targets[index].name}'")
					2 -> return
				}
				states[index] = 1
				targets[index].links.forEach { visit(it.targetIndex) }
				states[index] = 2
			}
			targets.indices.forEach(::visit)
		}
	}
}

internal class MorphTarget(
	val name: String,
	val englishName: String,
	val type: PmxMorphType,
	val links: List<MorphLink>,
	val vertexDeltas: List<VertexDelta>,
	val uvDeltas: List<UvDelta>,
	val boneDeltas: List<BoneDelta>,
	val materialDeltas: List<MaterialDelta>,
)

internal data class MorphLink(val targetIndex: Int, val influence: Float)

internal data class VertexDelta(val vertexIndex: Int, val x: Float, val y: Float, val z: Float)

internal data class UvDelta(val vertexIndex: Int, val x: Float, val y: Float)

internal data class BoneDelta(
	val boneIndex: Int,
	val x: Float,
	val y: Float,
	val z: Float,
	val rotation: Quaternionf,
)

internal class MaterialDelta(source: PmxMaterialMorphOffset) {
	val materialIndex = source.materialIndex
	val operation = source.operation
	val diffuse = source.diffuse
	val textureTint = source.textureTint
}
