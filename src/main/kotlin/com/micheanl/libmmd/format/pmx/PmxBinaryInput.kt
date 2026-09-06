package com.micheanl.libmmd.format.pmx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class PmxBinaryInput(bytes: ByteArray) {
	private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

	val position: Int
		get() = buffer.position()

	fun readInt8(): Int {
		requireRemaining(Byte.SIZE_BYTES)
		return buffer.get().toInt()
	}

	fun readUnsignedByte(): Int {
		requireRemaining(Byte.SIZE_BYTES)
		return buffer.get().toInt() and 0xFF
	}

	fun readInt16(): Int {
		requireRemaining(Short.SIZE_BYTES)
		return buffer.short.toInt()
	}

	fun readUnsignedInt16(): Int {
		requireRemaining(Short.SIZE_BYTES)
		return buffer.short.toInt() and 0xFFFF
	}

	fun readInt32(): Int {
		requireRemaining(Int.SIZE_BYTES)
		return buffer.int
	}

	fun readFloat32(): Float {
		requireRemaining(Float.SIZE_BYTES)
		return buffer.float
	}

	fun readBytes(length: Int): ByteArray {
		if (length < 0) {
			fail("Negative byte count $length")
		}
		requireRemaining(length)
		return ByteArray(length).also(buffer::get)
	}

	fun readText(encoding: PmxTextEncoding): String {
		val byteLength = readInt32()
		val textOffset = position
		val bytes = readBytes(byteLength)
		val charset = when (encoding) {
			PmxTextEncoding.UTF_16_LE -> StandardCharsets.UTF_16LE
			PmxTextEncoding.UTF_8 -> StandardCharsets.UTF_8
		}
		return try {
			charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString()
		} catch (exception: Exception) {
			throw PmxFormatException("Invalid ${encoding.name} text", textOffset, exception)
		}
	}

	fun fail(message: String): Nothing = throw PmxFormatException(message, position)

	fun requireEnd() {
		if (buffer.hasRemaining()) {
			fail("Unexpected ${buffer.remaining()} trailing bytes")
		}
	}

	private fun requireRemaining(byteCount: Int) {
		if (buffer.remaining() < byteCount) {
			fail("Expected $byteCount bytes but only ${buffer.remaining()} remain")
		}
	}
}
