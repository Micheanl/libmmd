package com.micheanl.libmmd.asset

import net.minecraft.resources.Identifier
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetPipelineTest {
	private val modelId = Identifier.fromNamespaceAndPath("example", "models/mmd/miku/model.pmx")
	private val motionId = Identifier.fromNamespaceAndPath("example", "motions/walk.vmd")
	private val directExecutor = Executor(Runnable::run)

	@Test
	fun `resolves PMX texture references relative to the model`() {
		val textureId = ResourcePath.resolve(modelId, "..\\textures/./body.png")

		assertEquals("example:models/mmd/textures/body.png", textureId.toString())
	}

	@Test
	fun `rejects unsafe texture references`() {
		assertFailsWith<IllegalArgumentException> {
			ResourcePath.resolve(modelId, "../../../../outside.png")
		}
		assertFailsWith<IllegalArgumentException> {
			ResourcePath.resolve(modelId, "/absolute.png")
		}
		assertFailsWith<IllegalArgumentException> {
			ResourcePath.resolve(modelId, "other:texture.png")
		}
		assertFailsWith<IllegalArgumentException> {
			ResourcePath.resolve(modelId, "Texture.PNG")
		}
	}

	@Test
	fun `decodes and caches a model once`() {
		val reads = AtomicInteger()
		val repository = ModelRepository(
			resources = ResourceSource { _, _ ->
				reads.incrementAndGet()
				modelFixture("textures/body.png")
			},
			decodeExecutor = directExecutor,
		)

		val first = repository.load(modelId).join()
		val second = repository.load(modelId).join()

		assertEquals(first, second)
		assertEquals(1, reads.get())
		assertEquals("example:models/mmd/miku/textures/body.png", first.textureIds.single().toString())
		assertEquals(1, repository.cachedModelCount)
		assertTrue(repository.invalidate(modelId))
		assertFalse(repository.invalidate(modelId))
	}

	@Test
	fun `removes failed model loads from the cache`() {
		val reads = AtomicInteger()
		val repository = ModelRepository(
			resources = ResourceSource { _, _ ->
				if (reads.getAndIncrement() == 0) byteArrayOf() else modelFixture("")
			},
			decodeExecutor = directExecutor,
		)

		assertFailsWith<Exception> { repository.load(modelId).join() }
		assertEquals(0, repository.cachedModelCount)
		assertTrue(repository.load(modelId).join().textureIds.isEmpty())
		assertEquals(2, reads.get())
	}

	@Test
	fun `decodes and caches a motion once`() {
		val reads = AtomicInteger()
		val repository = MotionRepository(
			resources = ResourceSource { _, _ ->
				reads.incrementAndGet()
				motionFixture()
			},
			decodeExecutor = directExecutor,
		)

		val first = repository.load(motionId).join()
		val second = repository.load(motionId).join()

		assertEquals(first, second)
		assertEquals(1, reads.get())
		assertEquals("Test", first.motion.modelName)
		assertEquals(1, repository.cachedMotionCount)
		assertTrue(repository.invalidate(motionId))
		assertFalse(repository.invalidate(motionId))
	}

	@Test
	fun `removes failed motion loads from the cache`() {
		val reads = AtomicInteger()
		val repository = MotionRepository(
			resources = ResourceSource { _, _ ->
				if (reads.getAndIncrement() == 0) byteArrayOf() else motionFixture()
			},
			decodeExecutor = directExecutor,
		)

		assertFailsWith<Exception> { repository.load(motionId).join() }
		assertEquals(0, repository.cachedMotionCount)
		assertEquals("Test", repository.load(motionId).join().motion.modelName)
		assertEquals(2, reads.get())
	}

	@Test
	fun `uploads and releases render data on the render executor`() {
		val renderTasks = ArrayDeque<Runnable>()
		val renderExecutor = Executor(renderTasks::addLast)
		val closes = AtomicInteger()
		val models = ModelRepository(
			resources = ResourceSource { _, _ -> modelFixture("") },
			decodeExecutor = directExecutor,
		)
		val renders = RenderRepository(
			models = models,
			renderExecutor = renderExecutor,
			backend = RenderBackend { TestRenderData(closes) },
		)

		val loading = renders.load(modelId)
		assertFalse(loading.isDone)
		renderTasks.removeFirst().run()
		assertTrue(loading.isDone)

		val releasing = renders.invalidate(modelId)
		assertFalse(releasing.isDone)
		renderTasks.removeFirst().run()
		assertTrue(releasing.isDone)
		assertEquals(1, closes.get())
	}

	private class TestRenderData(
		private val closes: AtomicInteger,
	) : AutoCloseable {
		override fun close() {
			closes.incrementAndGet()
		}
	}

	private fun modelFixture(texturePath: String): ByteArray = ByteArrayOutputStream().apply {
		write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte()))
		writeFloat32(2.1f)
		write(8)
		write(1)
		write(0)
		repeat(6) { write(1) }
		writeText("Model")
		repeat(3) { writeText("") }
		writeInt32(0)
		writeInt32(0)
		writeInt32(if (texturePath.isEmpty()) 0 else 1)
		if (texturePath.isNotEmpty()) writeText(texturePath)
		repeat(7) { writeInt32(0) }
	}.toByteArray()

	private fun motionFixture(): ByteArray = ByteArrayOutputStream().apply {
		writeFixedText("Vocaloid Motion Data 0002", 30)
		writeFixedText("Test", 20)
		writeInt32(0)
	}.toByteArray()

	private fun ByteArrayOutputStream.writeFloat32(value: Float) {
		write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
	}

	private fun ByteArrayOutputStream.writeInt32(value: Int) {
		write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
	}

	private fun ByteArrayOutputStream.writeText(value: String) {
		val bytes = value.toByteArray(StandardCharsets.UTF_8)
		writeInt32(bytes.size)
		write(bytes)
	}

	private fun ByteArrayOutputStream.writeFixedText(value: String, size: Int) {
		val bytes = value.toByteArray(StandardCharsets.US_ASCII)
		write(bytes)
		repeat(size - bytes.size) { write(0) }
	}
}
