package com.micheanl.libmmd.player

import com.micheanl.libmmd.Engine
import com.micheanl.libmmd.NativeRuntime
import com.micheanl.libmmd.animation.AnimationClip
import com.micheanl.libmmd.animation.AnimationGraph
import com.micheanl.libmmd.animation.AnimationGraphInput
import com.micheanl.libmmd.animation.GroundSample
import com.micheanl.libmmd.animation.GroundSampler
import com.micheanl.libmmd.animation.HumanoidRig
import com.micheanl.libmmd.animation.HumanoidJoint
import com.micheanl.libmmd.animation.MotionState
import com.micheanl.libmmd.animation.LocomotionParameters
import com.micheanl.libmmd.animation.ProceduralPoseSolver
import com.micheanl.libmmd.animation.RetargetProfile
import com.micheanl.libmmd.asset.ModelAsset
import com.micheanl.libmmd.asset.ModelRepository
import com.micheanl.libmmd.asset.MotionAsset
import com.micheanl.libmmd.asset.MotionRepository
import com.micheanl.libmmd.asset.RenderAsset
import com.micheanl.libmmd.deform.DeformableRegionExtractor
import com.micheanl.libmmd.deform.DeformableMotionInput
import com.micheanl.libmmd.render.GpuMesh
import com.micheanl.libmmd.render.MCRenderBackend
import com.micheanl.libmmd.render.ModelInstance
import com.micheanl.libmmd.render.WorldModels
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import java.util.concurrent.CompletableFuture
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

@Environment(EnvType.CLIENT)
object PlayerModels {
	private const val PLAYER_HEIGHT = 1.8f
	private const val RETRY_TICKS = 100
	private const val MAX_TEXTURE_BYTES = 64 * 1024 * 1024
	private val modelId = Engine.id("external/player/model.pmx")
	private val entries = HashMap<Int, PlayerEntry>()
	private var asset: PlayerAsset? = null
	private var loading: CompletableFuture<PlayerAsset>? = null
	private var retryTicks = 0
	private var generation = 0

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clearPlayers() }
		ClientLifecycleEvents.CLIENT_STOPPING.register { shutdown() }
	}

	fun isReplaced(entityId: Int): Boolean = entityId in entries

	fun reload(client: Minecraft) {
		generation++
		loading = null
		clearPlayers()
		asset?.close(client)
		asset = null
		retryTicks = 0
	}

	internal fun animationDebugSnapshots(): List<AnimationDebugSnapshot> = entries.values.map { entry ->
		val instance = entry.instance
		val pose = instance.pose
		val rig = asset?.rig ?: return@map AnimationDebugSnapshot(
			emptyList(),
			emptyList(),
			null,
			null,
			instance.transform.getTranslation(Vector3f()),
			entry.graph.snapshot(),
			entry.proceduralSolver.strideScale,
			entry.proceduralSolver.orientationRadians,
		)
		val axisLength = rig.height * 0.035f
		fun worldPosition(index: Int): Vector3f = Matrix4f(instance.transform)
			.mul(pose.globalMatrix(index))
			.transformPosition(Vector3f())
		val mappedIndices = HumanoidJoint.entries.mapNotNull(rig::get).distinct()
		val bones = mappedIndices.mapNotNull { index ->
			val parent = rig.skeleton.bones[index].parentIndex.takeIf { it >= 0 } ?: return@mapNotNull null
			BoneDebugSegment(worldPosition(parent), worldPosition(index))
		}
		val joints = mappedIndices.map { index ->
			val transform = Matrix4f(instance.transform).mul(pose.globalMatrix(index))
			JointDebugAxes(
				origin = transform.transformPosition(Vector3f()),
				x = transform.transformPosition(Vector3f(axisLength, 0f, 0f)),
				y = transform.transformPosition(Vector3f(0f, axisLength, 0f)),
				z = transform.transformPosition(Vector3f(0f, 0f, axisLength)),
			)
		}
		fun foot(footJoint: HumanoidJoint, ikJoint: HumanoidJoint, contact: Float, locked: Boolean): FootDebugPoint? {
			val foot = rig[footJoint] ?: return null
			return FootDebugPoint(worldPosition(foot), rig[ikJoint]?.let(::worldPosition), contact, locked)
		}
		val labelPosition = rig[HumanoidJoint.HEAD]?.let(::worldPosition)
			?: instance.transform.getTranslation(Vector3f()).add(0f, 2f, 0f)
		AnimationDebugSnapshot(
			bones = bones,
			joints = joints,
			leftFoot = foot(
				HumanoidJoint.LEFT_FOOT,
				HumanoidJoint.LEFT_FOOT_IK,
				entry.proceduralSolver.contacts.left,
				entry.proceduralSolver.locks.left,
			),
			rightFoot = foot(
				HumanoidJoint.RIGHT_FOOT,
				HumanoidJoint.RIGHT_FOOT_IK,
				entry.proceduralSolver.contacts.right,
				entry.proceduralSolver.locks.right,
			),
			labelPosition = labelPosition.add(0f, 0.2f, 0f),
			graph = entry.graph.snapshot(),
			strideScale = entry.proceduralSolver.strideScale,
			orientationRadians = entry.proceduralSolver.orientationRadians,
		)
	}

	fun updateRenderTransform(state: AvatarRenderState) {
		entries[state.id]?.instance?.setRenderTransform(transform(
			x = state.x.toFloat(),
			y = state.y.toFloat(),
			z = state.z.toFloat(),
			yawDegrees = state.bodyRot,
			asset = asset ?: return,
		))
	}

	private fun tick(client: Minecraft) {
		val level = client.level
		if (level == null) {
			clearPlayers()
			return
		}
		val loaded = asset
		if (loaded == null) {
			if (loading == null && retryTicks-- <= 0) beginLoad(client)
			return
		}

		val activeIds = HashSet<Int>()
		level.players().forEach { player ->
			activeIds += player.id
			val entry = entries[player.id]?.takeIf { it.playerUuid == player.uuid }
				?: createEntry(player, loaded)
			val animation = selectAnimation(player)
			val velocity = localVelocity(player)
			val turnRate = wrapDegrees(player.yBodyRot - entry.previousBodyYaw) * 20f
			entry.previousBodyYaw = player.yBodyRot
			val nextTransform = transform(player.x.toFloat(), player.y.toFloat(), player.z.toFloat(), player.yBodyRot, loaded)
			entry.graph.update(
				AnimationGraphInput(
					baseState = animation.base,
					planarSpeed = velocity.planar,
					sprinting = player.isSprinting,
					playbackSpeed = playbackSpeed(player, animation.base),
					upperBodyState = animation.upperBody,
					turnRateDegrees = turnRate,
				),
				1f / 20f,
			)
			entry.dynamics.apply(
				pose = entry.instance.pose,
				state = entry.graph.currentState,
				pitchDegrees = player.xRot,
				headYawDegrees = player.yHeadRot,
				bodyYawDegrees = player.yBodyRot,
				velocityX = player.deltaMovement.x.toFloat(),
				velocityY = player.deltaMovement.y.toFloat(),
				velocityZ = player.deltaMovement.z.toFloat(),
				onGround = player.onGround(),
				deltaSeconds = 1f / 20f,
			)
			entry.proceduralSolver.apply(
				pose = entry.instance.pose,
				state = entry.graph.currentState,
				onGround = player.onGround(),
				deltaSeconds = 1f / 20f,
				groundSampler = groundSampler(level, player, nextTransform, loaded.rig.height),
				locomotion = LocomotionParameters(
					planarSpeed = velocity.planar,
					forwardSpeed = velocity.forward,
					sideSpeed = velocity.side,
					sprinting = player.isSprinting,
				),
				modelTransform = nextTransform,
			)
			entry.instance.physics?.let { physics ->
				physics.updateCharacterMotion(
					Vector3f(
						(player.deltaMovement.x * 20.0).toFloat(),
						(player.deltaMovement.y * 20.0).toFloat(),
						(player.deltaMovement.z * 20.0).toFloat(),
					),
					1f / 20f,
				)
				client.cameraEntity?.let { camera ->
					val dx = player.x - camera.x
					val dy = player.y - camera.y
					val dz = player.z - camera.z
					physics.setViewerDistance(sqrt((dx * dx + dy * dy + dz * dz).toFloat()))
				}
				physics.setAnimationContacts(entry.proceduralSolver.contacts)
			}
			entry.instance.updateDeformableMotion(
				DeformableMotionInput(
					localVelocity = Vector3f(velocity.side * 20f, player.deltaMovement.y.toFloat() * 20f, velocity.forward * 20f),
					angularVelocityRadians = Math.toRadians(turnRate.toDouble()).toFloat(),
					grounded = player.onGround(),
				),
				1f / 20f,
			)
			entry.instance.followTransform(nextTransform)
			if (entry.instance.physics == null) entry.instance.updateDeformation()
		}
		entries.keys.filterNot(activeIds::contains).forEach(::removeEntry)
	}

	private fun createEntry(player: AbstractClientPlayer, asset: PlayerAsset): PlayerEntry {
		removeEntry(player.id)
		val id = Engine.id("players/${player.uuid}")
		val instance = WorldModels.set(
			id,
			asset.render,
			transform(player.x.toFloat(), player.y.toFloat(), player.z.toFloat(), player.yBodyRot, asset),
			asset.profile.physicsConfiguration(),
		)
		val graph = AnimationGraph(instance.pose, asset.rig, asset.clips)
		graph.update(AnimationGraphInput(MotionState.IDLE), 0f)
		instance.physics?.resetToPose()
		instance.updateDeformation()
		return PlayerEntry(
			playerUuid = player.uuid,
			modelId = id,
			instance = instance,
			graph = graph,
			dynamics = PoseDynamics(instance.pose.skeleton),
			proceduralSolver = ProceduralPoseSolver(asset.rig, asset.groundHeight, asset.profile.proceduralConfiguration()),
			previousBodyYaw = player.yBodyRot,
		).also { entries[player.id] = it }
	}

	private fun selectAnimation(player: AbstractClientPlayer): PlayerAnimation {
		if (player.isDeadOrDying) return PlayerAnimation(MotionState.DIE)
		if (player.isSleeping) return PlayerAnimation(MotionState.SLEEP)
		if (player.isFallFlying) return PlayerAnimation(MotionState.ELYTRA_FLY)
		if (player.vehicle is AbstractHorse) return PlayerAnimation(MotionState.HORSE)
		if (player.isPassenger) return PlayerAnimation(MotionState.RIDE)
		val base = when {
			player.onClimbable() && player.deltaMovement.y > 0.015 -> MotionState.CLIMB_UP
			player.onClimbable() && player.deltaMovement.y < -0.015 -> MotionState.CLIMB_DOWN
			player.onClimbable() -> MotionState.CLIMB
			player.pose == Pose.SWIMMING && player.isInWater -> MotionState.SWIM
			player.pose == Pose.SWIMMING -> MotionState.CRAWL
			player.isCrouching -> MotionState.SNEAK
			horizontalSpeed(player) > 0.01 -> if (player.isSprinting) MotionState.SPRINT else MotionState.WALK
			else -> MotionState.IDLE
		}
		return PlayerAnimation(base, selectUpperBodyMotion(player))
	}

	private fun selectUpperBodyMotion(player: AbstractClientPlayer): MotionState? {
		if (player.isUsingItem) {
			val arm = player.usedItemHand.asArm(player.mainArm)
			return when {
				player.useItem.`is`(Items.BOW) && arm == HumanoidArm.LEFT -> MotionState.BOW_LEFT
				player.useItem.`is`(Items.SHIELD) && arm == HumanoidArm.LEFT -> MotionState.SHIELD_LEFT
				player.useItem.`is`(Items.SHIELD) -> MotionState.SHIELD_RIGHT
				else -> if (arm == HumanoidArm.LEFT) MotionState.SWING_LEFT else MotionState.SWING_RIGHT
			}
		}
		player.currentSwing?.let { swing ->
			val arm = swing.hand().asArm(player.mainArm)
			if (player.getItemInHand(swing.hand()).`is`(Items.IRON_SWORD) && arm == HumanoidArm.RIGHT) {
				return MotionState.SWORD_RIGHT
			}
			return if (arm == HumanoidArm.LEFT) MotionState.SWING_LEFT else MotionState.SWING_RIGHT
		}
		return null
	}

	private fun playbackSpeed(player: AbstractClientPlayer, state: MotionState?): Float = when (state) {
		MotionState.WALK, MotionState.SNEAK -> (horizontalSpeed(player) * 7f).toFloat().coerceIn(0.65f, 1.45f)
		MotionState.SPRINT -> (horizontalSpeed(player) * 5f).toFloat().coerceIn(0.8f, 1.5f)
		MotionState.CLIMB_UP, MotionState.CLIMB_DOWN -> (kotlin.math.abs(player.deltaMovement.y) * 8f).toFloat().coerceIn(0.6f, 1.4f)
		else -> 1f
	}

	private fun horizontalSpeed(player: AbstractClientPlayer): Double {
		val velocity = player.deltaMovement
		return sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
	}

	private fun localVelocity(player: AbstractClientPlayer): PlayerVelocity {
		val velocity = player.deltaMovement
		val yaw = Math.toRadians(player.yBodyRot.toDouble()).toFloat()
		val x = velocity.x.toFloat()
		val z = velocity.z.toFloat()
		return PlayerVelocity(
			planar = sqrt(x * x + z * z),
			forward = -sin(yaw) * x + cos(yaw) * z,
			side = cos(yaw) * x + sin(yaw) * z,
		)
	}

	private fun wrapDegrees(value: Float): Float {
		var wrapped = value % 360f
		if (wrapped >= 180f) wrapped -= 360f
		if (wrapped < -180f) wrapped += 360f
		return wrapped
	}

	private fun groundSampler(
		level: ClientLevel,
		player: AbstractClientPlayer,
		modelTransform: Matrix4fc,
		modelHeight: Float,
	): GroundSampler {
		val transform = Matrix4f(modelTransform)
		val inverse = Matrix4f(modelTransform).invert()
		val probeUp = modelHeight * 0.18f
		val probeDown = modelHeight * 0.3f
		return GroundSampler { localPosition ->
			val from = transform.transformPosition(Vector3f(localPosition).add(0f, probeUp, 0f))
			val to = transform.transformPosition(Vector3f(localPosition).sub(0f, probeDown, 0f))
			val hit = level.clip(
				ClipContext(
					Vec3(from.x.toDouble(), from.y.toDouble(), from.z.toDouble()),
					Vec3(to.x.toDouble(), to.y.toDouble(), to.z.toDouble()),
					ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE,
					player,
				),
			)
			if (hit.type == HitResult.Type.MISS) {
				null
			} else {
				val location = hit.location
				val localHit = inverse.transformPosition(
					Vector3f(location.x.toFloat(), location.y.toFloat(), location.z.toFloat()),
				)
				val direction = hit.direction
				val localNormal = inverse.transformDirection(
					Vector3f(direction.stepX.toFloat(), direction.stepY.toFloat(), direction.stepZ.toFloat()),
				).normalize()
				GroundSample(localHit.y, localNormal)
			}
		}
	}

	private fun beginLoad(client: Minecraft) {
		retryTicks = RETRY_TICKS
		val pack = PlayerPack.discover(FabricLoader.getInstance().gameDir.resolve(Engine.MOD_ID)) ?: return
		val loadGeneration = generation
		val future = load(pack, client)
		loading = future
		future.whenComplete { result, error ->
			client.execute {
				if (loading !== future) {
					result?.close(client)
					return@execute
				}
				loading = null
				if (loadGeneration != generation) {
					result?.close(client)
				} else if (error == null && result != null) {
					asset = result
					Engine.logger.info("Loaded external player model with {} mapped motions", result.clips.size)
				} else {
					Engine.logger.error("Failed to load external player model")
				}
			}
		}
	}

	private fun load(pack: PlayerPack, client: Minecraft): CompletableFuture<PlayerAsset> {
		val models = ModelRepository(
			resources = pack.modelSource,
			decodeExecutor = Util.ioPool(),
			textureIds = { _, _, index -> Engine.id("external/player/textures/$index") },
		)
		val motions = MotionRepository(pack.motionSource(), Util.ioPool())
		val modelFuture = models.load(modelId)
		val nativeFuture = CompletableFuture.supplyAsync(
			{ NativeAsset.open(pack.modelFile) },
			Util.ioPool(),
		)
		val motionFutures = pack.availableMotions().associateWith { state ->
			motions.load(Engine.id("external/player/animations/${state.name.lowercase()}"))
		}
		val allMotions = CompletableFuture.allOf(*motionFutures.values.toTypedArray()).thenApply {
			motionFutures.mapValues { it.value.join() }
		}
		val resources = modelFuture.thenApplyAsync({ model -> decodeResources(pack, model) }, Util.ioPool())
		return modelFuture.thenCombine(allMotions) { model, loadedMotions -> model to loadedMotions }
			.thenCombine(resources) { (model, loadedMotions), decodedResources ->
				Triple(model, loadedMotions, decodedResources)
			}
			.thenCombine(nativeFuture) { loaded, native -> loaded to native }
			.thenApplyAsync({ (loaded, native) ->
				val (model, loadedMotions, decodedResources) = loaded
				createAsset(client, model, loadedMotions, decodedResources, native)
			}, client)
			.whenComplete { _, error ->
				if (error != null && !nativeFuture.isCompletedExceptionally) nativeFuture.getNow(null)?.close()
				models.close()
				motions.close()
			}
	}

	private fun decodeResources(pack: PlayerPack, model: ModelAsset): DecodedResources {
		val textures = decodeTextures(pack, model)
		try {
			val automaticRig = HumanoidRig.from(model.skeleton)
			val profile = AvatarProfileStore.loadOrCreate(pack.profileFile, AvatarProfile.automatic(automaticRig))
			return DecodedResources(textures, profile)
		} catch (exception: Throwable) {
			textures.forEach { it.image.close() }
			throw exception
		}
	}

	private fun decodeTextures(pack: PlayerPack, model: ModelAsset): List<DecodedTexture> {
		val decoded = ArrayList<DecodedTexture>()
		try {
			model.model.texturePaths.forEachIndexed { index, reference ->
				try {
					decoded += DecodedTexture(model.textureIds[index], NativeImage.read(pack.readTexture(reference, MAX_TEXTURE_BYTES)))
				} catch (_: Throwable) {
					Engine.logger.warn("Unable to decode player texture {}", index)
				}
			}
			return decoded
		} catch (exception: Throwable) {
			decoded.forEach { it.image.close() }
			throw exception
		}
	}

	private fun createAsset(
		client: Minecraft,
		model: ModelAsset,
		motions: Map<MotionState, MotionAsset>,
		resources: DecodedResources,
		native: NativeAsset,
	): PlayerAsset {
		val textures = resources.textures
		val profile = resources.profile
		val registered = ArrayList<Identifier>()
		var mesh: GpuMesh? = null
		try {
			native.validate(model)
			textures.forEach { texture ->
				client.textureManager.register(texture.id, DynamicTexture({ "Engine player texture" }, texture.image))
				registered += texture.id
			}
			val backend = MCRenderBackend()
			mesh = backend.upload(model, native.renderMesh())
			val minimumY = model.model.geometry.positions.asSequence().drop(1).filterIndexed { index, _ -> index % 3 == 0 }.minOrNull()
				?: 0f
			val maximumY = model.model.geometry.positions.asSequence().drop(1).filterIndexed { index, _ -> index % 3 == 0 }.maxOrNull()
				?: minimumY + PLAYER_HEIGHT
			val height = (maximumY - minimumY).takeIf { it.isFinite() && it > 0.001f } ?: PLAYER_HEIGHT
			val rig = HumanoidRig.from(model.skeleton, profile.boneMappings)
			val sourceProfile = profile.sourceMotionHeight?.let(RetargetProfile::standardMmd)
			val groundHeight = minimumY - profile.groundOffset
			return PlayerAsset(
				render = RenderAsset(model, mesh, DeformableRegionExtractor.extract(model.model)),
				rig = rig,
				clips = motions.mapValues { AnimationClip.bind(it.value.motion, rig, sourceProfile) },
				textureIds = registered,
				scale = PLAYER_HEIGHT / height * profile.scaleMultiplier,
				groundHeight = groundHeight,
				profile = profile,
				native = native,
			)
		} catch (exception: Throwable) {
			native.close()
			mesh?.close()
			registered.forEach(client.textureManager::release)
			textures.filterNot { it.id in registered }.forEach { it.image.close() }
			throw exception
		}
	}

	private fun transform(x: Float, y: Float, z: Float, yawDegrees: Float, asset: PlayerAsset): Matrix4f = playerTransform(
		x,
		y,
		z,
		yawDegrees,
		asset.scale,
		asset.groundHeight,
		asset.profile.forwardYawDegrees,
	)

	internal fun playerTransform(
		x: Float,
		y: Float,
		z: Float,
		yawDegrees: Float,
		scale: Float,
		groundHeight: Float,
		forwardYawDegrees: Float = 0f,
	): Matrix4f = Matrix4f()
		.translation(x, y, z)
		.rotateY(Math.toRadians(-yawDegrees.toDouble()).toFloat())
		.rotateY(Math.toRadians(forwardYawDegrees.toDouble()).toFloat())
		.scale(scale)
		.translate(0f, -groundHeight, 0f)

	private fun removeEntry(entityId: Int) {
		val entry = entries.remove(entityId) ?: return
		WorldModels.remove(entry.modelId)
	}

	private fun clearPlayers() {
		entries.keys.toList().forEach(::removeEntry)
	}

	private fun shutdown() {
		reload(Minecraft.getInstance())
	}

	private data class PlayerEntry(
		val playerUuid: java.util.UUID,
		val modelId: Identifier,
		val instance: ModelInstance,
		val graph: AnimationGraph,
		val dynamics: PoseDynamics,
		val proceduralSolver: ProceduralPoseSolver,
		var previousBodyYaw: Float,
	)

	private data class PlayerAnimation(val base: MotionState, val upperBody: MotionState? = null)
	private data class PlayerVelocity(val planar: Float, val forward: Float, val side: Float)

	private data class DecodedTexture(val id: Identifier, val image: NativeImage)
	private data class DecodedResources(val textures: List<DecodedTexture>, val profile: AvatarProfile)

	private class NativeAsset private constructor(
		private val runtime: NativeRuntime,
		private val model: NativeRuntime.Model,
	) : AutoCloseable {
		fun validate(source: ModelAsset) {
			val info = model.info()
			val mesh = model.mesh()
			val renderMesh = model.renderMesh()
			require(info.vertexCount() == source.model.geometry.vertexCount) { "Compiled vertex count does not match PMX" }
			require(info.indexCount() == source.model.geometry.triangleIndices.size) { "Compiled index count does not match PMX" }
			require(info.textureCount() == source.model.texturePaths.size) { "Compiled texture count does not match PMX" }
			require(info.materialCount() == source.model.materials.size) { "Compiled material count does not match PMX" }
			require(info.boneCount() == source.model.bones.size) { "Compiled bone count does not match PMX" }
			require(info.morphCount() == source.model.morphs.size) { "Compiled morph count does not match PMX" }
			require(info.rigidBodyCount() == source.model.rigidBodies.size) { "Compiled rigid body count does not match PMX" }
			require(info.jointCount() == source.model.joints.size) { "Compiled joint count does not match PMX" }
			require(mesh.vertexCount() == info.vertexCount() && mesh.vertexStride() == 68) { "Compiled vertex layout is invalid" }
			require(mesh.indexCount() == info.indexCount() && mesh.indexStride() == Int.SIZE_BYTES) { "Compiled index layout is invalid" }
			require(mesh.vertices().byteSize() == mesh.vertexCount().toLong() * mesh.vertexStride()) { "Compiled vertex buffer is truncated" }
			require(mesh.indices().byteSize() == mesh.indexCount().toLong() * mesh.indexStride()) { "Compiled index buffer is truncated" }
			require(renderMesh.vertexCount() == info.vertexCount() && renderMesh.vertexStride() == 28) { "Native render vertex layout is invalid" }
			require(renderMesh.skinningStride() == 32) { "Native skinning layout is invalid" }
			require(renderMesh.indexCount() == info.indexCount() && renderMesh.indexStride() in setOf(2, 4)) { "Native render index layout is invalid" }
			source.model.bones.forEachIndexed { boneIndex, sourceBone ->
				val bone = model.bone(boneIndex)
				require(bone.name() == sourceBone.name && bone.englishName() == sourceBone.englishName) { "Native bone name does not match PMX at $boneIndex" }
				require(bone.parentIndex() == sourceBone.parentBoneIndex && bone.deformLayer() == sourceBone.deformLayer) { "Native bone hierarchy does not match PMX at $boneIndex" }
				require(bone.flags() == sourceBone.flags.bits) { "Native bone flags do not match PMX at $boneIndex" }
				require(bone.position().x() == sourceBone.position.x && bone.position().y() == sourceBone.position.y && bone.position().z() == sourceBone.position.z) { "Native bone position does not match PMX at $boneIndex" }
				val inheritance = sourceBone.inheritance
				require(bone.inheritanceIndex() == (inheritance?.sourceBoneIndex ?: -1)) { "Native bone inheritance source does not match PMX at $boneIndex" }
				if (inheritance != null) require(bone.inheritanceWeight() == inheritance.influence) { "Native bone inheritance weight does not match PMX at $boneIndex" }
				val ik = sourceBone.inverseKinematics
				require(bone.ikLinkCount() == (ik?.links?.size ?: 0)) { "Native IK link count does not match PMX at $boneIndex" }
				if (ik != null) {
					require(bone.ikTargetIndex() == ik.targetBoneIndex && bone.ikIterationCount() == ik.iterationCount && bone.ikAngleLimit() == ik.angleLimit) { "Native IK constraint does not match PMX at $boneIndex" }
					ik.links.forEachIndexed { linkIndex, sourceLink ->
						val link = model.ikLink(boneIndex, linkIndex)
						require(link.boneIndex() == sourceLink.boneIndex && link.limited() == (sourceLink.angleLimits != null)) { "Native IK link does not match PMX at $boneIndex:$linkIndex" }
					}
				}
			}
			model.createPose().use { pose ->
				pose.evaluate()
				val matrices = pose.matrices()
				require(matrices.boneCount() == info.boneCount() && matrices.matrixStride() == 16) { "Native pose matrix layout is invalid" }
				require(matrices.data().byteSize() == info.boneCount().toLong() * 16 * Float.SIZE_BYTES) { "Native pose matrix buffer is truncated" }
			}
		}

		fun renderMesh(): NativeRuntime.RenderMesh = model.renderMesh()

		override fun close() {
			model.close()
			runtime.close()
		}

		companion object {
			fun open(source: java.nio.file.Path): NativeAsset {
				val runtime = NativeRuntime.open()
				try {
					return NativeAsset(runtime, runtime.loadPmx(source))
				} catch (failure: Throwable) {
					runtime.close()
					throw failure
				}
			}
		}
	}

	private data class PlayerAsset(
		val render: RenderAsset<GpuMesh>,
		val rig: HumanoidRig,
		val clips: Map<MotionState, AnimationClip>,
		val textureIds: List<Identifier>,
		val scale: Float,
		val groundHeight: Float,
		val profile: AvatarProfile,
		val native: NativeAsset,
	) {
		fun close(client: Minecraft) {
			render.renderData.close()
			textureIds.forEach(client.textureManager::release)
			native.close()
		}
	}
}
