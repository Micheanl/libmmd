package com.micheanl.libmmd.render

import com.micheanl.libmmd.Engine
import com.micheanl.libmmd.asset.RenderAsset
import com.micheanl.libmmd.physics.PhysicsWorld
import com.micheanl.libmmd.physics.PhysicsAssetConfiguration
import com.micheanl.libmmd.physics.PhysicsHit
import com.micheanl.libmmd.physics.PhysicsGrab
import com.micheanl.libmmd.physics.RigidBodySnapshot
import com.micheanl.libmmd.physics.game.BlockCollisionBridge
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.resources.Identifier
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.concurrent.ConcurrentHashMap

@Environment(EnvType.CLIENT)
object WorldModels {
	private val instances = ConcurrentHashMap<Identifier, ModelInstance>()
	private var physicsWorld: PhysicsWorld? = null
	private var blockCollisions: BlockCollisionBridge? = null

	operator fun get(id: Identifier): ModelInstance? = instances[id]

	fun set(
		id: Identifier,
		asset: RenderAsset<GpuMesh>,
		transform: Matrix4fc,
		physicsConfiguration: PhysicsAssetConfiguration = PhysicsAssetConfiguration(),
	): ModelInstance {
		RenderSystem.assertOnRenderThread()
		val hasPhysics = physicsConfiguration.enabled &&
			(asset.source.model.rigidBodies.isNotEmpty() || physicsConfiguration.autoGenerateBodyColliders)
		val instance = ModelInstance(asset, transform, if (hasPhysics) physicsWorld() else null, physicsConfiguration)
		instances.put(id, instance)?.close()
		return instance
	}

	fun remove(id: Identifier): Boolean {
		RenderSystem.assertOnRenderThread()
		val instance = instances.remove(id) ?: return false
		instance.close()
		return true
	}

	fun clear() {
		RenderSystem.assertOnRenderThread()
		instances.values.forEach(ModelInstance::close)
		instances.clear()
		blockCollisions?.clear()
	}

	fun raycast(origin: Vector3fc, direction: Vector3fc, maxDistance: Float): PhysicsHit? =
		physicsWorld?.raycast(origin, direction, maxDistance)

	fun raycastFromCamera(maxDistance: Float = 6f): PhysicsHit? {
		val camera = Minecraft.getInstance().cameraEntity ?: return null
		val origin = camera.eyePosition
		val direction = camera.lookAngle
		return raycast(
			Vector3f(origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat()),
			Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()),
			maxDistance,
		)
	}

	fun pushFromCamera(maxDistance: Float = 6f, impulse: Float = 2f): PhysicsHit? {
		require(impulse.isFinite() && impulse > 0f) { "impulse must be finite and positive" }
		val camera = Minecraft.getInstance().cameraEntity ?: return null
		val direction = camera.lookAngle
		val hit = raycastFromCamera(maxDistance) ?: return null
		hit.rig.applyImpulseAt(
			hit.rigidBodyIndex,
			Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()).normalize().mul(impulse),
			hit.position,
		)
		return hit
	}

	fun grabFromCamera(maxDistance: Float = 6f): PhysicsGrab? {
		val hit = raycastFromCamera(maxDistance) ?: return null
		return grab(hit)
	}

	fun grab(hit: PhysicsHit): PhysicsGrab? = physicsWorld?.grab(hit)

	fun moveGrabFromCamera(grab: PhysicsGrab, distance: Float) {
		require(distance.isFinite() && distance > 0f) { "distance must be finite and positive" }
		val camera = Minecraft.getInstance().cameraEntity ?: return
		val origin = camera.eyePosition
		val direction = camera.lookAngle
		grab.moveTo(
			Vector3f(
				(origin.x + direction.x * distance).toFloat(),
				(origin.y + direction.y * distance).toFloat(),
				(origin.z + direction.z * distance).toFloat(),
			),
		)
	}

	fun physicsSnapshots(): List<RigidBodySnapshot> = instances.values.flatMap { instance ->
		val rig = instance.physics
		if (rig == null || rig.isClosed) emptyList() else rig.snapshots()
	}

	internal fun register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(::collect)
		ClientTickEvents.END_CLIENT_TICK.register(::stepPhysics)
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
		ClientLifecycleEvents.CLIENT_STOPPING.register { shutdown() }
	}

	private fun physicsWorld(): PhysicsWorld = physicsWorld ?: PhysicsWorld().also { world ->
		Engine.logger.info(
			"Initialized PhysX {}.{}.{} on {}",
			world.version.major,
			world.version.minor,
			world.version.patch,
			world.device.activeProcessor,
		)
		physicsWorld = world
		blockCollisions = BlockCollisionBridge(world)
	}

	private fun stepPhysics(client: Minecraft) {
		val level = client.level ?: return
		if (client.isPaused) return
		physicsWorld?.let { world ->
			val centers = instances.values.mapNotNull { instance ->
				if (instance.physics == null) null else instance.transform.getTranslation(Vector3f())
			}
			blockCollisions?.sync(level, centers)
			world.step(1f / 20f)
		}
		instances.values.forEach { instance ->
			instance.advanceDeformable(1f / 20f)
			if (instance.hasDynamicDeformation) instance.updateDeformation()
		}
	}

	private fun shutdown() {
		clear()
		blockCollisions?.close()
		blockCollisions = null
		physicsWorld?.close()
		physicsWorld = null
	}

	private fun collect(context: LevelRenderContext) {
		val camera = context.levelState().cameraRenderState.pos
		for (instance in instances.values) {
			val transform = Matrix4f()
				.translation(-camera.x.toFloat(), -camera.y.toFloat(), -camera.z.toFloat())
				.mul(instance.transform)
			context.submitNodeCollector().submitCustom(
				SubmitRenderPhases.SOLID,
				ModelSubmit(instance, transform),
			)
		}
	}
}
