package com.micheanl.libmmd.format.vmd

class VmdFormatException(message: String, val byteOffset: Int) : IllegalArgumentException("$message at byte $byteOffset")
