package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxIkAngleLimits
import com.micheanl.libmmd.format.pmx.PmxVector3
import org.joml.Matrix4f
import org.joml.Vector3f

data class BoneInheritance(
	val sourceIndex: Int,
	val rotation: Boolean,
	val translation: Boolean,
	val influence: Float,
)

data class IkAngleLimits(
	val minimum: Vector3f,
	val maximum: Vector3f,
)

data class IkLink(
	val boneIndex: Int,
	val angleLimits: IkAngleLimits?,
)

data class IkConstraint(
	val targetBoneIndex: Int,
	val iterationCount: Int,
	val angleLimit: Float,
	val links: List<IkLink>,
)

data class Bone(
	val name: String,
	val englishName: String,
	val parentIndex: Int,
	val deformLayer: Int,
	val bindTranslation: PmxVector3,
	val inheritance: BoneInheritance?,
	val inverseKinematics: IkConstraint?,
)

class Skeleton private constructor(
	val bones: List<Bone>,
	internal val evaluationOrder: IntArray,
	internal val deformationOrder: IntArray,
	internal val inverseBindMatrices: Array<Matrix4f>,
) {
	private val boneIndices = buildMap {
		bones.forEachIndexed { index, bone ->
			putIfAbsent(bone.name, index)
			if (bone.englishName.isNotEmpty()) putIfAbsent(bone.englishName, index)
		}
	}

	val boneCount: Int
		get() = bones.size

	fun indexOf(name: String): Int? = boneIndices[name]

	companion object {
		fun from(bones: List<PmxBone>): Skeleton {
			val converted = bones.mapIndexed { index, bone -> convertBone(index, bone, bones) }
			val order = evaluationOrder(converted)
			validateDependencies(converted)
			val globalBind = Array(converted.size) { Matrix4f() }
			for (index in order) {
				val bone = converted[index]
				val local = Matrix4f().translation(
					bone.bindTranslation.x,
					bone.bindTranslation.y,
					bone.bindTranslation.z,
				)
				globalBind[index] = if (bone.parentIndex < 0) {
					local
				} else {
					Matrix4f(globalBind[bone.parentIndex]).mul(local)
				}
			}
			return Skeleton(
				bones = converted,
				evaluationOrder = order,
				deformationOrder = converted.indices.sortedWith(
					compareBy<Int> { converted[it].deformLayer }.thenBy { it },
				).toIntArray(),
				inverseBindMatrices = Array(converted.size) { Matrix4f(globalBind[it]).invert() },
			)
		}

		private fun convertBone(index: Int, bone: PmxBone, bones: List<PmxBone>): Bone {
			val parentPosition = bones.getOrNull(bone.parentBoneIndex)?.position
			val inheritance = bone.inheritance?.let {
				require(it.sourceBoneIndex in bones.indices) {
					"Bone $index inheritance source ${it.sourceBoneIndex} is outside 0 until ${bones.size}"
				}
				require(it.influence.isFinite()) { "Bone $index inheritance influence must be finite" }
				BoneInheritance(
					sourceIndex = it.sourceBoneIndex,
					rotation = bone.flags.inheritsRotation,
					translation = bone.flags.inheritsTranslation,
					influence = it.influence,
				)
			}
			val ik = bone.inverseKinematics?.let { source ->
				require(source.targetBoneIndex in bones.indices) {
					"Bone $index IK target ${source.targetBoneIndex} is outside 0 until ${bones.size}"
				}
				require(source.iterationCount >= 0) { "Bone $index IK iteration count must be non-negative" }
				require(source.angleLimit.isFinite() && source.angleLimit >= 0f) {
					"Bone $index IK angle limit must be finite and non-negative"
				}
				IkConstraint(
					targetBoneIndex = source.targetBoneIndex,
					iterationCount = source.iterationCount,
					angleLimit = source.angleLimit,
					links = source.links.map { link ->
						require(link.boneIndex in bones.indices) {
							"Bone $index IK link ${link.boneIndex} is outside 0 until ${bones.size}"
						}
						IkLink(link.boneIndex, link.angleLimits?.let(::convertLimits))
					},
				)
			}
			return Bone(
				name = bone.name,
				englishName = bone.englishName,
				parentIndex = bone.parentBoneIndex,
				deformLayer = bone.deformLayer,
				bindTranslation = PmxVector3(
					x = bone.position.x - (parentPosition?.x ?: 0f),
					y = bone.position.y - (parentPosition?.y ?: 0f),
					z = -bone.position.z + (parentPosition?.z ?: 0f),
				),
				inheritance = inheritance,
				inverseKinematics = ik,
			)
		}

		private fun convertLimits(source: PmxIkAngleLimits): IkAngleLimits = IkAngleLimits(
			minimum = Vector3f(-source.maximum.x, -source.maximum.y, source.minimum.z),
			maximum = Vector3f(-source.minimum.x, -source.minimum.y, source.maximum.z),
		)

		private fun evaluationOrder(bones: List<Bone>): IntArray {
			val states = ByteArray(bones.size)
			val order = ArrayList<Int>(bones.size)

			fun visit(index: Int) {
				when (states[index].toInt()) {
					1 -> throw IllegalArgumentException("Bone hierarchy contains a cycle at index $index")
					2 -> return
				}
				states[index] = 1
				val parentIndex = bones[index].parentIndex
				require(parentIndex in -1 until bones.size) {
					"Bone $index parent $parentIndex is outside -1 until ${bones.size}"
				}
				if (parentIndex >= 0) visit(parentIndex)
				states[index] = 2
				order += index
			}

			bones.indices.forEach(::visit)
			return order.toIntArray()
		}

		private fun validateDependencies(bones: List<Bone>) {
			val states = ByteArray(bones.size)
			fun visit(index: Int) {
				when (states[index].toInt()) {
					1 -> throw IllegalArgumentException("Bone inheritance contains a cycle at index $index")
					2 -> return
				}
				states[index] = 1
				bones[index].inheritance?.let { visit(it.sourceIndex) }
				states[index] = 2
			}
			bones.indices.forEach(::visit)
		}
	}
}
