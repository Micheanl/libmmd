package com.micheanl.libmmd.render

import com.micheanl.libmmd.interaction.InteractionController
import com.micheanl.libmmd.physics.BodyGeometry
import com.micheanl.libmmd.physics.PhysicsBodyRole
import com.micheanl.libmmd.physics.RigidBodySnapshot
import com.micheanl.libmmd.player.AnimationDebugSnapshot
import com.micheanl.libmmd.player.FootDebugPoint
import com.micheanl.libmmd.player.PlayerModels
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4fc
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale

@Environment(EnvType.CLIENT)
object PhysicsOverlay {
	private const val SEGMENTS = 20
	private val hoverColor = 0xffffc247.toInt()
	private val dynamicColor = 0xff5ee68a.toInt()
	private val kinematicColor = 0xff67b7ff.toInt()
	private val skirtColor = 0xffc084fc.toInt()
	private val hairColor = 0xffff78b4.toInt()
	private val accessoryColor = 0xffffd166.toInt()
	private val grabColor = 0xff67e8f9.toInt()
	private val boneColor = 0xfff2f2f2.toInt()
	private val contactColor = 0xff5ee68a.toInt()
	private val airborneColor = 0xffff9f43.toInt()
	private val axisXColor = 0xffff5b5b.toInt()
	private val axisYColor = 0xff62df7c.toInt()
	private val axisZColor = 0xff579dff.toInt()

	fun register() {
		LevelRenderEvents.BEFORE_GIZMOS.register { render() }
	}

	private fun render() {
		val client = Minecraft.getInstance()
		if (client.level == null) return
		client.levelRenderer.collectPerFrameRenderThreadGizmos().use {
			if (InteractionController.debugVisible) {
				WorldModels.physicsSnapshots().forEach { snapshot ->
					draw(snapshot, roleColor(snapshot), 1f)
				}
				PlayerModels.animationDebugSnapshots().forEach(::drawAnimation)
			}
			InteractionController.hovered?.takeUnless { it.rig.isClosed }?.let { hit ->
				draw(hit.rig.snapshot(hit.rigidBodyIndex), hoverColor, 2.25f)
				Gizmos.point(hit.position.toVec3(), hoverColor, 5f).setAlwaysOnTop()
			}
			InteractionController.activeGrab?.takeUnless { it.isClosed }?.let { grab ->
				val camera = client.cameraEntity ?: return@let
				Gizmos.line(camera.eyePosition, grab.position.toVec3(), grabColor, 2f).setAlwaysOnTop()
				Gizmos.point(grab.position.toVec3(), grabColor, 6f).setAlwaysOnTop()
			}
		}
	}

	private fun roleColor(snapshot: RigidBodySnapshot): Int = when (snapshot.role) {
		PhysicsBodyRole.BODY -> kinematicColor
		PhysicsBodyRole.SKIRT -> skirtColor
		PhysicsBodyRole.HAIR -> hairColor
		PhysicsBodyRole.ACCESSORY -> accessoryColor
		PhysicsBodyRole.OTHER -> if (snapshot.dynamic) dynamicColor else kinematicColor
	}

	private fun drawAnimation(snapshot: AnimationDebugSnapshot) {
		snapshot.bones.forEach { line(it.parent.toVec3(), it.child.toVec3(), boneColor, 1.5f) }
		snapshot.joints.forEach { axes ->
			Gizmos.arrow(axes.origin.toVec3(), axes.x.toVec3(), axisXColor, 1f)
			Gizmos.arrow(axes.origin.toVec3(), axes.y.toVec3(), axisYColor, 1f)
			Gizmos.arrow(axes.origin.toVec3(), axes.z.toVec3(), axisZColor, 1f)
		}
		drawFoot(snapshot.leftFoot)
		drawFoot(snapshot.rightFoot)
		val graph = snapshot.graph
		val label = String.format(
			Locale.ROOT,
			"%s + %s  speed %.3f  phase %.2f  I/W/S %.2f/%.2f/%.2f  stride %.2f  dir %.0f°",
			graph.baseState?.name ?: "NONE",
			graph.upperBodyState?.name ?: "NONE",
			graph.planarSpeed,
			graph.locomotionPhase,
			graph.idleWeight,
			graph.walkWeight,
			graph.sprintWeight,
			snapshot.strideScale,
			Math.toDegrees(snapshot.orientationRadians.toDouble()),
		)
		Gizmos.billboardText(
			label,
			snapshot.labelPosition.toVec3(),
			TextGizmo.Style.forColorAndCentered(0xffffffff.toInt()).withScale(0.8f),
		).setAlwaysOnTop()
	}

	private fun drawFoot(point: FootDebugPoint?) {
		point ?: return
		val color = if (point.locked) contactColor else airborneColor
		Gizmos.point(point.foot.toVec3(), color, 6f).setAlwaysOnTop()
		point.target?.let { target ->
			Gizmos.point(target.toVec3(), color, 4f).setAlwaysOnTop()
			line(point.foot.toVec3(), target.toVec3(), color, 1.5f)
		}
	}

	private fun draw(snapshot: RigidBodySnapshot, color: Int, width: Float) {
		when (val geometry = snapshot.geometry) {
			is BodyGeometry.Box -> drawBox(snapshot.transform, geometry, color, width)
			is BodyGeometry.Sphere -> {
				drawCircle(snapshot.transform, 0f, geometry.radius, Plane.XY, color, width)
				drawCircle(snapshot.transform, 0f, geometry.radius, Plane.XZ, color, width)
				drawCircle(snapshot.transform, 0f, geometry.radius, Plane.YZ, color, width)
			}

			is BodyGeometry.Capsule -> drawCapsule(snapshot.transform, geometry, color, width)
		}
	}

	private fun drawBox(transform: Matrix4fc, box: BodyGeometry.Box, color: Int, width: Float) {
		val corners = arrayOf(
			point(transform, -box.halfX, -box.halfY, -box.halfZ),
			point(transform, box.halfX, -box.halfY, -box.halfZ),
			point(transform, box.halfX, box.halfY, -box.halfZ),
			point(transform, -box.halfX, box.halfY, -box.halfZ),
			point(transform, -box.halfX, -box.halfY, box.halfZ),
			point(transform, box.halfX, -box.halfY, box.halfZ),
			point(transform, box.halfX, box.halfY, box.halfZ),
			point(transform, -box.halfX, box.halfY, box.halfZ),
		)
		BOX_EDGES.forEach { edge -> line(corners[edge.first], corners[edge.second], color, width) }
	}

	private fun drawCapsule(transform: Matrix4fc, capsule: BodyGeometry.Capsule, color: Int, width: Float) {
		drawCircle(transform, capsule.halfHeight, capsule.radius, Plane.XZ, color, width)
		drawCircle(transform, -capsule.halfHeight, capsule.radius, Plane.XZ, color, width)
		listOf(
			capsule.radius to 0f,
			-capsule.radius to 0f,
			0f to capsule.radius,
			0f to -capsule.radius,
		).forEach { (x, z) ->
			line(
				point(transform, x, -capsule.halfHeight, z),
				point(transform, x, capsule.halfHeight, z),
				color,
				width,
			)
		}
		drawCap(transform, capsule, true, Plane.XY, color, width)
		drawCap(transform, capsule, true, Plane.YZ, color, width)
		drawCap(transform, capsule, false, Plane.XY, color, width)
		drawCap(transform, capsule, false, Plane.YZ, color, width)
	}

	private fun drawCircle(
		transform: Matrix4fc,
		offset: Float,
		radius: Float,
		plane: Plane,
		color: Int,
		width: Float,
	) {
		polyline(SEGMENTS, true, color, width) { step ->
			val angle = step * PI.toFloat() * 2f / SEGMENTS
			when (plane) {
				Plane.XY -> point(transform, cos(angle) * radius, sin(angle) * radius, offset)
				Plane.XZ -> point(transform, cos(angle) * radius, offset, sin(angle) * radius)
				Plane.YZ -> point(transform, offset, cos(angle) * radius, sin(angle) * radius)
			}
		}
	}

	private fun drawCap(
		transform: Matrix4fc,
		capsule: BodyGeometry.Capsule,
		top: Boolean,
		plane: Plane,
		color: Int,
		width: Float,
	) {
		polyline(SEGMENTS / 2, false, color, width) { step ->
			val angle = step * PI.toFloat() / (SEGMENTS / 2)
			val lateral = cos(angle) * capsule.radius
			val vertical = (if (top) capsule.halfHeight else -capsule.halfHeight) +
				(if (top) 1f else -1f) * sin(angle) * capsule.radius
			if (plane == Plane.XY) point(transform, lateral, vertical, 0f)
			else point(transform, 0f, vertical, lateral)
		}
	}

	private fun polyline(
		segments: Int,
		closed: Boolean,
		color: Int,
		width: Float,
		pointAt: (Int) -> Vec3,
	) {
		val count = if (closed) segments else segments + 1
		val points = Array(count) { pointAt(it) }
		for (index in 0 until points.lastIndex) line(points[index], points[index + 1], color, width)
		if (closed) line(points.last(), points.first(), color, width)
	}

	private fun point(transform: Matrix4fc, x: Float, y: Float, z: Float): Vec3 {
		val result = transform.transformPosition(Vector3f(x, y, z))
		return result.toVec3()
	}

	private fun line(start: Vec3, end: Vec3, color: Int, width: Float) {
		Gizmos.line(start, end, color, width)
	}

	private fun Vector3f.toVec3() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

	private enum class Plane { XY, XZ, YZ }

	private val BOX_EDGES = arrayOf(
		0 to 1, 1 to 2, 2 to 3, 3 to 0,
		4 to 5, 5 to 6, 6 to 7, 7 to 4,
		0 to 4, 1 to 5, 2 to 6, 3 to 7,
	)
}
