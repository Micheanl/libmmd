package com.micheanl.libmmd.interaction

import com.micheanl.libmmd.Engine
import com.micheanl.libmmd.physics.PhysicsGrab
import com.micheanl.libmmd.physics.PhysicsHit
import com.micheanl.libmmd.render.PhysicsOverlay
import com.micheanl.libmmd.render.WorldModels
import com.micheanl.libmmd.player.PlayerModels
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.joml.Vector3f

@Environment(EnvType.CLIENT)
object InteractionController {
	private const val REACH = 8f
	private const val PUSH_IMPULSE = 2f
	private val category = KeyMapping.Category.register(Engine.id("controls"))
	private val pushKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping("key.libmmd.push", InputConstants.Type.KEYBOARD, InputConstants.KEY_P, category),
	)
	private val grabKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping("key.libmmd.grab", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, category),
	)
	private val debugKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping("key.libmmd.physics_debug", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, category),
	)
	private val reloadAvatarKey = KeyMappingHelper.registerKeyMapping(
		KeyMapping("key.libmmd.reload_avatar", InputConstants.Type.KEYBOARD, InputConstants.KEY_F10, category),
	)

	internal var hovered: PhysicsHit? = null
		private set
	internal var activeGrab: PhysicsGrab? = null
		private set
	internal var debugVisible = false
		private set
	private var grabDistance = 0f

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
		PhysicsOverlay.register()
	}

	private fun tick(client: Minecraft) {
		if (activeGrab?.isClosed == true) activeGrab = null
		if (client.level == null || client.cameraEntity == null) {
			reset()
			return
		}
		if (client.gui.screen() != null || client.isPaused) {
			hovered = null
			return
		}

		hovered = WorldModels.raycastFromCamera(REACH)
		while (pushKey.consumeClick()) push(client)
		while (grabKey.consumeClick()) toggleGrab(client)
		while (debugKey.consumeClick()) toggleDebug(client)
		while (reloadAvatarKey.consumeClick()) reloadAvatar(client)
		activeGrab?.let { WorldModels.moveGrabFromCamera(it, grabDistance) }
	}

	private fun push(client: Minecraft) {
		val hit = hovered ?: return notify(client, "message.libmmd.no_target")
		val direction = client.cameraEntity?.lookAngle ?: return
		hit.rig.applyImpulseAt(
			hit.rigidBodyIndex,
			Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
				.normalize()
				.mul(PUSH_IMPULSE),
			hit.position,
		)
		notify(client, "message.libmmd.pushed", hit.rigidBodyIndex)
	}

	private fun toggleGrab(client: Minecraft) {
		activeGrab?.let {
			it.close()
			activeGrab = null
			notify(client, "message.libmmd.released")
			return
		}
		val hit = hovered ?: return notify(client, "message.libmmd.no_target")
		activeGrab = WorldModels.grab(hit)
		if (activeGrab == null) return notify(client, "message.libmmd.no_target")
		grabDistance = hit.distance.coerceIn(0.5f, REACH)
		notify(client, "message.libmmd.grabbed", hit.rigidBodyIndex)
	}

	private fun toggleDebug(client: Minecraft) {
		debugVisible = !debugVisible
		notify(client, if (debugVisible) "message.libmmd.debug_on" else "message.libmmd.debug_off")
	}

	private fun reloadAvatar(client: Minecraft) {
		PlayerModels.reload(client)
		notify(client, "message.libmmd.avatar_reloaded")
	}

	private fun notify(client: Minecraft, key: String, vararg arguments: Any) {
		client.player?.sendOverlayMessage(Component.translatable(key, *arguments))
	}

	private fun reset() {
		activeGrab?.close()
		activeGrab = null
		hovered = null
	}
}
