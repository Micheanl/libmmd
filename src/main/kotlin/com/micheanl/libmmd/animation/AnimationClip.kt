package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.vmd.VmdBoneKeyframe
import com.micheanl.libmmd.format.vmd.VmdMotion
import org.joml.Quaternionf
import org.joml.Vector3f

class AnimationClip private constructor(
	val name: String,
	val skeleton: Skeleton,
	private val tracks: List<BoneTrack>,
	private val ikTracks: List<IkTrack>,
	val durationFrames: Int,
	val frameRate: Float,
) {
	val durationSeconds: Float
		get() = if (durationFrames == 0) 0f else durationFrames / frameRate

	val boundBoneCount: Int
		get() = tracks.size

	fun apply(timeSeconds: Float, pose: Pose) {
		require(pose.skeleton === skeleton) { "Animation clip and pose use different skeletons" }
		require(timeSeconds.isFinite()) { "timeSeconds must be finite" }
		pose.reset()
		val frame = (timeSeconds.coerceAtLeast(0f) * frameRate).coerceAtMost(durationFrames.toFloat())
		tracks.forEach { it.apply(frame, pose) }
		ikTracks.forEach { it.apply(frame, pose) }
	}

	private class IkTrack(
		private val boneIndex: Int,
		private val frames: List<IkFrame>,
	) {
		val endFrame: Int
			get() = frames.last().frame

		fun apply(frame: Float, pose: Pose) {
			val insertion = frames.binarySearch { keyframe ->
				when {
					keyframe.frame < frame -> -1
					keyframe.frame > frame -> 1
					else -> 0
				}
			}.let { if (it >= 0) it + 1 else -it - 1 }
			if (insertion > 0) pose.setIkEnabled(boneIndex, frames[insertion - 1].enabled)
		}
	}

	private class BoneTrack(
		private val boneIndex: Int,
		private val frames: List<BoneFrame>,
	) {
		val endFrame: Int
			get() = frames.last().frame.toInt()

		fun apply(frame: Float, pose: Pose) {
			val nextIndex = frames.binarySearch { keyframe ->
				when {
					keyframe.frame < frame -> -1
					keyframe.frame > frame -> 1
					else -> 0
				}
			}
			if (nextIndex >= 0) {
				applyFrame(frames[nextIndex], pose)
				return
			}
			val insertion = -nextIndex - 1
			if (insertion == 0) {
				applyFrame(frames.first(), pose)
				return
			}
			if (insertion == frames.size) {
				applyFrame(frames.last(), pose)
				return
			}
			val previous = frames[insertion - 1]
			val next = frames[insertion]
			val progress = (frame - previous.frame) / (next.frame - previous.frame)
			val translation = Vector3f(
				mix(previous.translation.x, next.translation.x, next.xCurve.transform(progress)),
				mix(previous.translation.y, next.translation.y, next.yCurve.transform(progress)),
				mix(previous.translation.z, next.translation.z, next.zCurve.transform(progress)),
			)
			val rotation = Quaternionf(previous.rotation)
				.slerp(next.rotation, next.rotationCurve.transform(progress))
			pose.setTranslation(boneIndex, translation).setRotation(boneIndex, rotation)
		}

		private fun applyFrame(frame: BoneFrame, pose: Pose) {
			pose.setTranslation(boneIndex, frame.translation).setRotation(boneIndex, frame.rotation)
		}

		private fun mix(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress
	}

	private data class BoneFrame(
		val frame: Float,
		val translation: Vector3f,
		val rotation: Quaternionf,
		val xCurve: BezierCurve,
		val yCurve: BezierCurve,
		val zCurve: BezierCurve,
		val rotationCurve: BezierCurve,
	)

	private data class IkFrame(val frame: Int, val enabled: Boolean)

	companion object {
		const val VMD_FRAME_RATE: Float = 30f

		fun bind(motion: VmdMotion, skeleton: Skeleton): AnimationClip = bind(motion, skeleton, null)

		fun bind(
			motion: VmdMotion,
			targetRig: HumanoidRig,
			sourceProfile: RetargetProfile? = null,
		): AnimationClip = bind(motion, targetRig.skeleton, MotionRetargeter(targetRig, sourceProfile))

		private fun bind(motion: VmdMotion, skeleton: Skeleton, retargeter: MotionRetargeter?): AnimationClip {
			val tracks = motion.boneKeyframes
				.groupBy(VmdBoneKeyframe::boneName)
				.mapNotNull { (boneName, sourceFrames) ->
					val binding = retargeter?.binding(boneName)
					val boneIndex = binding?.boneIndex ?: skeleton.indexOf(boneName) ?: return@mapNotNull null
					val frames = sourceFrames
						.associateBy(VmdBoneKeyframe::frame)
						.values
						.sortedBy(VmdBoneKeyframe::frame)
						.map { convertFrame(it, binding?.joint, retargeter) }
					BoneTrack(boneIndex, frames)
				}
			val ikTracks = motion.ikKeyframes
				.flatMap { keyframe -> keyframe.states.map { state -> state.name to IkFrame(keyframe.frame, state.enabled) } }
				.groupBy({ it.first }, { it.second })
				.mapNotNull { (boneName, sourceFrames) ->
					val boneIndex = retargeter?.binding(boneName)?.boneIndex
						?: skeleton.indexOf(boneName)
						?: return@mapNotNull null
					if (skeleton.bones[boneIndex].inverseKinematics == null) return@mapNotNull null
					val frames = sourceFrames.associateBy(IkFrame::frame).values.sortedBy(IkFrame::frame)
					IkTrack(boneIndex, frames)
				}
			val duration = maxOf(
				tracks.maxOfOrNull(BoneTrack::endFrame) ?: 0,
				ikTracks.maxOfOrNull(IkTrack::endFrame) ?: 0,
			)
			return AnimationClip(motion.modelName, skeleton, tracks, ikTracks, duration, VMD_FRAME_RATE)
		}

		private fun convertFrame(
			frame: VmdBoneKeyframe,
			joint: HumanoidJoint? = null,
			retargeter: MotionRetargeter? = null,
		): BoneFrame {
			val sourceRotation = frame.rotation
			val rotation = Quaternionf(-sourceRotation.x, -sourceRotation.y, sourceRotation.z, sourceRotation.w)
			if (!rotation.isFinite || rotation.lengthSquared() < 1.0e-12f) rotation.identity() else rotation.normalize()
			val translation = Vector3f(frame.translation.x, frame.translation.y, -frame.translation.z)
			retargeter?.translation(joint, translation, translation)
			retargeter?.rotation(joint, rotation, rotation)
			return BoneFrame(
				frame = frame.frame.toFloat(),
				translation = translation,
				rotation = rotation,
				xCurve = BezierCurve.from(frame.interpolation.x),
				yCurve = BezierCurve.from(frame.interpolation.y),
				zCurve = BezierCurve.from(frame.interpolation.z),
				rotationCurve = BezierCurve.from(frame.interpolation.rotation),
			)
		}
	}
}
