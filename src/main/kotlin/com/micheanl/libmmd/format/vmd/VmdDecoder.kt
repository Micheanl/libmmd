package com.micheanl.libmmd.format.vmd

import java.nio.charset.Charset

object VmdDecoder {
	private val textEncoding: Charset = Charset.forName("windows-31j")

	fun readMotion(bytes: ByteArray, limits: VmdDecodeLimits = VmdDecodeLimits()): VmdMotion {
		val input = VmdBinaryInput(bytes)
		val signatureOffset = input.position
		val signature = input.readFixedText(30, Charsets.US_ASCII)
		if (signature != "Vocaloid Motion Data 0002") {
			throw VmdFormatException("Unsupported VMD signature '$signature'", signatureOffset)
		}
		val modelName = input.readFixedText(20, textEncoding)
		val boneKeyframes = readSection(input, "bone keyframe", limits.maxBoneKeyframes, BONE_FRAME_BYTES) {
			readBoneKeyframe(input)
		}
		val morphKeyframes = readOptionalSection(input, "morph keyframe", limits.maxMorphKeyframes, MORPH_FRAME_BYTES) {
			VmdMorphKeyframe(
				input.readFixedText(15, textEncoding),
				input.readUnsignedInt32("morph frame"),
				input.readFloat32(),
			)
		}
		val cameraKeyframes = readOptionalSection(input, "camera keyframe", limits.maxCameraKeyframes, CAMERA_FRAME_BYTES) {
			readCameraKeyframe(input)
		}
		val lightKeyframes = readOptionalSection(input, "light keyframe", limits.maxLightKeyframes, LIGHT_FRAME_BYTES) {
			VmdLightKeyframe(
				input.readUnsignedInt32("light frame"),
				VmdColor(input.readFloat32(), input.readFloat32(), input.readFloat32()),
				readVector3(input),
			)
		}
		val shadowKeyframes = readOptionalSection(input, "shadow keyframe", limits.maxShadowKeyframes, SHADOW_FRAME_BYTES) {
			VmdShadowKeyframe(
				input.readUnsignedInt32("shadow frame"),
				input.readUnsignedByte(),
				input.readFloat32(),
			)
		}
		val ikKeyframes = if (input.remaining == 0) emptyList() else readIkKeyframes(input, limits)
		input.requireEnd()
		return VmdMotion(
			modelName,
			boneKeyframes,
			morphKeyframes,
			cameraKeyframes,
			lightKeyframes,
			shadowKeyframes,
			ikKeyframes,
		)
	}

	private fun readBoneKeyframe(input: VmdBinaryInput): VmdBoneKeyframe {
		val name = input.readFixedText(15, textEncoding)
		val frame = input.readUnsignedInt32("bone frame")
		val translation = readVector3(input)
		val rotation = VmdQuaternion(input.readFloat32(), input.readFloat32(), input.readFloat32(), input.readFloat32())
		val interpolation = input.readBytes(64)
		fun curve(column: Int): VmdBezier = VmdBezier(
			x1 = (interpolation[column].toInt() and 0xFF).coerceAtMost(127) / 127f,
			y1 = (interpolation[column + 4].toInt() and 0xFF).coerceAtMost(127) / 127f,
			x2 = (interpolation[column + 8].toInt() and 0xFF).coerceAtMost(127) / 127f,
			y2 = (interpolation[column + 12].toInt() and 0xFF).coerceAtMost(127) / 127f,
		)
		return VmdBoneKeyframe(
			name,
			frame,
			translation,
			rotation,
			VmdBoneInterpolation(curve(0), curve(1), curve(2), curve(3)),
		)
	}

	private fun readCameraKeyframe(input: VmdBinaryInput): VmdCameraKeyframe = VmdCameraKeyframe(
		frame = input.readUnsignedInt32("camera frame"),
		distance = input.readFloat32(),
		position = readVector3(input),
		rotation = readVector3(input),
		interpolation = input.readBytes(24).map { it.toInt() and 0xFF },
		fieldOfView = input.readUnsignedInt32("camera field of view"),
		perspective = when (val value = input.readUnsignedByte()) {
			0 -> true
			1 -> false
			else -> throw VmdFormatException("Unsupported camera perspective flag $value", input.position - 1)
		},
	)

	private fun readIkKeyframes(input: VmdBinaryInput, limits: VmdDecodeLimits): List<VmdIkKeyframe> {
		val count = readCount(input, "IK keyframe", limits.maxIkKeyframes)
		var remainingStates = limits.maxIkStates
		return List(count) {
			val frame = input.readUnsignedInt32("IK frame")
			val visible = readBoolean(input, "IK visibility")
			val stateCount = readCount(input, "IK state", remainingStates)
			remainingStates -= stateCount
			val states = List(stateCount) {
				VmdIkState(input.readFixedText(20, textEncoding), readBoolean(input, "IK enabled"))
			}
			VmdIkKeyframe(frame, visible, states)
		}
	}

	private fun readVector3(input: VmdBinaryInput): VmdVector3 =
		VmdVector3(input.readFloat32(), input.readFloat32(), input.readFloat32())

	private fun readBoolean(input: VmdBinaryInput, label: String): Boolean = when (val value = input.readUnsignedByte()) {
		0 -> false
		1 -> true
		else -> throw VmdFormatException("Unsupported $label flag $value", input.position - 1)
	}

	private fun readCount(input: VmdBinaryInput, label: String, maximum: Int): Int {
		val offset = input.position
		val count = input.readUnsignedInt32("$label count")
		if (count > maximum) throw VmdFormatException("$label count $count exceeds limit $maximum", offset)
		return count
	}

	private inline fun <T> readSection(
		input: VmdBinaryInput,
		label: String,
		maximum: Int,
		minimumFrameBytes: Int,
		readFrame: () -> T,
	): List<T> {
		val count = readCount(input, label, maximum)
		if (count.toLong() * minimumFrameBytes > input.remaining) {
			throw VmdFormatException("$label section is truncated", input.position)
		}
		return List(count) { readFrame() }
	}

	private inline fun <T> readOptionalSection(
		input: VmdBinaryInput,
		label: String,
		maximum: Int,
		minimumFrameBytes: Int,
		readFrame: () -> T,
	): List<T> = if (input.remaining == 0) emptyList() else readSection(input, label, maximum, minimumFrameBytes, readFrame)

	private const val BONE_FRAME_BYTES = 111
	private const val MORPH_FRAME_BYTES = 23
	private const val CAMERA_FRAME_BYTES = 61
	private const val LIGHT_FRAME_BYTES = 28
	private const val SHADOW_FRAME_BYTES = 9
}
