package com.micheanl.libmmd.physics.game

import com.micheanl.libmmd.physics.PhysicsWorld
import com.micheanl.libmmd.physics.StaticCollider
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.floor

@Environment(EnvType.CLIENT)
class BlockCollisionBridge(
	private val physics: PhysicsWorld,
	private val horizontalRadius: Int = 5,
	private val verticalRadius: Int = 4,
) : AutoCloseable {
	private val entries = HashMap<Long, BlockEntry>()
	private var closed = false

	val blockCount: Int
		get() = entries.size

	init {
		require(horizontalRadius >= 0) { "horizontalRadius must be non-negative" }
		require(verticalRadius >= 0) { "verticalRadius must be non-negative" }
	}

	fun sync(level: ClientLevel, centers: Collection<Vector3fc>) {
		check(!closed) { "Block collision bridge is closed" }
		val required = HashSet<Long>()
		centers.forEach { center -> collect(level, center, required) }
		val obsolete = entries.keys.filterNot(required::contains)
		obsolete.forEach { key -> entries.remove(key)?.close() }
	}

	fun clear() {
		entries.values.forEach(BlockEntry::close)
		entries.clear()
	}

	override fun close() {
		if (closed) return
		closed = true
		clear()
	}

	private fun collect(level: ClientLevel, center: Vector3fc, required: MutableSet<Long>) {
		val centerX = floor(center.x()).toInt()
		val centerY = floor(center.y()).toInt()
		val centerZ = floor(center.z()).toInt()
		for (x in centerX - horizontalRadius..centerX + horizontalRadius) {
			for (y in centerY - verticalRadius..centerY + verticalRadius) {
				for (z in centerZ - horizontalRadius..centerZ + horizontalRadius) {
					val position = BlockPos(x, y, z)
					if (!level.isLoaded(position)) continue
					val key = position.asLong()
					required += key
					val state = level.getBlockState(position)
					val boxes = if (state.isAir) {
						emptyList()
					} else {
						state.getCollisionShape(level, position).toAabbs().mapNotNull { bounds ->
							BlockBox.from(position, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)
						}
					}
					val previous = entries[key]
					if (boxes.isEmpty()) {
						entries.remove(key)?.close()
					} else if (previous == null || previous.boxes != boxes) {
						previous?.close()
						entries[key] = createEntry(boxes)
					}
				}
			}
		}
	}

	private fun createEntry(boxes: List<BlockBox>): BlockEntry {
		val colliders = ArrayList<StaticCollider>(boxes.size)
		try {
			boxes.forEach { box ->
				colliders += physics.addStaticBox(
					center = Vector3f(box.centerX, box.centerY, box.centerZ),
					halfExtents = Vector3f(box.halfX, box.halfY, box.halfZ),
				)
			}
			return BlockEntry(boxes, colliders)
		} catch (exception: Throwable) {
			colliders.forEach(StaticCollider::close)
			throw exception
		}
	}

	private data class BlockEntry(val boxes: List<BlockBox>, val colliders: List<StaticCollider>) : AutoCloseable {
		override fun close() = colliders.forEach(StaticCollider::close)
	}

	private data class BlockBox(
		val centerX: Float,
		val centerY: Float,
		val centerZ: Float,
		val halfX: Float,
		val halfY: Float,
		val halfZ: Float,
	) {
		companion object {
			fun from(
				position: BlockPos,
				minimumX: Double,
				minimumY: Double,
				minimumZ: Double,
				maximumX: Double,
				maximumY: Double,
				maximumZ: Double,
			): BlockBox? {
				val halfX = ((maximumX - minimumX) * 0.5).toFloat()
				val halfY = ((maximumY - minimumY) * 0.5).toFloat()
				val halfZ = ((maximumZ - minimumZ) * 0.5).toFloat()
				if (halfX <= MINIMUM_EXTENT || halfY <= MINIMUM_EXTENT || halfZ <= MINIMUM_EXTENT) return null
				return BlockBox(
					centerX = position.x + ((minimumX + maximumX) * 0.5).toFloat(),
					centerY = position.y + ((minimumY + maximumY) * 0.5).toFloat(),
					centerZ = position.z + ((minimumZ + maximumZ) * 0.5).toFloat(),
					halfX = halfX,
					halfY = halfY,
					halfZ = halfZ,
				)
			}
		}
	}

	private companion object {
		const val MINIMUM_EXTENT = 1.0e-5f
	}
}
