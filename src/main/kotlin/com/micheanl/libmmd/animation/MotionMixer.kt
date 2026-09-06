package com.micheanl.libmmd.animation

enum class MotionState(
	val fileName: String,
	val looping: Boolean = true,
	internal val locomotion: Boolean = false,
) {
	IDLE("idle.vmd"),
	START("start.vmd", looping = false, locomotion = true),
	STOP("stop.vmd", looping = false, locomotion = true),
	TURN_LEFT("turnLeft.vmd", looping = false, locomotion = true),
	TURN_RIGHT("turnRight.vmd", looping = false, locomotion = true),
	WALK("walk.vmd", locomotion = true),
	SPRINT("sprint.vmd", locomotion = true),
	SNEAK("sneak.vmd", locomotion = true),
	SWIM("swim.vmd", locomotion = true),
	CRAWL("crawl.vmd", locomotion = true),
	ELYTRA_FLY("elytraFly.vmd"),
	CLIMB("onClimbable.vmd", locomotion = true),
	CLIMB_UP("onClimbableUp.vmd", locomotion = true),
	CLIMB_DOWN("onClimbableDown.vmd", locomotion = true),
	HORSE("onHorse.vmd"),
	RIDE("ride.vmd"),
	SLEEP("sleep.vmd"),
	LIE_DOWN("lieDown.vmd", looping = false),
	DIE("die.vmd", looping = false),
	SWING_LEFT("swingLeft.vmd", looping = false),
	SWING_RIGHT("swingRight.vmd", looping = false),
	BOW_LEFT("itemActive_minecraft.bow_Left_using.vmd"),
	SWORD_RIGHT("itemActive_minecraft.iron_sword_Right_swinging.vmd", looping = false),
	SHIELD_LEFT("itemActive_minecraft.shield_Left_using.vmd"),
	SHIELD_RIGHT("itemActive_minecraft.shield_Right_using.vmd"),
}

class MotionMixer(
	private val pose: Pose,
	clips: Map<MotionState, AnimationClip>,
	val transitionSeconds: Float = 0.2f,
) {
	private val clips = clips.toMap()
	private val sampledPose = Pose(pose.skeleton)
	private val outgoingPose = Pose(pose.skeleton)
	private val times = HashMap<MotionState, Float>()
	private var outgoing = false
	private var transitionTime = transitionSeconds
	private var state: MotionState? = null

	val currentState: MotionState?
		get() = state

	init {
		require(transitionSeconds.isFinite() && transitionSeconds >= 0f) {
			"transitionSeconds must be finite and non-negative"
		}
		select(MotionState.IDLE)
	}

	fun select(requested: MotionState): MotionState? {
		val next = when {
			requested in clips -> requested
			MotionState.IDLE in clips -> MotionState.IDLE
			else -> clips.keys.firstOrNull()
		}
		if (next == state) return state
		if (state != null) {
			outgoingPose.copyFrom(pose)
			outgoing = true
			transitionTime = 0f
		}
		val previous = state
		state = next
		if (next != null && (!next.looping || previous?.locomotion != true || !next.locomotion)) {
			times[next] = 0f
		} else if (next != null && previous != null) {
			val previousClip = clips.getValue(previous)
			val nextClip = clips.getValue(next)
			val phase = if (previousClip.durationSeconds > 0f) {
				(times[previous] ?: 0f) / previousClip.durationSeconds
			} else {
				0f
			}
			times[next] = phase * nextClip.durationSeconds
		}
		return state
	}

	fun update(deltaSeconds: Float, playbackSpeed: Float = 1f) {
		require(deltaSeconds.isFinite() && deltaSeconds >= 0f) { "deltaSeconds must be finite and non-negative" }
		require(playbackSpeed.isFinite() && playbackSpeed >= 0f) { "playbackSpeed must be finite and non-negative" }
		val current = state
		if (current == null) {
			pose.reset()
			return
		}
		val clip = clips.getValue(current)
		val time = advance(current, clip, deltaSeconds * playbackSpeed)
		clip.apply(time, sampledPose)
		if (!outgoing || transitionSeconds == 0f) {
			outgoing = false
			pose.copyFrom(sampledPose)
			return
		}
		transitionTime = (transitionTime + deltaSeconds).coerceAtMost(transitionSeconds)
		val linear = transitionTime / transitionSeconds
		val smooth = linear * linear * (3f - 2f * linear)
		pose.blend(outgoingPose, sampledPose, smooth)
		if (transitionTime >= transitionSeconds) outgoing = false
	}

	private fun advance(state: MotionState, clip: AnimationClip, deltaSeconds: Float): Float {
		val duration = clip.durationSeconds
		if (duration <= 0f) return 0f
		val next = (times[state] ?: 0f) + deltaSeconds
		val resolved = if (state.looping) next % duration else next.coerceAtMost(duration)
		times[state] = resolved
		return resolved
	}
}
