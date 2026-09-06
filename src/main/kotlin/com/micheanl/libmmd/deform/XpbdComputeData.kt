package com.micheanl.libmmd.deform

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ComputeBatch(val first: Int, val count: Int)

class XpbdComputeData private constructor(
	private val particles: ByteBuffer,
	private val distanceConstraints: ByteBuffer,
	private val bendingConstraints: ByteBuffer,
	private val volumeConstraints: ByteBuffer,
	private val volumeTriangleIndices: ByteBuffer,
	private val volumeParticleIndices: ByteBuffer,
	private val particleMetadata: ByteBuffer,
	private val neighborIndices: ByteBuffer,
	val distanceBatches: List<ComputeBatch>,
	val bendingBatches: List<ComputeBatch>,
	val particleCount: Int,
) {
	fun particleBytes(): ByteBuffer = particles.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun distanceConstraintBytes(): ByteBuffer = distanceConstraints.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun bendingConstraintBytes(): ByteBuffer = bendingConstraints.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun volumeConstraintBytes(): ByteBuffer = volumeConstraints.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun volumeTriangleIndexBytes(): ByteBuffer = volumeTriangleIndices.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun volumeParticleIndexBytes(): ByteBuffer = volumeParticleIndices.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun particleMetadataBytes(): ByteBuffer = particleMetadata.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	fun neighborIndexBytes(): ByteBuffer = neighborIndices.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

	companion object {
		const val PARTICLE_STRIDE = 4 * Float.SIZE_BYTES
		const val CONSTRAINT_STRIDE = 4 * Int.SIZE_BYTES
		const val PARTICLE_METADATA_STRIDE = 4 * Int.SIZE_BYTES
		const val VOLUME_CONSTRAINT_STRIDE = 8 * Int.SIZE_BYTES

		fun from(asset: DeformableAsset, configuration: XpbdConfiguration = XpbdConfiguration()): XpbdComputeData {
			val particleBytes = ByteBuffer.allocateDirect(asset.particles.size * PARTICLE_STRIDE).order(ByteOrder.nativeOrder())
			asset.particles.forEach { particle ->
				particleBytes.putFloat(particle.restPosition.x)
				particleBytes.putFloat(particle.restPosition.y)
				particleBytes.putFloat(particle.restPosition.z)
				particleBytes.putFloat(particle.inverseMass)
			}
			particleBytes.flip()
			val distanceBatches = batch(asset.distanceConstraints) { it.first to it.second }
			val orderedDistance = distanceBatches.flatMap { batch -> batch.constraints }
			val distanceBytes = constraints(
				orderedDistance.map { PackedConstraint(it.first, it.second, it.restLength, configuration.distanceCompliance) },
			)
			val bendingBatches = batch(asset.bendingConstraints) { it.first to it.second }
			val orderedBending = bendingBatches.flatMap { batch -> batch.constraints }
			val bendingBytes = constraints(
				orderedBending.map { PackedConstraint(it.first, it.second, it.restLength, configuration.bendingCompliance) },
			)
			val volumeBytes = ByteBuffer.allocateDirect(
				maxOf(asset.volumeConstraints.size * VOLUME_CONSTRAINT_STRIDE, Int.SIZE_BYTES),
			).order(ByteOrder.nativeOrder())
			val volumeTriangleCount = asset.volumeConstraints.sumOf { it.triangles.size }
			val volumeParticleLists = asset.volumeConstraints.map { it.triangles.distinct() }
			val volumeParticleCount = volumeParticleLists.sumOf(List<Int>::size)
			val volumeTriangleBytes = ByteBuffer.allocateDirect(maxOf(volumeTriangleCount, 1) * Int.SIZE_BYTES)
				.order(ByteOrder.nativeOrder())
			val volumeParticleBytes = ByteBuffer.allocateDirect(maxOf(volumeParticleCount, 1) * Int.SIZE_BYTES)
				.order(ByteOrder.nativeOrder())
			var triangleOffset = 0
			var particleOffset = 0
			asset.volumeConstraints.forEachIndexed { index, constraint ->
				val volumeParticles = volumeParticleLists[index]
				volumeBytes.putInt(triangleOffset)
					.putInt(constraint.triangles.size)
					.putInt(particleOffset)
					.putInt(volumeParticles.size)
					.putFloat(constraint.restVolume)
					.putFloat(configuration.volumeCompliance)
					.putFloat(0f)
					.putFloat(0f)
				constraint.triangles.forEach(volumeTriangleBytes::putInt)
				volumeParticles.forEach(volumeParticleBytes::putInt)
				triangleOffset += constraint.triangles.size
				particleOffset += volumeParticles.size
			}
			if (asset.volumeConstraints.isEmpty()) volumeBytes.putInt(0)
			if (volumeTriangleCount == 0) volumeTriangleBytes.putInt(0)
			if (volumeParticleCount == 0) volumeParticleBytes.putInt(0)
			volumeBytes.flip()
			volumeTriangleBytes.flip()
			volumeParticleBytes.flip()
			val adjacency = Array(asset.particles.size) { LinkedHashSet<Int>() }
			asset.distanceConstraints.forEach { constraint ->
				adjacency[constraint.first] += constraint.second
				adjacency[constraint.second] += constraint.first
			}
			val metadataBytes = ByteBuffer.allocateDirect(
				asset.particles.size * PARTICLE_METADATA_STRIDE,
			).order(ByteOrder.nativeOrder())
			val neighborCount = adjacency.sumOf(Set<Int>::size)
			val neighborBytes = ByteBuffer.allocateDirect(maxOf(neighborCount, 1) * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
			var neighborOffset = 0
			asset.particles.forEachIndexed { index, particle ->
				metadataBytes.putInt(particle.vertexIndex)
				metadataBytes.putInt(particle.region.ordinal)
				metadataBytes.putInt(neighborOffset)
				metadataBytes.putInt(adjacency[index].size)
				adjacency[index].forEach(neighborBytes::putInt)
				neighborOffset += adjacency[index].size
			}
			metadataBytes.flip()
			if (neighborCount == 0) neighborBytes.putInt(0)
			neighborBytes.flip()
			return XpbdComputeData(
				particles = particleBytes,
				distanceConstraints = distanceBytes,
				bendingConstraints = bendingBytes,
				volumeConstraints = volumeBytes,
				volumeTriangleIndices = volumeTriangleBytes,
				volumeParticleIndices = volumeParticleBytes,
				particleMetadata = metadataBytes,
				neighborIndices = neighborBytes,
				distanceBatches = ranges(distanceBatches),
				bendingBatches = ranges(bendingBatches),
				particleCount = asset.particles.size,
			)
		}

		private fun constraints(source: List<PackedConstraint>): ByteBuffer {
			val result = ByteBuffer.allocateDirect(source.size * CONSTRAINT_STRIDE).order(ByteOrder.nativeOrder())
			source.forEach { constraint ->
				result.putInt(constraint.first)
				result.putInt(constraint.second)
				result.putFloat(constraint.restLength)
				result.putFloat(constraint.compliance)
			}
			result.flip()
			return result
		}

		private fun <T> batch(source: List<T>, endpoints: (T) -> Pair<Int, Int>): List<Batch<T>> {
			val batches = ArrayList<Batch<T>>()
			source.forEach { constraint ->
				val pair = endpoints(constraint)
				val batch = batches.firstOrNull { pair.first !in it.particles && pair.second !in it.particles }
					?: Batch<T>().also(batches::add)
				batch.constraints += constraint
				batch.particles += pair.first
				batch.particles += pair.second
			}
			return batches
		}

		private fun <T> ranges(batches: List<Batch<T>>): List<ComputeBatch> {
			var first = 0
			return batches.map { batch ->
				ComputeBatch(first, batch.constraints.size).also { first += batch.constraints.size }
			}
		}

		private data class Batch<T>(
			val constraints: MutableList<T> = ArrayList(),
			val particles: MutableSet<Int> = HashSet(),
		)

		private data class PackedConstraint(
			val first: Int,
			val second: Int,
			val restLength: Float,
			val compliance: Float,
		)
	}
}
