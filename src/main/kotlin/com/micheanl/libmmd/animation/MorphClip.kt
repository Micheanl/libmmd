package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.vmd.VmdMorphKeyframe
import com.micheanl.libmmd.format.vmd.VmdMotion

class MorphClip private constructor(
	val name: String,
	val set: MorphSet,
	private val tracks: List<MorphTrack>,
	val durationFrames: Int,
	val frameRate: Float,
) {
	val durationSeconds: Float
		get() = if (durationFrames == 0) 0f else durationFrames / frameRate

	val boundTargetCount: Int
		get() = tracks.size

	fun apply(timeSeconds: Float, weights: MorphWeights) {
		require(weights.set === set) { "Morph clip and weights use different morph sets" }
		require(timeSeconds.isFinite()) { "timeSeconds must be finite" }
		weights.reset()
		val frame = (timeSeconds.coerceAtLeast(0f) * frameRate).coerceAtMost(durationFrames.toFloat())
		tracks.forEach { it.apply(frame, weights) }
	}

	private class MorphTrack(
		private val targetIndex: Int,
		private val frames: List<MorphFrame>,
	) {
		val endFrame: Int
			get() = frames.last().frame

		fun apply(frame: Float, weights: MorphWeights) {
			val nextIndex = frames.binarySearch { keyframe ->
				when {
					keyframe.frame < frame -> -1
					keyframe.frame > frame -> 1
					else -> 0
				}
			}
			if (nextIndex >= 0) {
				weights.set(targetIndex, frames[nextIndex].weight)
				return
			}
			val insertion = -nextIndex - 1
			if (insertion == 0) {
				weights.set(targetIndex, frames.first().weight)
				return
			}
			if (insertion == frames.size) {
				weights.set(targetIndex, frames.last().weight)
				return
			}
			val previous = frames[insertion - 1]
			val next = frames[insertion]
			val progress = (frame - previous.frame) / (next.frame - previous.frame)
			weights.set(targetIndex, previous.weight + (next.weight - previous.weight) * progress)
		}
	}

	private data class MorphFrame(val frame: Int, val weight: Float)

	companion object {
		const val VMD_FRAME_RATE: Float = 30f

		fun bind(motion: VmdMotion, set: MorphSet): MorphClip {
			val tracks = motion.morphKeyframes
				.groupBy(VmdMorphKeyframe::morphName)
				.mapNotNull { (morphName, sourceFrames) ->
					val targetIndex = set.indexOf(morphName) ?: return@mapNotNull null
					val frames = sourceFrames
						.associateBy(VmdMorphKeyframe::frame)
						.values
						.sortedBy(VmdMorphKeyframe::frame)
						.map { MorphFrame(it.frame, it.weight) }
					MorphTrack(targetIndex, frames)
				}
			val duration = tracks.maxOfOrNull(MorphTrack::endFrame) ?: 0
			return MorphClip(motion.modelName, set, tracks, duration, VMD_FRAME_RATE)
		}
	}
}
