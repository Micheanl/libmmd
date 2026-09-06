package com.micheanl.libmmd.animation

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.exp
import kotlin.math.abs

data class AnimationGraphInput(
	val baseState: MotionState,
	val planarSpeed: Float = 0f,
	val sprinting: Boolean = false,
	val playbackSpeed: Float = 1f,
	val upperBodyState: MotionState? = null,
	val turnRateDegrees: Float = 0f,
)

class AnimationGraph(
	private val pose: Pose,
	private val rig: HumanoidRig,
	clips: Map<MotionState, AnimationClip>,
	private val transitionHalfLife: Float = 0.09f,
	private val speedHalfLife: Float = 0.06f,
) {
	private val clips = clips.toMap()
	private val firstPose = Pose(pose.skeleton)
	private val secondPose = Pose(pose.skeleton)
	private val targetPose = Pose(pose.skeleton)
	private val overlayPose = Pose(pose.skeleton)
	private val upperBodyMask = BoneMask.upperBody(rig)
	private val inertializer = PoseInertializer(pose.skeleton, transitionHalfLife)
	private val stateTimes = HashMap<MotionState, Float>()
	private var locomotionPhase = 0f
	private var smoothedSpeed = 0f
	private var locomotionWeights = LocomotionWeights(1f, 0f, 0f)
	private var selection: Selection? = null
	private var transientState: MotionState? = null
	private var wasMoving = false
	private var turnReady = true

	val currentState: MotionState?
		get() = selection?.base

	val currentUpperBodyState: MotionState?
		get() = selection?.upper

	fun snapshot(): AnimationGraphSnapshot = AnimationGraphSnapshot(
		baseState = currentState,
		upperBodyState = currentUpperBodyState,
		locomotionPhase = locomotionPhase,
		planarSpeed = smoothedSpeed,
		idleWeight = locomotionWeights.idle,
		walkWeight = locomotionWeights.walk,
		sprintWeight = locomotionWeights.sprint,
	)

	init {
		require(rig.skeleton === pose.skeleton) { "Animation graph rig and pose use different skeletons" }
		require(transitionHalfLife.isFinite() && transitionHalfLife > 0f) { "transitionHalfLife must be finite and positive" }
		require(speedHalfLife.isFinite() && speedHalfLife > 0f) { "speedHalfLife must be finite and positive" }
	}

	fun update(input: AnimationGraphInput, deltaSeconds: Float) {
		require(deltaSeconds.isFinite() && deltaSeconds >= 0f) { "deltaSeconds must be finite and non-negative" }
		require(input.planarSpeed.isFinite() && input.planarSpeed >= 0f) { "planarSpeed must be finite and non-negative" }
		require(input.playbackSpeed.isFinite() && input.playbackSpeed >= 0f) { "playbackSpeed must be finite and non-negative" }
		require(input.turnRateDegrees.isFinite()) { "turnRateDegrees must be finite" }

		val previous = selection
		val resolved = Selection(resolveBase(input), input.upperBodyState?.takeIf(clips::containsKey))
		val changed = resolved != previous
		if (changed) {
			if (resolved.base != previous?.base && !resolved.base.looping) stateTimes[resolved.base] = 0f
			if (resolved.upper != previous?.upper && resolved.upper?.looping == false) stateTimes[resolved.upper] = 0f
		}
		selection = resolved
		smoothedSpeed += (input.planarSpeed - smoothedSpeed) * decayAlpha(deltaSeconds, speedHalfLife)
		sampleBase(resolved.base, smoothedSpeed, input.sprinting, input.playbackSpeed, deltaSeconds)
		resolved.upper?.let { overlay ->
			val clip = clips.getValue(overlay)
			clip.apply(advance(overlay, clip, deltaSeconds * input.playbackSpeed), overlayPose)
			targetPose.blend(targetPose, overlayPose, upperBodyMask, 1f)
		}
		if (changed) inertializer.capture(pose, targetPose)
		inertializer.apply(targetPose, pose, deltaSeconds)
	}

	private fun resolveBase(input: AnimationGraphInput): MotionState {
		val requested = resolve(input.baseState)
		if (requested !in LOCOMOTION_STATES) {
			transientState = null
			wasMoving = false
			return requested
		}
		if (abs(input.turnRateDegrees) <= TURN_RELEASE_RATE) turnReady = true
		val moving = input.planarSpeed > START_SPEED
		val active = transientState
		if (active != null) {
			val clip = clips.getValue(active)
			if ((stateTimes[active] ?: 0f) < clip.durationSeconds) {
				wasMoving = moving
				return active
			}
			transientState = null
		}
		val next = when {
			moving && !wasMoving && MotionState.START in clips -> MotionState.START
			!moving && wasMoving && MotionState.STOP in clips -> MotionState.STOP
			!moving && turnReady && abs(input.turnRateDegrees) >= TURN_TRIGGER_RATE -> {
				val turn = if (input.turnRateDegrees > 0f) MotionState.TURN_RIGHT else MotionState.TURN_LEFT
				if (turn in clips) {
					turnReady = false
					turn
				} else {
					null
				}
			}
			else -> null
		}
		wasMoving = moving
		if (next != null) {
			stateTimes[next] = 0f
			transientState = next
			return next
		}
		return requested
	}

	private fun sampleBase(
		state: MotionState,
		planarSpeed: Float,
		sprinting: Boolean,
		playbackSpeed: Float,
		deltaSeconds: Float,
	) {
		if (state in LOCOMOTION_STATES && LOCOMOTION_STATES.all(clips::containsKey)) {
			sampleLocomotion(planarSpeed, sprinting, playbackSpeed, deltaSeconds)
			return
		}
		locomotionWeights = LocomotionWeights(0f, 0f, 0f)
		val clip = clips.getValue(state)
		clip.apply(advance(state, clip, deltaSeconds * playbackSpeed), targetPose)
	}

	private fun sampleLocomotion(speed: Float, sprinting: Boolean, playbackSpeed: Float, deltaSeconds: Float) {
		val idle = clips.getValue(MotionState.IDLE)
		val walk = clips.getValue(MotionState.WALK)
		val sprint = clips.getValue(MotionState.SPRINT)
		val effectiveSpeed = if (sprinting && speed > IDLE_SPEED) maxOf(speed, SPRINT_SPEED) else speed
		val walkWeight = smoothStep(IDLE_SPEED, WALK_SPEED, effectiveSpeed)
		val sprintWeight = smoothStep(WALK_SPEED, SPRINT_SPEED, effectiveSpeed)
		locomotionWeights = if (effectiveSpeed <= WALK_SPEED) {
			LocomotionWeights(1f - walkWeight, walkWeight, 0f)
		} else {
			LocomotionWeights(0f, 1f - sprintWeight, sprintWeight)
		}
		val cycleDuration = when {
			effectiveSpeed <= WALK_SPEED -> mix(idle.durationSeconds, walk.durationSeconds, walkWeight)
			else -> mix(walk.durationSeconds, sprint.durationSeconds, sprintWeight)
		}.coerceAtLeast(1f / AnimationClip.VMD_FRAME_RATE)
		locomotionPhase = (locomotionPhase + deltaSeconds * playbackSpeed / cycleDuration) % 1f
		idle.apply(locomotionPhase * idle.durationSeconds, firstPose)
		walk.apply(locomotionPhase * walk.durationSeconds, secondPose)
		targetPose.blend(firstPose, secondPose, walkWeight)
		if (sprintWeight > 0f) {
			sprint.apply(locomotionPhase * sprint.durationSeconds, firstPose)
			targetPose.blend(targetPose, firstPose, sprintWeight)
		}
	}

	private fun advance(state: MotionState, clip: AnimationClip, deltaSeconds: Float): Float {
		val duration = clip.durationSeconds
		if (duration <= 0f) return 0f
		val next = (stateTimes[state] ?: 0f) + deltaSeconds
		val time = if (state.looping) next % duration else next.coerceAtMost(duration)
		stateTimes[state] = time
		return time
	}

	private fun resolve(requested: MotionState): MotionState = when {
		requested in clips -> requested
		MotionState.IDLE in clips -> MotionState.IDLE
		else -> clips.keys.firstOrNull() ?: error("Animation graph has no clips")
	}

	private data class Selection(val base: MotionState, val upper: MotionState?)
	private data class LocomotionWeights(val idle: Float, val walk: Float, val sprint: Float)

	companion object {
		private const val IDLE_SPEED = 0.01f
		private const val WALK_SPEED = 0.1f
		private const val SPRINT_SPEED = 0.14f
		private const val START_SPEED = 0.018f
		private const val TURN_TRIGGER_RATE = 75f
		private const val TURN_RELEASE_RATE = 20f
		private val LOCOMOTION_STATES = setOf(MotionState.IDLE, MotionState.WALK, MotionState.SPRINT)

		private fun smoothStep(start: Float, end: Float, value: Float): Float {
			val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
			return t * t * (3f - 2f * t)
		}

		private fun mix(start: Float, end: Float, weight: Float): Float = start + (end - start) * weight

		private fun decayAlpha(deltaSeconds: Float, halfLife: Float): Float =
			if (deltaSeconds == 0f) 0f else 1f - exp((-0.6931472f * deltaSeconds / halfLife))
	}
}

data class AnimationGraphSnapshot(
	val baseState: MotionState?,
	val upperBodyState: MotionState?,
	val locomotionPhase: Float,
	val planarSpeed: Float,
	val idleWeight: Float,
	val walkWeight: Float,
	val sprintWeight: Float,
)

private class PoseInertializer(
	private val skeleton: Skeleton,
	private val halfLife: Float,
) {
	private val translationOffsets = Array(skeleton.boneCount) { Vector3f() }
	private val rotationOffsets = Array(skeleton.boneCount) { Quaternionf() }
	private var elapsed = Float.POSITIVE_INFINITY

	fun capture(current: Pose, target: Pose) {
		for (index in 0 until skeleton.boneCount) {
			translationOffsets[index].set(current.translation(index)).sub(target.translation(index))
			rotationOffsets[index].set(current.rotation(index)).mul(target.rotation(index).invert()).normalize()
		}
		elapsed = 0f
	}

	fun apply(target: Pose, output: Pose, deltaSeconds: Float) {
		elapsed += deltaSeconds
		val remaining = exp(-0.6931472f * elapsed / halfLife)
		output.copyFrom(target)
		if (remaining < 0.001f) return
		val translation = Vector3f()
		val rotation = Quaternionf()
		for (index in 0 until skeleton.boneCount) {
			target.translation(index, translation).fma(remaining, translationOffsets[index])
			output.setTranslation(index, translation)
			rotation.identity().slerp(rotationOffsets[index], remaining).mul(target.rotation(index)).normalize()
			output.setRotation(index, rotation)
		}
	}
}
