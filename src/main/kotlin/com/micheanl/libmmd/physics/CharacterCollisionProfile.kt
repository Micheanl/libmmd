package com.micheanl.libmmd.physics

import com.micheanl.libmmd.format.pmx.PmxModel
import com.micheanl.libmmd.format.pmx.PmxRigidBody
import kotlin.math.abs

internal class CharacterCollisionProfile(
	private val sources: List<PmxRigidBody>,
	boneNames: List<String>,
	private val parentBones: List<Int> = List(boneNames.size) { -1 },
	private val configuration: PhysicsAssetConfiguration = PhysicsAssetConfiguration(),
) {
	constructor(model: PmxModel, configuration: PhysicsAssetConfiguration = PhysicsAssetConfiguration()) : this(
		model.rigidBodies,
		model.rigidBodies.map { body -> model.bones.getOrNull(body.boneIndex)?.name.orEmpty() },
		model.bones.map { it.parentBoneIndex },
		configuration,
	)

	private val roles = sources.mapIndexed { index, body -> classify(body, boneNames.getOrElse(index) { "" }) }
	private val legs = sources.mapIndexed { index, body -> isLeg(body, boneNames.getOrElse(index) { "" }) }
	private val bodyGroups = groupBits(PhysicsBodyRole.BODY)
	private val skirtGroups = groupBits(PhysicsBodyRole.SKIRT)
	private val hairGroups = groupBits(PhysicsBodyRole.HAIR)
	private val chains = buildChains()

	fun role(rigidBodyIndex: Int): PhysicsBodyRole = roles[rigidBodyIndex]

	fun isLeg(rigidBodyIndex: Int): Boolean = legs[rigidBodyIndex]

	fun mask(rigidBodyIndex: Int): Int {
		val source = sources[rigidBodyIndex]
		val authored = source.collisionExclusionMask.inv() and COLLISION_GROUP_MASK
		return when (roles[rigidBodyIndex]) {
			PhysicsBodyRole.BODY -> authored or skirtGroups or hairGroups
			PhysicsBodyRole.SKIRT -> authored or bodyGroups or if (configuration.skirtSelfCollision) skirtGroups else 0
			PhysicsBodyRole.HAIR -> authored or bodyGroups or if (configuration.hairSelfCollision) hairGroups else 0
			PhysicsBodyRole.ACCESSORY, PhysicsBodyRole.OTHER -> authored
		}
	}

	fun metadata(rigidBodyIndex: Int, rigId: Int): Int {
		val role = roles[rigidBodyIndex]
		if (role == PhysicsBodyRole.SKIRT && !configuration.skirtSelfCollision) return 0
		if (role == PhysicsBodyRole.HAIR && !configuration.hairSelfCollision) return 0
		if (role != PhysicsBodyRole.SKIRT && role != PhysicsBodyRole.HAIR) return 0
		val chain = chains[rigidBodyIndex]
		return CollisionMetadata.encode(rigId, role, chain.root, chain.depth)
	}

	private fun groupBits(role: PhysicsBodyRole): Int = sources.indices
		.filter { roles[it] == role }
		.fold(0) { bits, index -> bits or (1 shl sources[index].collisionGroup) }

	private fun buildChains(): List<Chain> {
		val bodyByBone = sources.indices.groupBy { sources[it].boneIndex }
		return sources.indices.map { index ->
			val role = roles[index]
			var bone = sources[index].boneIndex
			var root = bone
			var depth = 0
			val visited = HashSet<Int>()
			while (bone >= 0 && visited.add(bone)) {
				val parent = parentBones.getOrElse(bone) { -1 }
				val parentHasRole = bodyByBone[parent].orEmpty().any { roles[it] == role }
				if (!parentHasRole) break
				root = parent
				bone = parent
				depth++
			}
			Chain(root, depth)
		}
	}

	private fun classify(body: PmxRigidBody, boneName: String): PhysicsBodyRole {
		val identity = "${body.name} ${body.englishName} $boneName".lowercase()
		return when {
			SKIRT_NAMES.any(identity::contains) -> PhysicsBodyRole.SKIRT
			HAIR_NAMES.any(identity::contains) -> PhysicsBodyRole.HAIR
			ACCESSORY_NAMES.any(identity::contains) -> PhysicsBodyRole.ACCESSORY
			BODY_NAMES.any(identity::contains) -> PhysicsBodyRole.BODY
			else -> PhysicsBodyRole.OTHER
		}
	}

	private fun isLeg(body: PmxRigidBody, boneName: String): Boolean {
		val identity = "${body.name} ${body.englishName} $boneName".lowercase()
		return LEG_NAMES.any(identity::contains)
	}

	private data class Chain(val root: Int, val depth: Int)

	private companion object {
		const val COLLISION_GROUP_MASK = 0xffff
		val SKIRT_NAMES = listOf("skirt", "dress", "qunzi", "スカート", "裙")
		val HAIR_NAMES = listOf("hair", "bang", "twin tail", "ponytail", "weiba", "髪", "发", "辮", "辫")
		val ACCESSORY_NAMES = listOf("ribbon", "bow", "apron", "accessory", "飾", "饰", "リボン")
		val LEG_NAMES = listOf("足", "ひざ", "膝", "leg", "thigh", "knee", "calf")
		val BODY_NAMES = listOf(
			"bodyproxy",
			"下半身",
			"上半身",
			"首",
			"頭",
			"头",
			"肩",
			"腕",
			"ひじ",
			"肘",
			"左足",
			"右足",
			"左ひざ",
			"右ひざ",
			"lowerbody",
			"upperbody",
			"spine",
			"chest",
			"neck",
			"head",
			"shoulder",
			"arm",
			"elbow",
			"leftleg",
			"rightleg",
			"leftknee",
			"rightknee",
		)
	}
}

internal object CollisionMetadata {
	private const val DEPTH_MASK = 0xff
	private const val ROOT_MASK = 0xfff
	private const val ROLE_MASK = 0xf
	private const val RIG_MASK = 0xff

	fun encode(rigId: Int, role: PhysicsBodyRole, root: Int, depth: Int): Int =
		((rigId and RIG_MASK) shl 24) or
			(((role.ordinal + 1) and ROLE_MASK) shl 20) or
			(((root + 1) and ROOT_MASK) shl 8) or
			(depth and DEPTH_MASK)

	fun suppress(first: Int, second: Int): Boolean {
		if (first == 0 || second == 0) return false
		val sameRig = (first ushr 24 and RIG_MASK) == (second ushr 24 and RIG_MASK)
		val sameRole = (first ushr 20 and ROLE_MASK) == (second ushr 20 and ROLE_MASK)
		val sameRoot = (first ushr 8 and ROOT_MASK) == (second ushr 8 and ROOT_MASK)
		return sameRig && sameRole && sameRoot && abs((first and DEPTH_MASK) - (second and DEPTH_MASK)) <= 1
	}
}
