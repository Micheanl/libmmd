package com.micheanl.libmmd.deform

import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.floor
import kotlin.math.sqrt

data class XpbdConfiguration(
	val fixedStepSeconds: Float = 1f / 120f,
	val maximumFrameSeconds: Float = 0.1f,
	val solverIterations: Int = 8,
	val distanceCompliance: Float = 1.0e-7f,
	val bendingCompliance: Float = 2.0e-5f,
	val volumeCompliance: Float = 1.0e-7f,
	val damping: Float = 0.02f,
	val maximumStretchRatio: Float = 1.08f,
	val particleRadius: Float = 0.025f,
	val selfCollision: Boolean = true,
	val friction: Float = 0.25f,
	val gravity: Vector3f = Vector3f(0f, -9.81f, 0f),
) {
	init {
		require(fixedStepSeconds.isFinite() && fixedStepSeconds > 0f)
		require(maximumFrameSeconds.isFinite() && maximumFrameSeconds >= fixedStepSeconds)
		require(solverIterations in 1..64)
		listOf(distanceCompliance, bendingCompliance, volumeCompliance).forEach {
			require(it.isFinite() && it >= 0f)
		}
		require(damping.isFinite() && damping in 0f..1f)
		require(maximumStretchRatio.isFinite() && maximumStretchRatio >= 1f)
		require(particleRadius.isFinite() && particleRadius > 0f)
		require(friction.isFinite() && friction in 0f..1f)
		require(gravity.isFinite)
	}
}

data class AnimatedCapsule(
	val previousStart: Vector3f,
	val previousEnd: Vector3f,
	val start: Vector3f,
	val end: Vector3f,
	val radius: Float,
	val friction: Float = 0.3f,
) {
	init {
		require(previousStart.isFinite && previousEnd.isFinite && start.isFinite && end.isFinite)
		require(radius.isFinite() && radius > 0f)
		require(friction.isFinite() && friction in 0f..1f)
	}
}

class XpbdSolver(
	val asset: DeformableAsset,
	val configuration: XpbdConfiguration = XpbdConfiguration(),
) {
	private val positions = Array(asset.particles.size) { Vector3f(asset.particles[it].restPosition) }
	private val previousPositions = Array(asset.particles.size) { Vector3f(positions[it]) }
	private val previousStepPositions = Array(asset.particles.size) { Vector3f(positions[it]) }
	private val anchors = Array(asset.particles.size) { Vector3f(positions[it]) }
	private val distanceLambdas = FloatArray(asset.distanceConstraints.size)
	private val bendingLambdas = FloatArray(asset.bendingConstraints.size)
	private val volumeLambdas = FloatArray(asset.volumeConstraints.size)
	private val volumeGradients = Array(asset.particles.size) { Vector3f() }
	private val spatialHash = SpatialHash(configuration.particleRadius * 2f)
	private val connectedPairs = asset.distanceConstraints.mapTo(HashSet()) { pairKey(it.first, it.second) }
	private val firstScratch = Vector3f()
	private val secondScratch = Vector3f()
	private val thirdScratch = Vector3f()
	private val fourthScratch = Vector3f()
	private var accumulator = 0f

	val interpolationAlpha: Float
		get() = (accumulator / configuration.fixedStepSeconds).coerceIn(0f, 1f)

	fun reset(animatedPositions: Array<out Vector3fc?> = emptyArray()) {
		require(animatedPositions.isEmpty() || animatedPositions.size == positions.size)
		positions.indices.forEach { index ->
			val target = animatedPositions.getOrNull(index) ?: asset.particles[index].restPosition
			positions[index].set(target)
			previousPositions[index].set(target)
			previousStepPositions[index].set(target)
			anchors[index].set(target)
		}
		accumulator = 0f
	}

	fun advance(
		frameSeconds: Float,
		animatedPositions: Array<out Vector3fc?>,
		capsules: List<AnimatedCapsule> = emptyList(),
		inertialAcceleration: Vector3fc = ZERO,
	): Int {
		require(frameSeconds.isFinite() && frameSeconds >= 0f)
		require(animatedPositions.size == positions.size)
		require(inertialAcceleration.isFinite)
		animatedPositions.forEachIndexed { index, target -> if (target != null) anchors[index].set(target) }
		accumulator = (accumulator + frameSeconds).coerceAtMost(configuration.maximumFrameSeconds)
		var steps = 0
		while (accumulator >= configuration.fixedStepSeconds) {
			step(capsules, inertialAcceleration)
			accumulator -= configuration.fixedStepSeconds
			steps++
		}
		return steps
	}

	fun position(particleIndex: Int, destination: Vector3f = Vector3f()): Vector3f =
		destination.set(positions[particleIndex])

	fun interpolatedPosition(particleIndex: Int, destination: Vector3f = Vector3f()): Vector3f =
		destination.set(previousStepPositions[particleIndex]).lerp(positions[particleIndex], interpolationAlpha)

	fun allFinite(): Boolean = positions.all(Vector3f::isFinite)

	private fun step(capsules: List<AnimatedCapsule>, inertialAcceleration: Vector3fc) {
		val delta = configuration.fixedStepSeconds
		val deltaSquared = delta * delta
		positions.indices.forEach { index ->
			previousStepPositions[index].set(positions[index])
			if (asset.particles[index].inverseMass == 0f) {
				positions[index].set(anchors[index])
				previousPositions[index].set(anchors[index])
			} else {
				firstScratch.set(positions[index]).sub(previousPositions[index]).mul(1f - configuration.damping)
				previousPositions[index].set(positions[index])
				positions[index].add(firstScratch)
					.add(
						(configuration.gravity.x + inertialAcceleration.x()) * deltaSquared,
						(configuration.gravity.y + inertialAcceleration.y()) * deltaSquared,
						(configuration.gravity.z + inertialAcceleration.z()) * deltaSquared,
					)
			}
		}
		distanceLambdas.fill(0f)
		bendingLambdas.fill(0f)
		volumeLambdas.fill(0f)
		repeat(configuration.solverIterations) {
			solveDistanceConstraints()
			solveBendingConstraints()
			solveVolumeConstraints()
			solveMaximumStretch()
			solveCapsules(capsules)
			if (configuration.selfCollision) solveSelfCollision()
			positions.indices.forEach { index ->
				if (asset.particles[index].inverseMass == 0f) positions[index].set(anchors[index])
			}
		}
	}

	private fun solveDistanceConstraints() {
		asset.distanceConstraints.forEachIndexed { index, constraint ->
			distanceLambdas[index] = solveDistance(
				constraint.first,
				constraint.second,
				constraint.restLength,
				configuration.distanceCompliance,
				distanceLambdas[index],
			)
		}
	}

	private fun solveBendingConstraints() {
		asset.bendingConstraints.forEachIndexed { index, constraint ->
			bendingLambdas[index] = solveDistance(
				constraint.first,
				constraint.second,
				constraint.restLength,
				configuration.bendingCompliance,
				bendingLambdas[index],
			)
		}
	}

	private fun solveDistance(
		first: Int,
		second: Int,
		restLength: Float,
		compliance: Float,
		lambda: Float,
	): Float {
		val firstWeight = asset.particles[first].inverseMass
		val secondWeight = asset.particles[second].inverseMass
		val weight = firstWeight + secondWeight
		if (weight <= 0f) return lambda
		firstScratch.set(positions[first]).sub(positions[second])
		val length = firstScratch.length()
		if (length <= EPSILON) return lambda
		firstScratch.div(length)
		val alpha = compliance / (configuration.fixedStepSeconds * configuration.fixedStepSeconds)
		val deltaLambda = (-(length - restLength) - alpha * lambda) / (weight + alpha)
		positions[first].fma(firstWeight * deltaLambda, firstScratch)
		positions[second].fma(-secondWeight * deltaLambda, firstScratch)
		return lambda + deltaLambda
	}

	private fun solveMaximumStretch() {
		asset.distanceConstraints.forEach { constraint ->
			val maximum = constraint.restLength * configuration.maximumStretchRatio
			firstScratch.set(positions[constraint.first]).sub(positions[constraint.second])
			val length = firstScratch.length()
			if (length <= maximum || length <= EPSILON) return@forEach
			val firstWeight = asset.particles[constraint.first].inverseMass
			val secondWeight = asset.particles[constraint.second].inverseMass
			val weight = firstWeight + secondWeight
			if (weight <= 0f) return@forEach
			firstScratch.mul((length - maximum) / (length * weight))
			positions[constraint.first].fma(-firstWeight, firstScratch)
			positions[constraint.second].fma(secondWeight, firstScratch)
		}
	}

	private fun solveVolumeConstraints() {
		asset.volumeConstraints.forEachIndexed { index, constraint ->
			volumeGradients.forEach(Vector3f::zero)
			var volume = 0f
			for (offset in constraint.triangles.indices step 3) {
				val first = constraint.triangles[offset]
				val second = constraint.triangles[offset + 1]
				val third = constraint.triangles[offset + 2]
				val a = positions[first]
				val b = positions[second]
				val c = positions[third]
				b.cross(c, firstScratch)
				volume += a.dot(firstScratch) / 6f
				volumeGradients[first].add(firstScratch.div(6f))
				c.cross(a, secondScratch)
				volumeGradients[second].add(secondScratch.div(6f))
				a.cross(b, thirdScratch)
				volumeGradients[third].add(thirdScratch.div(6f))
			}
			var denominator = 0f
			constraint.triangles.distinct().forEach { particle ->
				denominator += asset.particles[particle].inverseMass * volumeGradients[particle].lengthSquared()
			}
			val alpha = configuration.volumeCompliance /
				(configuration.fixedStepSeconds * configuration.fixedStepSeconds)
			if (denominator + alpha <= EPSILON) return@forEachIndexed
			val deltaLambda = (-(volume - constraint.restVolume) - alpha * volumeLambdas[index]) / (denominator + alpha)
			constraint.triangles.distinct().forEach { particle ->
				positions[particle].fma(asset.particles[particle].inverseMass * deltaLambda, volumeGradients[particle])
			}
			volumeLambdas[index] += deltaLambda
		}
	}

	private fun solveCapsules(capsules: List<AnimatedCapsule>) {
		positions.indices.forEach { particle ->
			if (asset.particles[particle].inverseMass == 0f) return@forEach
			capsules.forEach { capsule ->
				for (sample in CAPSULE_SAMPLES) {
					firstScratch.set(capsule.previousStart).lerp(capsule.start, sample)
					secondScratch.set(capsule.previousEnd).lerp(capsule.end, sample)
					closestPoints(
						previousPositions[particle],
						positions[particle],
						firstScratch,
						secondScratch,
						thirdScratch,
						fourthScratch,
					)
					thirdScratch.sub(fourthScratch)
					val distanceSquared = thirdScratch.lengthSquared()
					val radius = capsule.radius + configuration.particleRadius
					if (distanceSquared >= radius * radius) continue
					val distance = sqrt(distanceSquared.coerceAtLeast(EPSILON))
					val normal = if (distanceSquared <= EPSILON) {
						thirdScratch.set(previousPositions[particle]).sub(fourthScratch)
						if (thirdScratch.lengthSquared() <= EPSILON) thirdScratch.set(1f, 0f, 0f) else thirdScratch.normalize()
					} else {
						thirdScratch.div(distance)
					}
					positions[particle].set(fourthScratch).fma(radius, normal)
					applyFriction(particle, normal, maxOf(configuration.friction, capsule.friction))
				}
			}
		}
	}

	private fun solveSelfCollision() {
		spatialHash.rebuild(positions)
		val minimumDistance = configuration.particleRadius * 2f
		positions.indices.forEach { first ->
			spatialHash.neighbors(positions[first]).forEach { second ->
				if (second <= first || pairKey(first, second) in connectedPairs) return@forEach
				if (asset.particles[first].region != asset.particles[second].region) return@forEach
				val firstWeight = asset.particles[first].inverseMass
				val secondWeight = asset.particles[second].inverseMass
				val weight = firstWeight + secondWeight
				if (weight <= 0f) return@forEach
				firstScratch.set(positions[first]).sub(positions[second])
				val lengthSquared = firstScratch.lengthSquared()
				if (lengthSquared >= minimumDistance * minimumDistance) return@forEach
				if (lengthSquared <= EPSILON) firstScratch.set(1f, 0f, 0f) else firstScratch.div(sqrt(lengthSquared))
				val correction = (minimumDistance - sqrt(lengthSquared.coerceAtLeast(EPSILON))) / weight
				positions[first].fma(firstWeight * correction, firstScratch)
				positions[second].fma(-secondWeight * correction, firstScratch)
			}
		}
	}

	private fun applyFriction(particle: Int, normal: Vector3fc, friction: Float) {
		firstScratch.set(positions[particle]).sub(previousPositions[particle])
		val normalSpeed = firstScratch.dot(normal)
		secondScratch.set(normal).mul(normalSpeed)
		firstScratch.sub(secondScratch).mul(friction.coerceIn(0f, 1f))
		previousPositions[particle].add(firstScratch)
	}

	private fun closestPoints(
		p1: Vector3fc,
		q1: Vector3fc,
		p2: Vector3fc,
		q2: Vector3fc,
		firstResult: Vector3f,
		secondResult: Vector3f,
	) {
		val d1 = Vector3f(q1).sub(p1)
		val d2 = Vector3f(q2).sub(p2)
		val r = Vector3f(p1).sub(p2)
		val a = d1.dot(d1)
		val e = d2.dot(d2)
		val f = d2.dot(r)
		var s: Float
		var t: Float
		if (a <= EPSILON && e <= EPSILON) {
			s = 0f
			t = 0f
		} else if (a <= EPSILON) {
			s = 0f
			t = (f / e).coerceIn(0f, 1f)
		} else {
			val c = d1.dot(r)
			if (e <= EPSILON) {
				t = 0f
				s = (-c / a).coerceIn(0f, 1f)
			} else {
				val b = d1.dot(d2)
				val denominator = a * e - b * b
				s = if (denominator != 0f) ((b * f - c * e) / denominator).coerceIn(0f, 1f) else 0f
				t = (b * s + f) / e
				if (t < 0f) {
					t = 0f
					s = (-c / a).coerceIn(0f, 1f)
				} else if (t > 1f) {
					t = 1f
					s = ((b - c) / a).coerceIn(0f, 1f)
				}
			}
		}
		firstResult.set(p1).fma(s, d1)
		secondResult.set(p2).fma(t, d2)
	}

	private class SpatialHash(private val cellSize: Float) {
		private val cells = HashMap<Cell, MutableList<Int>>()

		fun rebuild(positions: Array<Vector3f>) {
			cells.clear()
			positions.forEachIndexed { index, position -> cells.getOrPut(cell(position), ::ArrayList) += index }
		}

		fun neighbors(position: Vector3fc): Sequence<Int> {
			val center = cell(position)
			return sequence {
				for (x in center.x - 1..center.x + 1) {
					for (y in center.y - 1..center.y + 1) {
						for (z in center.z - 1..center.z + 1) {
							cells[Cell(x, y, z)]?.forEach { yield(it) }
						}
					}
				}
			}
		}

		private fun cell(position: Vector3fc): Cell = Cell(
			floor(position.x() / cellSize).toInt(),
			floor(position.y() / cellSize).toInt(),
			floor(position.z() / cellSize).toInt(),
		)

		private data class Cell(val x: Int, val y: Int, val z: Int)
	}

	private companion object {
		const val EPSILON = 1.0e-8f
		val ZERO = Vector3f()
		val CAPSULE_SAMPLES = floatArrayOf(0f, 0.5f, 1f)

		fun pairKey(first: Int, second: Int): Long {
			val minimum = minOf(first, second)
			val maximum = maxOf(first, second)
			return minimum.toLong() shl 32 or (maximum.toLong() and 0xffffffffL)
		}
	}
}
