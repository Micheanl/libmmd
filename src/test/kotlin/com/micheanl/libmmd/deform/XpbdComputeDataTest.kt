package com.micheanl.libmmd.deform

import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XpbdComputeDataTest {
	@Test
	fun `colors constraints into batches without particle write conflicts`() {
		val constraints = listOf(
			DistanceConstraint(0, 1, 1f),
			DistanceConstraint(1, 2, 1f),
			DistanceConstraint(2, 3, 1f),
			DistanceConstraint(3, 0, 1f),
			DistanceConstraint(0, 2, 1.4f),
		)
		val particles = (0..3).map { index ->
			DeformableParticle(index, Vector3f(index.toFloat(), 0f, 0f), 1f, DeformableRegionType.SKIRT)
		}
		val asset = DeformableAsset(4, particles, emptyList(), constraints, emptyList(), emptyList())

		val data = XpbdComputeData.from(asset)

		assertTrue(data.distanceBatches.size >= 3)
		assertEquals(constraints.size, data.distanceBatches.sumOf(ComputeBatch::count))
		assertEquals(constraints.size * XpbdComputeData.CONSTRAINT_STRIDE, data.distanceConstraintBytes().remaining())
		assertEquals(particles.size * XpbdComputeData.PARTICLE_STRIDE, data.particleBytes().remaining())
		val bytes = data.distanceConstraintBytes()
		data.distanceBatches.forEach { batch ->
			val touched = HashSet<Int>()
			repeat(batch.count) { localIndex ->
				val offset = (batch.first + localIndex) * XpbdComputeData.CONSTRAINT_STRIDE
				assertTrue(touched.add(bytes.getInt(offset)))
				assertTrue(touched.add(bytes.getInt(offset + Int.SIZE_BYTES)))
			}
		}
	}

	@Test
	fun `packs closed-region volume topology`() {
		val particles = listOf(
			DeformableParticle(0, Vector3f(0f, 0f, 0f), 0f, DeformableRegionType.HAIR),
			DeformableParticle(1, Vector3f(1f, 0f, 0f), 1f, DeformableRegionType.HAIR),
			DeformableParticle(2, Vector3f(0f, 1f, 0f), 1f, DeformableRegionType.HAIR),
			DeformableParticle(3, Vector3f(0f, 0f, 1f), 1f, DeformableRegionType.HAIR),
		)
		val triangles = intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3)
		val asset = DeformableAsset(
			4,
			particles,
			emptyList(),
			emptyList(),
			emptyList(),
			listOf(VolumeConstraint(triangles, 1f / 6f)),
		)

		val data = XpbdComputeData.from(asset)
		val descriptor = data.volumeConstraintBytes()

		assertEquals(XpbdComputeData.VOLUME_CONSTRAINT_STRIDE, descriptor.remaining())
		assertEquals(0, descriptor.getInt(0))
		assertEquals(triangles.size, descriptor.getInt(Int.SIZE_BYTES))
		assertEquals(4, descriptor.getInt(Int.SIZE_BYTES * 3))
		assertEquals(triangles.size * Int.SIZE_BYTES, data.volumeTriangleIndexBytes().remaining())
		assertEquals(4 * Int.SIZE_BYTES, data.volumeParticleIndexBytes().remaining())
	}
}
