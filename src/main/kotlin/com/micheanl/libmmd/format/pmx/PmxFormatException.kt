package com.micheanl.libmmd.format.pmx

class PmxFormatException(
	message: String,
	val byteOffset: Int,
	cause: Throwable? = null,
) : IllegalArgumentException("$message at byte $byteOffset", cause)
