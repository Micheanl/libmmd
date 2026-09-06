package com.micheanl.libmmd.animation

import org.joml.Vector3f
import kotlin.math.max

enum class HumanoidJoint(vararg names: String) {
	ROOT("全ての親", "motherbone", "master", "root"),
	CENTER("センター", "center", "centre"),
	HIPS("下半身", "lowerbody", "hips", "pelvis"),
	SPINE("上半身", "upperbody", "spine"),
	CHEST("上半身2", "upperbody2", "chest", "upperchest"),
	NECK("首", "neck"),
	HEAD("頭", "head"),
	LEFT_SHOULDER("左肩P", "左肩", "shoulderp_l", "shoulder_l", "leftshoulder", "lshoulder", "j_bip_l_shoulder"),
	LEFT_UPPER_ARM("左腕", "arm_l", "upperarm_l", "leftarm", "leftupperarm", "j_bip_l_upperarm"),
	LEFT_LOWER_ARM("左ひじ", "elbow_l", "lowerarm_l", "forearm_l", "leftforearm", "leftlowerarm", "j_bip_l_lowerarm"),
	LEFT_HAND("左手首", "wrist_l", "hand_l", "lefthand", "leftwrist", "j_bip_l_hand"),
	RIGHT_SHOULDER("右肩P", "右肩", "shoulderp_r", "shoulder_r", "rightshoulder", "rshoulder", "j_bip_r_shoulder"),
	RIGHT_UPPER_ARM("右腕", "arm_r", "upperarm_r", "rightarm", "rightupperarm", "j_bip_r_upperarm"),
	RIGHT_LOWER_ARM("右ひじ", "elbow_r", "lowerarm_r", "forearm_r", "rightforearm", "rightlowerarm", "j_bip_r_lowerarm"),
	RIGHT_HAND("右手首", "wrist_r", "hand_r", "righthand", "rightwrist", "j_bip_r_hand"),
	LEFT_UPPER_LEG("左足", "leg_l", "thigh_l", "leftleg", "leftupleg", "leftthigh", "j_bip_l_upperleg"),
	LEFT_LOWER_LEG("左ひざ", "knee_l", "calf_l", "lowerleg_l", "leftknee", "leftlowerleg", "j_bip_l_lowerleg"),
	LEFT_FOOT("左足首", "ankle_l", "foot_l", "leftankle", "leftfoot", "j_bip_l_foot"),
	LEFT_TOES("左つま先", "toe_l", "toes_l", "lefttoe", "lefttoes", "j_bip_l_toe"),
	LEFT_FOOT_IK("左足ＩＫ", "左足IK", "legik_l", "footik_l", "leftfootik"),
	RIGHT_UPPER_LEG("右足", "leg_r", "thigh_r", "rightleg", "rightupleg", "rightthigh", "j_bip_r_upperleg"),
	RIGHT_LOWER_LEG("右ひざ", "knee_r", "calf_r", "lowerleg_r", "rightknee", "rightlowerleg", "j_bip_r_lowerleg"),
	RIGHT_FOOT("右足首", "ankle_r", "foot_r", "rightankle", "rightfoot", "j_bip_r_foot"),
	RIGHT_TOES("右つま先", "toe_r", "toes_r", "righttoe", "righttoes", "j_bip_r_toe"),
	RIGHT_FOOT_IK("右足ＩＫ", "右足IK", "legik_r", "footik_r", "rightfootik");

	internal val aliases = names.map(::normalizeBoneName)

	companion object {
		fun resolve(name: String): HumanoidJoint? {
			val normalized = normalizeBoneName(name)
			return entries.firstOrNull { joint -> normalized in joint.aliases }
				?: entries.firstOrNull { joint ->
					joint.aliases.any { alias -> alias.length >= 5 && normalized.endsWith(alias) }
				}
		}
	}
}

class HumanoidRig private constructor(
	val skeleton: Skeleton,
	private val indices: Map<HumanoidJoint, Int>,
	private val restPositions: Array<Vector3f>,
) {
	val mappedJointCount: Int
		get() = indices.size

	val height: Float = calculateHeight()

	operator fun get(joint: HumanoidJoint): Int? = indices[joint]

	fun require(joint: HumanoidJoint): Int = requireNotNull(indices[joint]) { "Humanoid joint ${joint.name} is not mapped" }

	fun contains(joint: HumanoidJoint): Boolean = joint in indices

	fun restPosition(joint: HumanoidJoint, destination: Vector3f = Vector3f()): Vector3f =
		destination.set(restPositions[require(joint)])

	fun direction(joint: HumanoidJoint, destination: Vector3f = Vector3f()): Vector3f {
		val child = PRIMARY_CHILD[joint]?.let(indices::get)
		val source = indices[joint]
		if (source == null || child == null) return destination.zero()
		destination.set(restPositions[child]).sub(restPositions[source])
		return if (destination.lengthSquared() > 1e-8f) destination.normalize() else destination.zero()
	}

	fun length(joint: HumanoidJoint): Float {
		val child = PRIMARY_CHILD[joint]?.let(indices::get) ?: return 0f
		val source = indices[joint] ?: return 0f
		return restPositions[source].distance(restPositions[child])
	}

	fun scaleComparedTo(referenceHeight: Float): Float {
		require(referenceHeight.isFinite() && referenceHeight > 0f) { "referenceHeight must be finite and positive" }
		return height / referenceHeight
	}

	private fun calculateHeight(): Float {
		val headY = indices[HumanoidJoint.HEAD]?.let { restPositions[it].y }
		val footY = listOfNotNull(indices[HumanoidJoint.LEFT_FOOT], indices[HumanoidJoint.RIGHT_FOOT])
			.minOfOrNull { restPositions[it].y }
		if (headY != null && footY != null && headY > footY) return headY - footY
		if (restPositions.isEmpty()) return 1f
		val minimum = restPositions.minOf { it.y }
		val maximum = restPositions.maxOf { it.y }
		return max(maximum - minimum, 0.001f)
	}

	companion object {
		fun from(
			skeleton: Skeleton,
			boneMappings: Map<HumanoidJoint, String> = emptyMap(),
		): HumanoidRig {
			val positions = Array(skeleton.boneCount) { Vector3f() }
			for (index in skeleton.evaluationOrder) {
				val bone = skeleton.bones[index]
				positions[index].set(bone.bindTranslation.x, bone.bindTranslation.y, bone.bindTranslation.z)
				if (bone.parentIndex >= 0) positions[index].add(positions[bone.parentIndex])
			}
			val indices = HumanoidJoint.entries.mapNotNull { joint ->
				findBestMatch(skeleton, joint)?.let { joint to it }
			}.toMap().toMutableMap()
			boneMappings.forEach { (joint, name) ->
				indices[joint] = requireNotNull(skeleton.indexOf(name)) {
					"Configured bone '$name' for ${joint.name} does not exist"
				}
			}
			return HumanoidRig(skeleton, indices, positions)
		}

		private fun findBestMatch(skeleton: Skeleton, joint: HumanoidJoint): Int? = skeleton.bones.indices
			.mapNotNull { index -> score(skeleton.bones[index], joint)?.let { score -> index to score } }
			.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
			.firstOrNull()
			?.first

		private fun score(bone: Bone, joint: HumanoidJoint): Int? {
			val names = listOf(bone.name, bone.englishName).filter(String::isNotBlank)
			val normalized = names.map(::normalizeBoneName)
			joint.aliases.forEachIndexed { aliasIndex, alias ->
				if (alias in normalized) return 1000 - aliasIndex
			}
			if (normalized.any { name -> AUXILIARY_MARKERS.any(name::contains) }) return null
			joint.aliases.forEachIndexed { aliasIndex, alias ->
				if (alias.length >= 5 && normalized.any { it.endsWith(alias) }) return 500 - aliasIndex
			}
			return null
		}

		private val AUXILIARY_MARKERS = listOf("twist", "helper", "dummy", "control", "捩", "補助")

		private val PRIMARY_CHILD = mapOf(
			HumanoidJoint.ROOT to HumanoidJoint.CENTER,
			HumanoidJoint.CENTER to HumanoidJoint.HIPS,
			HumanoidJoint.HIPS to HumanoidJoint.SPINE,
			HumanoidJoint.SPINE to HumanoidJoint.CHEST,
			HumanoidJoint.CHEST to HumanoidJoint.NECK,
			HumanoidJoint.NECK to HumanoidJoint.HEAD,
			HumanoidJoint.LEFT_SHOULDER to HumanoidJoint.LEFT_UPPER_ARM,
			HumanoidJoint.LEFT_UPPER_ARM to HumanoidJoint.LEFT_LOWER_ARM,
			HumanoidJoint.LEFT_LOWER_ARM to HumanoidJoint.LEFT_HAND,
			HumanoidJoint.RIGHT_SHOULDER to HumanoidJoint.RIGHT_UPPER_ARM,
			HumanoidJoint.RIGHT_UPPER_ARM to HumanoidJoint.RIGHT_LOWER_ARM,
			HumanoidJoint.RIGHT_LOWER_ARM to HumanoidJoint.RIGHT_HAND,
			HumanoidJoint.LEFT_UPPER_LEG to HumanoidJoint.LEFT_LOWER_LEG,
			HumanoidJoint.LEFT_LOWER_LEG to HumanoidJoint.LEFT_FOOT,
			HumanoidJoint.LEFT_FOOT to HumanoidJoint.LEFT_TOES,
			HumanoidJoint.RIGHT_UPPER_LEG to HumanoidJoint.RIGHT_LOWER_LEG,
			HumanoidJoint.RIGHT_LOWER_LEG to HumanoidJoint.RIGHT_FOOT,
			HumanoidJoint.RIGHT_FOOT to HumanoidJoint.RIGHT_TOES,
		)
	}
}

internal fun normalizeBoneName(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
