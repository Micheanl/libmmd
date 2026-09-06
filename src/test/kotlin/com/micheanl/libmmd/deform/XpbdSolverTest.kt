package com.micheanl.libmmd.deform

import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class XpbdSolverTest {
	@Test
	fun `uses fixed steps and keeps structural stretch bounded`() {
		val asset = DeformableAsset(
			vertexCount = 2,
			particles = listOf(
				particle(0, Vector3f(0f, 0f, 0f), 0f),
				particle(1, Vector3f(0f, -1f, 0f), 1f),
			),
			regions = emptyList(),
			distanceConstraints = listOf(DistanceConstraint(0, 1, 1f)),
			bendingConstraints = emptyList(),
			volumeConstraints = emptyList(),
		)
		val solver = XpbdSolver(asset, XpbdConfiguration(fixedStepSeconds = 1f / 120f))
		val targets = arrayOf(Vector3f(0f, 0f, 0f), null)

		val firstSteps = solver.advance(1f / 240f, targets, inertialAcceleration = Vector3f(0f, -50f, 0f))
		val secondSteps = solver.advance(1f / 240f, targets, inertialAcceleration = Vector3f(0f, -50f, 0f))

		assertEquals(0, firstSteps)
		assertEquals(1, secondSteps)
		assertTrue(solver.position(0).distance(solver.position(1)) <= 1.081f)
		assertTrue(solver.allFinite())
	}

	@Test
	fun `separates non adjacent particles through the spatial hash`() {
		val asset = DeformableAsset(
			vertexCount = 2,
			particles = listOf(
				particle(0, Vector3f(0f, 0f, 0f), 1f),
				particle(1, Vector3f(0.01f, 0f, 0f), 1f),
			),
			regions = emptyList(),
			distanceConstraints = emptyList(),
			bendingConstraints = emptyList(),
			volumeConstraints = emptyList(),
		)
		val solver = XpbdSolver(
			asset,
			XpbdConfiguration(
				fixedStepSeconds = 0.01f,
				solverIterations = 2,
				particleRadius = 0.1f,
				gravity = Vector3f(),
			),
		)

		solver.advance(0.01f, arrayOf(null, null))

		assertTrue(solver.position(0).distance(solver.position(1)) >= 0.199f)
	}

	@Test
	fun `prevents a particle from tunneling through a moving capsule`() {
		val asset = DeformableAsset(
			vertexCount = 1,
			particles = listOf(particle(0, Vector3f(-1f, 0f, 0f), 1f)),
			regions = emptyList(),
			distanceConstraints = emptyList(),
			bendingConstraints = emptyList(),
			volumeConstraints = emptyList(),
		)
		val solver = XpbdSolver(
			asset,
			XpbdConfiguration(
				fixedStepSeconds = 0.1f,
				solverIterations = 1,
				particleRadius = 0.05f,
				selfCollision = false,
				gravity = Vector3f(),
			),
		)
		val capsule = AnimatedCapsule(
			previousStart = Vector3f(0f, -1f, 0f),
			previousEnd = Vector3f(0f, 1f, 0f),
			start = Vector3f(0f, -1f, 0f),
			end = Vector3f(0f, 1f, 0f),
			radius = 0.2f,
		)

		solver.advance(0.1f, arrayOf(null), listOf(capsule), Vector3f(200f, 0f, 0f))

		assertTrue(solver.position(0).x <= -0.249f)
	}

	@Test
	fun `restores the signed volume of a closed body`() {
		val triangles = intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3)
		val asset = DeformableAsset(
			vertexCount = 4,
			particles = listOf(
				particle(0, Vector3f(0f, 0f, 0f), 1f),
				particle(1, Vector3f(1f, 0f, 0f), 1f),
				particle(2, Vector3f(0f, 1f, 0f), 1f),
				particle(3, Vector3f(0f, 0f, 1f), 1f),
			),
			regions = emptyList(),
			distanceConstraints = emptyList(),
			bendingConstraints = emptyList(),
			volumeConstraints = listOf(VolumeConstraint(triangles, 1f / 6f)),
		)
		val solver = XpbdSolver(
			asset,
			XpbdConfiguration(
				fixedStepSeconds = 0.01f,
				solverIterations = 12,
				volumeCompliance = 0f,
				selfCollision = false,
				gravity = Vector3f(),
			),
		)
		solver.reset(arrayOf(
			Vector3f(0f, 0f, 0f),
			Vector3f(1f, 0f, 0f),
			Vector3f(0f, 1f, 0f),
			Vector3f(0f, 0f, 2f),
		))

		solver.advance(0.01f, arrayOf(null, null, null, null))

		val a = solver.position(0)
		val b = solver.position(1)
		val c = solver.position(2)
		val d = solver.position(3)
		val volume = signedTetraVolume(a, b, c, d)
		assertTrue(abs(volume - 1f / 6f) < 0.01f, "volume=$volume")
	}

	private fun signedTetraVolume(a: Vector3f, b: Vector3f, c: Vector3f, d: Vector3f): Float =
		Vector3f(b).sub(a).dot(Vector3f(c).sub(a).cross(Vector3f(d).sub(a))) / 6f

	private fun particle(index: Int, position: Vector3f, inverseMass: Float) = DeformableParticle(
		vertexIndex = index,
		restPosition = position,
		inverseMass = inverseMass,
		region = DeformableRegionType.SKIRT,
	)
}
