package com.micheanl.libmmd.animation

import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import org.joml.Vector3fc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos

class Pose(val skeleton: Skeleton) {
	private val translations = Array(skeleton.boneCount) { Vector3f() }
	private val rotations = Array(skeleton.boneCount) { Quaternionf() }
	private val ikRotations = Array(skeleton.boneCount) { Quaternionf() }
	private val evaluatedTranslations = Array(skeleton.boneCount) { Vector3f() }
	private val evaluatedRotations = Array(skeleton.boneCount) { Quaternionf() }
	private val localMatrices = Array(skeleton.boneCount) { Matrix4f() }
	private val globalMatrices = Array(skeleton.boneCount) { Matrix4f() }
	private val skinMatrices = Array(skeleton.boneCount) { Matrix4f() }
	private val physicsTransforms = Array(skeleton.boneCount) { Matrix4f() }
	private val physicsModes = ByteArray(skeleton.boneCount)
	private val ikEnabled = BooleanArray(skeleton.boneCount) { true }
	private val localStates = ByteArray(skeleton.boneCount)
	private var dirty = true

	fun setTranslation(boneIndex: Int, translation: Vector3fc): Pose = apply {
		translations[boneIndex].set(translation)
		dirty = true
	}

	fun setRotation(boneIndex: Int, rotation: Quaternionfc): Pose = apply {
		rotations[boneIndex].set(rotation).normalize()
		dirty = true
	}

	fun translation(boneIndex: Int, destination: Vector3f = Vector3f()): Vector3f =
		destination.set(translations[boneIndex])

	fun rotation(boneIndex: Int, destination: Quaternionf = Quaternionf()): Quaternionf =
		destination.set(rotations[boneIndex])

	fun isIkEnabled(boneIndex: Int): Boolean = ikEnabled[boneIndex]

	fun setIkEnabled(boneIndex: Int, enabled: Boolean): Pose = apply {
		require(skeleton.bones[boneIndex].inverseKinematics != null) { "Bone $boneIndex has no IK constraint" }
		if (ikEnabled[boneIndex] != enabled) {
			ikEnabled[boneIndex] = enabled
			dirty = true
		}
	}

	fun reset(boneIndex: Int): Pose = apply {
		translations[boneIndex].zero()
		rotations[boneIndex].identity()
		ikRotations[boneIndex].identity()
		ikEnabled[boneIndex] = true
		dirty = true
	}

	fun reset(): Pose = apply {
		translations.forEach(Vector3f::zero)
		rotations.forEach(Quaternionf::identity)
		ikRotations.forEach(Quaternionf::identity)
		ikEnabled.fill(true)
		physicsModes.fill(PHYSICS_NONE)
		dirty = true
	}

	fun copyFrom(source: Pose): Pose = apply {
		require(source.skeleton === skeleton) { "Poses use different skeletons" }
		for (index in translations.indices) {
			translations[index].set(source.translations[index])
			rotations[index].set(source.rotations[index])
			ikEnabled[index] = source.ikEnabled[index]
		}
		physicsModes.fill(PHYSICS_NONE)
		dirty = true
	}

	fun blend(from: Pose, to: Pose, weight: Float): Pose = apply {
		require(from.skeleton === skeleton && to.skeleton === skeleton) { "Poses use different skeletons" }
		require(weight.isFinite()) { "Blend weight must be finite" }
		val amount = weight.coerceIn(0f, 1f)
		for (index in translations.indices) {
			translations[index].set(from.translations[index]).lerp(to.translations[index], amount)
			rotations[index].set(from.rotations[index]).slerp(to.rotations[index], amount).normalize()
			ikEnabled[index] = if (amount < 0.5f) from.ikEnabled[index] else to.ikEnabled[index]
		}
		physicsModes.fill(PHYSICS_NONE)
		dirty = true
	}

	fun blend(from: Pose, to: Pose, mask: BoneMask, weight: Float): Pose = apply {
		require(from.skeleton === skeleton && to.skeleton === skeleton && mask.skeleton === skeleton) {
			"Poses and bone mask use different skeletons"
		}
		require(weight.isFinite()) { "Blend weight must be finite" }
		val amount = weight.coerceIn(0f, 1f)
		for (index in translations.indices) {
			val boneAmount = amount * mask[index]
			translations[index].set(from.translations[index]).lerp(to.translations[index], boneAmount)
			rotations[index].set(from.rotations[index]).slerp(to.rotations[index], boneAmount).normalize()
			ikEnabled[index] = if (boneAmount < 0.5f) from.ikEnabled[index] else to.ikEnabled[index]
		}
		physicsModes.fill(PHYSICS_NONE)
		dirty = true
	}

	fun rotate(boneIndex: Int, rotation: Quaternionfc): Pose = apply {
		require(rotation.isFinite) { "Rotation must be finite" }
		rotations[boneIndex].mul(rotation).normalize()
		dirty = true
	}

	fun translate(boneIndex: Int, x: Float, y: Float, z: Float): Pose = apply {
		require(x.isFinite() && y.isFinite() && z.isFinite()) { "Translation must be finite" }
		translations[boneIndex].add(x, y, z)
		dirty = true
	}

	fun clearPhysics(): Pose = apply {
		if (physicsModes.any { it != PHYSICS_NONE }) {
			physicsModes.fill(PHYSICS_NONE)
			dirty = true
		}
	}

	fun setPhysicsTransform(boneIndex: Int, transform: Matrix4fc, rotationOnly: Boolean = false): Pose = apply {
		require(transform.isFinite) { "Physics transform must be finite" }
		physicsTransforms[boneIndex].set(transform)
		physicsModes[boneIndex] = if (rotationOnly) PHYSICS_ROTATION else PHYSICS_FULL
		dirty = true
	}

	fun matrix(boneIndex: Int, destination: Matrix4f = Matrix4f()): Matrix4f {
		evaluate()
		return destination.set(skinMatrices[boneIndex])
	}

	fun globalMatrix(boneIndex: Int, destination: Matrix4f = Matrix4f()): Matrix4f {
		evaluate()
		return destination.set(globalMatrices[boneIndex])
	}

	internal fun addTranslation(boneIndex: Int, x: Float, y: Float, z: Float) {
		translations[boneIndex].add(x, y, z)
		dirty = true
	}

	internal fun multiplyRotation(boneIndex: Int, rotation: Quaternionfc) {
		rotations[boneIndex].mul(rotation).normalize()
		dirty = true
	}

	internal fun writeMatrices(destination: ByteBuffer, byteOffset: Int) {
		evaluate()
		skinMatrices.forEachIndexed { index, matrix ->
			matrix.get(byteOffset + index * MATRIX_BYTES, destination)
		}
	}

	internal fun paletteBytes(): ByteBuffer {
		val size = (skeleton.boneCount + 1) * MATRIX_BYTES
		val bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
		Matrix4f().get(0, bytes)
		writeMatrices(bytes, MATRIX_BYTES)
		bytes.limit(size)
		return bytes
	}

	private fun evaluate() {
		if (!dirty) return
		ikRotations.forEach(Quaternionf::identity)
		rebuildTransforms()
		for (boneIndex in skeleton.deformationOrder) {
			val ik = skeleton.bones[boneIndex].inverseKinematics ?: continue
			if (ikEnabled[boneIndex]) solveIk(boneIndex, ik)
		}
		applyPhysicsTransforms()
		for (index in skeleton.evaluationOrder) {
			skinMatrices[index].set(globalMatrices[index]).mul(skeleton.inverseBindMatrices[index])
		}
		dirty = false
	}

	private fun rebuildTransforms() {
		localStates.fill(0)
		skeleton.deformationOrder.forEach(::evaluateLocal)
		for (index in skeleton.evaluationOrder) {
			val bone = skeleton.bones[index]
			val translation = evaluatedTranslations[index]
			val local = localMatrices[index]
				.identity()
				.translation(
					bone.bindTranslation.x + translation.x,
					bone.bindTranslation.y + translation.y,
					bone.bindTranslation.z + translation.z,
				)
				.rotate(evaluatedRotations[index])
			globalMatrices[index].set(
				if (bone.parentIndex < 0) local else Matrix4f(globalMatrices[bone.parentIndex]).mul(local),
			)
		}
	}

	private fun applyPhysicsTransforms() {
		if (physicsModes.all { it == PHYSICS_NONE }) return
		val position = Vector3f()
		val rotation = Quaternionf()
		for (index in skeleton.evaluationOrder) {
			val parentIndex = skeleton.bones[index].parentIndex
			globalMatrices[index].set(
				if (parentIndex < 0) localMatrices[index] else Matrix4f(globalMatrices[parentIndex]).mul(localMatrices[index]),
			)
			when (physicsModes[index]) {
				PHYSICS_FULL -> globalMatrices[index].set(physicsTransforms[index])
				PHYSICS_ROTATION -> {
					globalMatrices[index].getTranslation(position)
					physicsTransforms[index].getUnnormalizedRotation(rotation).normalize()
					globalMatrices[index].identity().translation(position).rotate(rotation)
				}
			}
		}
	}

	private fun evaluateLocal(index: Int) {
		when (localStates[index].toInt()) {
			1 -> error("Bone inheritance cycle reached at runtime")
			2 -> return
		}
		localStates[index] = 1
		val inheritance = skeleton.bones[index].inheritance
		if (inheritance == null) {
			evaluatedTranslations[index].set(translations[index])
			evaluatedRotations[index].set(rotations[index]).mul(ikRotations[index]).normalize()
		} else {
			evaluateLocal(inheritance.sourceIndex)
			evaluatedTranslations[index].set(translations[index])
			if (inheritance.translation) {
				evaluatedTranslations[index].fma(inheritance.influence, evaluatedTranslations[inheritance.sourceIndex])
			}
			if (inheritance.rotation) {
				val inherited = Quaternionf().slerp(evaluatedRotations[inheritance.sourceIndex], inheritance.influence)
				evaluatedRotations[index].set(inherited).mul(rotations[index]).mul(ikRotations[index]).normalize()
			} else {
				evaluatedRotations[index].set(rotations[index]).mul(ikRotations[index]).normalize()
			}
		}
		localStates[index] = 2
	}

	private fun solveIk(controllerIndex: Int, ik: IkConstraint) {
		if (ik.iterationCount == 0 || ik.links.isEmpty()) return
		val goal = Vector3f()
		val effector = Vector3f()
		val localGoal = Vector3f()
		val localEffector = Vector3f()
		val axis = Vector3f()
		val inverse = Matrix4f()
		val delta = Quaternionf()
		val euler = Vector3f()

		repeat(ik.iterationCount) {
			globalMatrices[controllerIndex].getTranslation(goal)
			globalMatrices[ik.targetBoneIndex].getTranslation(effector)
			if (goal.distanceSquared(effector) <= IK_EPSILON_SQUARED) return
			for (link in ik.links) {
				inverse.set(globalMatrices[link.boneIndex]).invert()
				inverse.transformPosition(goal, localGoal)
				inverse.transformPosition(effector, localEffector)
				if (localGoal.lengthSquared() <= IK_EPSILON_SQUARED || localEffector.lengthSquared() <= IK_EPSILON_SQUARED) {
					continue
				}
				localGoal.normalize()
				localEffector.normalize()
				val angle = acos(localEffector.dot(localGoal).coerceIn(-1f, 1f)).coerceAtMost(ik.angleLimit)
				if (angle <= IK_ANGLE_EPSILON) continue
				localEffector.cross(localGoal, axis)
				if (axis.lengthSquared() <= IK_EPSILON_SQUARED) continue
				delta.fromAxisAngleRad(axis.normalize(), angle)
				ikRotations[link.boneIndex].mul(delta).normalize()
				link.angleLimits?.let { limits ->
					ikRotations[link.boneIndex].getEulerAnglesXYZ(euler)
					euler.set(
						euler.x.coerceIn(limits.minimum.x, limits.maximum.x),
						euler.y.coerceIn(limits.minimum.y, limits.maximum.y),
						euler.z.coerceIn(limits.minimum.z, limits.maximum.z),
					)
					ikRotations[link.boneIndex].rotationXYZ(euler.x, euler.y, euler.z)
				}
				rebuildTransforms()
				globalMatrices[ik.targetBoneIndex].getTranslation(effector)
				if (goal.distanceSquared(effector) <= IK_EPSILON_SQUARED) return
			}
		}
	}

	companion object {
		internal const val MATRIX_BYTES: Int = 16 * Float.SIZE_BYTES
		private const val IK_EPSILON_SQUARED = 1.0e-8f
		private const val IK_ANGLE_EPSILON = 1.0e-6f
		private const val PHYSICS_NONE: Byte = 0
		private const val PHYSICS_FULL: Byte = 1
		private const val PHYSICS_ROTATION: Byte = 2
	}
}
