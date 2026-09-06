package com.micheanl.libmmd.animation

class Animator(
	val clip: AnimationClip,
	val pose: Pose,
	val morphClip: MorphClip? = null,
	val morphWeights: MorphWeights? = null,
) {
	var timeSeconds: Float = 0f
		private set

	var playbackSpeed: Float = 1f
		set(value) {
			require(value.isFinite() && value >= 0f) { "playbackSpeed must be finite and non-negative" }
			field = value
		}

	var looping: Boolean = true
	var isPlaying: Boolean = false
		private set

	val durationSeconds: Float
		get() = maxOf(clip.durationSeconds, morphClip?.durationSeconds ?: 0f)

	init {
		require(pose.skeleton === clip.skeleton) { "Animation clip and pose use different skeletons" }
		require((morphClip == null) == (morphWeights == null)) {
			"Morph clip and weights must be provided together"
		}
		if (morphClip != null && morphWeights != null) {
			require(morphWeights.set === morphClip.set) { "Morph clip and weights use different morph sets" }
		}
		applyCurrentTime()
	}

	fun play(restart: Boolean = false): Animator = apply {
		if (restart || (!looping && timeSeconds >= durationSeconds)) seek(0f)
		isPlaying = true
	}

	fun pause(): Animator = apply {
		isPlaying = false
	}

	fun stop(): Animator = apply {
		isPlaying = false
		seek(0f)
	}

	fun seek(timeSeconds: Float): Animator = apply {
		require(timeSeconds.isFinite()) { "timeSeconds must be finite" }
		this.timeSeconds = timeSeconds.coerceIn(0f, durationSeconds)
		applyCurrentTime()
	}

	fun update(deltaSeconds: Float): Animator = apply {
		require(deltaSeconds.isFinite() && deltaSeconds >= 0f) { "deltaSeconds must be finite and non-negative" }
		if (!isPlaying) return@apply
		val duration = durationSeconds
		if (duration <= 0f) {
			timeSeconds = 0f
			isPlaying = false
		} else {
			val next = timeSeconds + deltaSeconds * playbackSpeed
			if (looping) {
				timeSeconds = next % duration
			} else {
				timeSeconds = next.coerceAtMost(duration)
				if (timeSeconds >= duration) isPlaying = false
			}
		}
		applyCurrentTime()
	}

	private fun applyCurrentTime() {
		clip.apply(timeSeconds, pose)
		if (morphClip != null && morphWeights != null) {
			morphClip.apply(timeSeconds, morphWeights)
			morphWeights.applyBones(pose)
		}
	}
}
