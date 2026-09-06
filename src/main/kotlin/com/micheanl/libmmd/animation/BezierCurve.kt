package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.vmd.VmdBezier

data class BezierCurve(
	val x1: Float,
	val y1: Float,
	val x2: Float,
	val y2: Float,
) {
	fun transform(progress: Float): Float {
		val target = progress.coerceIn(0f, 1f)
		if (target == 0f || target == 1f) return target
		if (x1 == y1 && x2 == y2) return target
		var low = 0f
		var high = 1f
		repeat(24) {
			val middle = (low + high) * 0.5f
			if (component(middle, x1, x2) < target) low = middle else high = middle
		}
		return component((low + high) * 0.5f, y1, y2)
	}

	private fun component(time: Float, first: Float, second: Float): Float {
		val inverse = 1f - time
		return 3f * inverse * inverse * time * first + 3f * inverse * time * time * second + time * time * time
	}

	companion object {
		val LINEAR = BezierCurve(0f, 0f, 1f, 1f)

		internal fun from(curve: VmdBezier): BezierCurve = BezierCurve(curve.x1, curve.y1, curve.x2, curve.y2)
	}
}
