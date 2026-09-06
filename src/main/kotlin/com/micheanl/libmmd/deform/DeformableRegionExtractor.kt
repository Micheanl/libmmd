package com.micheanl.libmmd.deform

import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.micheanl.libmmd.format.pmx.PmxMaterial
import com.micheanl.libmmd.format.pmx.PmxModel
import com.micheanl.libmmd.format.pmx.PmxSkinning
import org.joml.Vector3f
import kotlin.math.abs

data class DeformableExtractionConfiguration(
	val minimumBoneWeight: Float = 0.2f,
	val anchorBandRatio: Float = 0.08f,
	val minimumRegionTriangles: Int = 2,
) {
	init {
		require(minimumBoneWeight.isFinite() && minimumBoneWeight in 0f..1f)
		require(anchorBandRatio.isFinite() && anchorBandRatio in 0.01f..0.5f)
		require(minimumRegionTriangles > 0)
	}
}

object DeformableRegionExtractor {
	fun extract(
		model: PmxModel,
		configuration: DeformableExtractionConfiguration = DeformableExtractionConfiguration(),
	): DeformableAsset = extract(
		geometry = model.geometry,
		materials = model.materials,
		boneNames = model.bones.map { "${it.name} ${it.englishName}" },
		configuration = configuration,
	)

	internal fun extract(
		geometry: PmxGeometry,
		materials: List<PmxMaterial>,
		boneNames: List<String>,
		configuration: DeformableExtractionConfiguration,
	): DeformableAsset {
		if (geometry.vertexCount == 0 || geometry.triangleCount == 0) return DeformableAsset.empty(geometry.vertexCount)
		val boneRoles = boneNames.map(::classify)
		val vertexRoles = Array<DeformableRegionType?>(geometry.vertexCount) { vertex ->
			weightedRole(geometry.skinning, vertex, boneRoles, configuration.minimumBoneWeight)
		}
		val triangleRoles = arrayOfNulls<DeformableRegionType>(geometry.triangleCount)
		materials.forEach { material ->
			val materialRole = classify("${material.name} ${material.englishName} ${material.metadata}")
			val start = material.firstIndex.coerceIn(0, geometry.triangleIndices.size)
			val end = (material.firstIndex + material.indexCount).coerceIn(start, geometry.triangleIndices.size)
			for (index in start until end step 3) {
				if (index + 2 >= end) break
				val triangle = index / 3
				triangleRoles[triangle] = materialRole ?: triangleRole(
					geometry.triangleIndices,
					index,
					vertexRoles,
				)
			}
		}
		for (triangle in triangleRoles.indices) {
			if (triangleRoles[triangle] == null) {
				triangleRoles[triangle] = triangleRole(
					geometry.triangleIndices,
					triangle * 3,
					vertexRoles,
				)
			}
		}

		val particles = ArrayList<DeformableParticle>()
		val regions = ArrayList<DeformableRegion>()
		val distances = ArrayList<DistanceConstraint>()
		val bending = ArrayList<BendingConstraint>()
		val volumes = ArrayList<VolumeConstraint>()
		val particleByVertex = HashMap<Int, Int>()
		DeformableRegionType.entries.forEach { role ->
			val triangles = triangleRoles.indices.filter { triangleRoles[it] == role }
			connectedComponents(triangles, geometry.triangleIndices).forEach { component ->
				if (component.size < configuration.minimumRegionTriangles) return@forEach
				val vertices = component.flatMap { triangle ->
					val offset = triangle * 3
					listOf(
						geometry.triangleIndices[offset],
						geometry.triangleIndices[offset + 1],
						geometry.triangleIndices[offset + 2],
					)
				}.distinct()
				val ys = vertices.map { geometry.positions[it * 3 + 1] }
				val maximumY = ys.max()
				val anchorHeight = (maximumY - ys.min()) * configuration.anchorBandRatio
				val localParticles = vertices.map { vertex ->
					particleByVertex.getOrPut(vertex) {
						val offset = vertex * 3
						particles += DeformableParticle(
							vertexIndex = vertex,
							restPosition = Vector3f(
								geometry.positions[offset],
								geometry.positions[offset + 1],
								-geometry.positions[offset + 2],
							),
							inverseMass = if (maximumY - geometry.positions[offset + 1] <= anchorHeight) 0f else 1f,
							region = role,
						)
						particles.lastIndex
					}
				}
				val triangleIndices = IntArray(component.size * 3)
				component.forEachIndexed { componentIndex, triangle ->
					val offset = triangle * 3
					repeat(3) { corner ->
						triangleIndices[componentIndex * 3 + corner] = checkNotNull(
							particleByVertex[geometry.triangleIndices[offset + corner]],
						)
					}
				}
				val edgeOpposites = LinkedHashMap<Edge, MutableList<Int>>()
				for (offset in triangleIndices.indices step 3) {
					val a = triangleIndices[offset]
					val b = triangleIndices[offset + 1]
					val c = triangleIndices[offset + 2]
					addEdge(edgeOpposites, a, b, c)
					addEdge(edgeOpposites, b, c, a)
					addEdge(edgeOpposites, c, a, b)
				}
				edgeOpposites.forEach { (edge, opposite) ->
					distances += DistanceConstraint(edge.first, edge.second, restDistance(particles, edge.first, edge.second))
					if (opposite.size == 2 && opposite[0] != opposite[1]) {
						bending += BendingConstraint(
							opposite[0],
							opposite[1],
							restDistance(particles, opposite[0], opposite[1]),
						)
					}
				}
				val closed = edgeOpposites.values.all { it.size == 2 }
				regions += DeformableRegion(role, localParticles.toIntArray(), triangleIndices, closed)
				if (closed) {
					val restVolume = signedVolume(triangleIndices) { particles[it].restPosition }
					if (abs(restVolume) > MINIMUM_VOLUME) volumes += VolumeConstraint(triangleIndices, restVolume)
				}
			}
		}
		return DeformableAsset(geometry.vertexCount, particles, regions, distances, bending, volumes)
	}

	private fun weightedRole(
		skinning: PmxSkinning,
		vertex: Int,
		boneRoles: List<DeformableRegionType?>,
		minimumWeight: Float,
	): DeformableRegionType? {
		val weights = FloatArray(DeformableRegionType.entries.size)
		repeat(PmxSkinning.MAX_INFLUENCES) { influence ->
			val bone = skinning.boneIndex(vertex, influence)
			val role = boneRoles.getOrNull(bone) ?: return@repeat
			weights[role.ordinal] += skinning.boneWeight(vertex, influence).coerceAtLeast(0f)
		}
		val role = DeformableRegionType.entries.maxBy { weights[it.ordinal] }
		return role.takeIf { weights[it.ordinal] >= minimumWeight }
	}

	private fun triangleRole(
		indices: IntArray,
		offset: Int,
		vertexRoles: Array<DeformableRegionType?>,
	): DeformableRegionType? = DeformableRegionType.entries.firstOrNull { role ->
		(0..2).count { vertexRoles[indices[offset + it]] == role } >= 2
	}

	private fun connectedComponents(triangles: List<Int>, indices: IntArray): List<List<Int>> {
		val byVertex = HashMap<Int, MutableList<Int>>()
		triangles.forEach { triangle ->
			repeat(3) { corner -> byVertex.getOrPut(indices[triangle * 3 + corner], ::ArrayList) += triangle }
		}
		val remaining = triangles.toMutableSet()
		val result = ArrayList<List<Int>>()
		while (remaining.isNotEmpty()) {
			val queue = ArrayDeque<Int>()
			val component = ArrayList<Int>()
			queue += remaining.first()
			while (queue.isNotEmpty()) {
				val triangle = queue.removeFirst()
				if (!remaining.remove(triangle)) continue
				component += triangle
				repeat(3) { corner ->
					byVertex[indices[triangle * 3 + corner]].orEmpty().forEach(queue::addLast)
				}
			}
			result += component
		}
		return result
	}

	private fun addEdge(target: MutableMap<Edge, MutableList<Int>>, first: Int, second: Int, opposite: Int) {
		target.getOrPut(Edge.of(first, second), ::ArrayList) += opposite
	}

	private fun restDistance(particles: List<DeformableParticle>, first: Int, second: Int): Float =
		particles[first].restPosition.distance(particles[second].restPosition)

	private fun signedVolume(triangles: IntArray, position: (Int) -> Vector3f): Float {
		var volume = 0f
		val cross = Vector3f()
		for (offset in triangles.indices step 3) {
			val a = position(triangles[offset])
			val b = position(triangles[offset + 1])
			val c = position(triangles[offset + 2])
			b.cross(c, cross)
			volume += a.dot(cross) / 6f
		}
		return volume
	}

	private fun classify(value: String): DeformableRegionType? {
		val identity = value.lowercase()
		return when {
			SKIRT_NAMES.any(identity::contains) -> DeformableRegionType.SKIRT
			HAIR_NAMES.any(identity::contains) -> DeformableRegionType.HAIR
			else -> null
		}
	}

	private data class Edge private constructor(val first: Int, val second: Int) {
		companion object {
			fun of(first: Int, second: Int): Edge = Edge(minOf(first, second), maxOf(first, second))
		}
	}

	private const val MINIMUM_VOLUME = 1.0e-8f
	private val SKIRT_NAMES = listOf("skirt", "dress", "qunzi", "スカート", "裙")
	private val HAIR_NAMES = listOf("hair", "bang", "twin tail", "ponytail", "髪", "发", "辮", "辫")
}
