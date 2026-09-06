package com.micheanl.libmmd.asset

class AssetLoadException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)
