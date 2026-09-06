package com.micheanl.libmmd.format.vmd

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

internal class VmdBinaryInput(bytes: ByteArray) {
	private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

	val position: Int
		get() = buffer.position()

	val remaining: Int
		get() = buffer.remaining()

	fun readUnsignedByte(): Int {
		requireRemaining(1)
		return buffer.get().toInt() and 0xFF
	}

	fun readInt32(): Int {
		requireRemaining(Int.SIZE_BYTES)
		return buffer.int
	}

	fun readUnsignedInt32(label: String): Int {
		val offset = position
		val value = readInt32().toLong() and 0xFFFF_FFFFL
		if (value > Int.MAX_VALUE) throw VmdFormatException("$label $value exceeds JVM limits", offset)
		return value.toInt()
	}

	fun readFloat32(): Float {
		requireRemaining(Float.SIZE_BYTES)
		return buffer.float
	}

	fun readBytes(count: Int): ByteArray {
		require(count >= 0)
		requireRemaining(count)
		return ByteArray(count).also(buffer::get)
	}

	fun readFixedText(byteCount: Int, charset: Charset): String {
		val bytes = readBytes(byteCount)
		val length = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
		return charset.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
	}

	fun requireEnd() {
		if (remaining != 0) throw VmdFormatException("Unexpected trailing data ($remaining bytes)", position)
	}

	private fun requireRemaining(count: Int) {
		if (remaining < count) throw VmdFormatException("Unexpected end of VMD data", position)
	}
}
