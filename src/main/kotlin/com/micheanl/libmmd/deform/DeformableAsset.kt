package com.micheanl.libmmd.deform

import org.joml.Vector3f

enum class DeformableRegionType {
	SKIRT,
	HAIR,
}

data class DeformableParticle(
	val vertexIndex: Int,
	val restPosition: Vector3f,
	val inverseMass: Float,
	val region: DeformableRegionType,
)

data class DistanceConstraint(
	val first: Int,
	val second: Int,
	val restLength: Float,
)

data class BendingConstraint(
	val first: Int,
	val second: Int,
	val restLength: Float,
)

data class VolumeConstraint(
	val triangles: IntArray,
	val restVolume: Float,
)

data class DeformableRegion(
	val type: DeformableRegionType,
	val particleIndices: IntArray,
	val triangleIndices: IntArray,
	val closed: Boolean,
)

class DeformableAsset internal constructor(
	val vertexCount: Int,
	val particles: List<DeformableParticle>,
	val regions: List<DeformableRegion>,
	val distanceConstraints: List<DistanceConstraint>,
	val bendingConstraints: List<BendingConstraint>,
	val volumeConstraints: List<VolumeConstraint>,
) {
	val isEmpty: Boolean
		get() = particles.isEmpty()

	val simulatedVertexCount: Int
		get() = particles.count { it.inverseMass > 0f }

	fun withinParticleBudget(maximumParticles: Int): DeformableAsset {
		require(maximumParticles > 0)
		if (particles.size <= maximumParticles) return this
		val selected = ArrayList<Int>()
		var remaining = maximumParticles
		val quotas = DeformableRegionType.entries.associateWith { maximumParticles / DeformableRegionType.entries.size }
		DeformableRegionType.entries.forEach { type ->
			var budget = quotas.getValue(type)
			regions.indices
				.filter { regions[it].type == type }
				.sortedByDescending { regions[it].particleIndices.size }
				.forEach { regionIndex ->
					val size = regions[regionIndex].particleIndices.size
					if (size <= budget && size <= remaining) {
						selected += regionIndex
						budget -= size
						remaining -= size
					}
				}
		}
		regions.indices
			.filterNot(selected::contains)
			.sortedByDescending { regions[it].particleIndices.size }
			.forEach { regionIndex ->
				val size = regions[regionIndex].particleIndices.size
				if (size <= remaining) {
					selected += regionIndex
					remaining -= size
				}
			}
		return selectRegions(selected)
	}

	fun selectRegions(regionIndices: Collection<Int>): DeformableAsset {
		require(regionIndices.all { it in regions.indices })
		val selectedRegions = regionIndices.distinct().map(regions::get)
		val selectedParticles = selectedRegions.flatMap { it.particleIndices.asIterable() }.distinct().sorted()
		val remap = selectedParticles.withIndex().associate { (new, old) -> old to new }
		fun mapped(index: Int): Int = checkNotNull(remap[index])
		return DeformableAsset(
			vertexCount = vertexCount,
			particles = selectedParticles.map(particles::get),
			regions = selectedRegions.map { region ->
				DeformableRegion(
					region.type,
					region.particleIndices.map(::mapped).toIntArray(),
					region.triangleIndices.map(::mapped).toIntArray(),
					region.closed,
				)
			},
			distanceConstraints = distanceConstraints.mapNotNull { constraint ->
				val first = remap[constraint.first] ?: return@mapNotNull null
				val second = remap[constraint.second] ?: return@mapNotNull null
				DistanceConstraint(first, second, constraint.restLength)
			},
			bendingConstraints = bendingConstraints.mapNotNull { constraint ->
				val first = remap[constraint.first] ?: return@mapNotNull null
				val second = remap[constraint.second] ?: return@mapNotNull null
				BendingConstraint(first, second, constraint.restLength)
			},
			volumeConstraints = volumeConstraints.mapNotNull { constraint ->
				if (constraint.triangles.any { it !in remap }) return@mapNotNull null
				VolumeConstraint(constraint.triangles.map(::mapped).toIntArray(), constraint.restVolume)
			},
		)
	}

	companion object {
		fun empty(vertexCount: Int): DeformableAsset = DeformableAsset(
			vertexCount,
			emptyList(),
			emptyList(),
			emptyList(),
			emptyList(),
			emptyList(),
		)
	}
}
