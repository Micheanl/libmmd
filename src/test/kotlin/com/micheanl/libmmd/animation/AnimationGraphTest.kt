package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxVector3
import com.micheanl.libmmd.format.vmd.VmdBezier
import com.micheanl.libmmd.format.vmd.VmdBoneInterpolation
import com.micheanl.libmmd.format.vmd.VmdBoneKeyframe
import com.micheanl.libmmd.format.vmd.VmdMotion
import com.micheanl.libmmd.format.vmd.VmdQuaternion
import com.micheanl.libmmd.format.vmd.VmdVector3
import kotlin.test.Test
import kotlin.test.assertEquals

class AnimationGraphTest {
	@Test
	fun `blends locomotion continuously with shared phase`() {
		val skeleton = skeleton()
		val pose = Pose(skeleton)
		val clips = mapOf(
			MotionState.IDLE to clip(skeleton, "センター", 0f, 0f),
			MotionState.WALK to clip(skeleton, "センター", 0f, 10f),
			MotionState.SPRINT to clip(skeleton, "センター", 0f, 20f),
		)
		val graph = AnimationGraph(pose, HumanoidRig.from(skeleton), clips, 0.0001f, 0.0001f)

		graph.update(AnimationGraphInput(MotionState.IDLE, planarSpeed = 0.1f), 0.5f)

		assertEquals(5f, pose.translation(0).x, 0.001f)
		assertEquals(1f, graph.snapshot().walkWeight, 0.001f)
		assertEquals(0.5f, graph.snapshot().locomotionPhase, 0.001f)
	}

	@Test
	fun `applies action overlay only to upper body`() {
		val skeleton = skeleton()
		val pose = Pose(skeleton)
		val clips = mapOf(
			MotionState.IDLE to clip(skeleton, "センター", 0f, 0f),
			MotionState.WALK to clip(skeleton, "センター", 0f, 0f),
			MotionState.SPRINT to clip(skeleton, "センター", 0f, 0f),
			MotionState.SWING_LEFT to multiBoneClip(mapOf("左腕" to 9f, "左足" to 9f), skeleton),
		)
		val graph = AnimationGraph(pose, HumanoidRig.from(skeleton), clips, 0.0001f, 0.0001f)
		graph.update(AnimationGraphInput(MotionState.IDLE), 1f)

		graph.update(AnimationGraphInput(MotionState.IDLE, upperBodyState = MotionState.SWING_LEFT), 1f)

		assertEquals(9f, pose.translation(3).x, 0.001f)
		assertEquals(0f, pose.translation(4).x, 0.001f)
	}

	@Test
	fun `starts state changes from the current pose`() {
		val skeleton = skeleton()
		val pose = Pose(skeleton)
		val clips = mapOf(
			MotionState.IDLE to clip(skeleton, "センター", 0f, 0f),
			MotionState.WALK to clip(skeleton, "センター", 0f, 0f),
			MotionState.SPRINT to clip(skeleton, "センター", 0f, 0f),
			MotionState.SLEEP to clip(skeleton, "センター", 10f, 10f),
		)
		val graph = AnimationGraph(pose, HumanoidRig.from(skeleton), clips)
		graph.update(AnimationGraphInput(MotionState.IDLE), 1f)

		graph.update(AnimationGraphInput(MotionState.SLEEP), 0f)

		assertEquals(0f, pose.translation(0).x, 0.0001f)
		graph.update(AnimationGraphInput(MotionState.SLEEP), 0.5f)
		assertEquals(10f, pose.translation(0).x, 0.3f)
	}

	@Test
	fun `restarts non-looping clips when selected again`() {
		val skeleton = skeleton()
		val pose = Pose(skeleton)
		val clips = mapOf(
			MotionState.IDLE to clip(skeleton, "センター", 0f, 0f),
			MotionState.WALK to clip(skeleton, "センター", 0f, 0f),
			MotionState.SPRINT to clip(skeleton, "センター", 0f, 0f),
			MotionState.DIE to clip(skeleton, "センター", 0f, 10f),
		)
		val graph = AnimationGraph(pose, HumanoidRig.from(skeleton), clips, 0.0001f, 0.0001f)
		graph.update(AnimationGraphInput(MotionState.IDLE), 1f)
		graph.update(AnimationGraphInput(MotionState.DIE), 0.5f)
		graph.update(AnimationGraphInput(MotionState.IDLE), 1f)

		graph.update(AnimationGraphInput(MotionState.DIE), 0f)
		graph.update(AnimationGraphInput(MotionState.DIE), 0.1f)

		assertEquals(1f, pose.translation(0).x, 0.01f)
	}

	@Test
	fun `plays start and stop transients around locomotion`() {
		val skeleton = skeleton()
		val clips = listOf(
			MotionState.IDLE,
			MotionState.WALK,
			MotionState.SPRINT,
			MotionState.START,
			MotionState.STOP,
		).associateWith { state -> durationClip(skeleton, if (state == MotionState.START) 16 else if (state == MotionState.STOP) 14 else 30) }
		val graph = AnimationGraph(Pose(skeleton), HumanoidRig.from(skeleton), clips)
		graph.update(AnimationGraphInput(MotionState.IDLE), 0f)

		graph.update(AnimationGraphInput(MotionState.WALK, planarSpeed = 0.1f), 0f)

		assertEquals(MotionState.START, graph.currentState)
		repeat(14) { graph.update(AnimationGraphInput(MotionState.WALK, planarSpeed = 0.1f), 0.05f) }
		assertEquals(MotionState.WALK, graph.currentState)

		graph.update(AnimationGraphInput(MotionState.IDLE), 0f)
		assertEquals(MotionState.STOP, graph.currentState)
		repeat(12) { graph.update(AnimationGraphInput(MotionState.IDLE), 0.05f) }
		assertEquals(MotionState.IDLE, graph.currentState)
	}

	@Test
	fun `plays directional turn transient only after threshold`() {
		val skeleton = skeleton()
		val clips = listOf(
			MotionState.IDLE,
			MotionState.WALK,
			MotionState.SPRINT,
			MotionState.TURN_LEFT,
			MotionState.TURN_RIGHT,
		).associateWith { durationClip(skeleton, 18) }
		val graph = AnimationGraph(Pose(skeleton), HumanoidRig.from(skeleton), clips)
		graph.update(AnimationGraphInput(MotionState.IDLE), 0f)

		graph.update(AnimationGraphInput(MotionState.IDLE, turnRateDegrees = 90f), 0f)

		assertEquals(MotionState.TURN_RIGHT, graph.currentState)
	}

	private fun skeleton(): Skeleton = Skeleton.from(
		listOf(
			bone("センター", 0f, -1),
			bone("下半身", 1f, 0),
			bone("上半身", 2f, 1),
			bone("左腕", 3f, 2),
			bone("左足", 0f, 1),
		),
	)

	private fun bone(name: String, y: Float, parent: Int): PmxBone = PmxBone(
		name,
		name,
		PmxVector3(0f, y, 0f),
		parent,
		0,
		PmxBoneFlags(0),
		PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
		null,
		null,
		null,
		null,
		null,
	)

	private fun clip(skeleton: Skeleton, bone: String, start: Float, end: Float): AnimationClip =
		AnimationClip.bind(
			motion(
				mapOf(bone to listOf(0 to start, 30 to end)),
			),
			skeleton,
		)

	private fun multiBoneClip(values: Map<String, Float>, skeleton: Skeleton): AnimationClip =
		AnimationClip.bind(motion(values.mapValues { listOf(0 to it.value) }), skeleton)

	private fun durationClip(skeleton: Skeleton, durationFrames: Int): AnimationClip =
		AnimationClip.bind(motion(mapOf("センター" to listOf(0 to 0f, durationFrames to 0f))), skeleton)

	private fun motion(tracks: Map<String, List<Pair<Int, Float>>>): VmdMotion {
		val linear = VmdBezier(0f, 0f, 1f, 1f)
		val interpolation = VmdBoneInterpolation(linear, linear, linear, linear)
		return VmdMotion(
			"Graph Test",
			tracks.flatMap { (bone, values) ->
				values.map { (frame, x) ->
					VmdBoneKeyframe(
						bone,
						frame,
						VmdVector3(x, 0f, 0f),
						VmdQuaternion(0f, 0f, 0f, 1f),
						interpolation,
					)
				}
			},
			emptyList(),
			emptyList(),
			emptyList(),
			emptyList(),
			emptyList(),
		)
	}
}
