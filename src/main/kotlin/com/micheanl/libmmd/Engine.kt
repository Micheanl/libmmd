package com.micheanl.libmmd

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object Engine : ModInitializer {
	const val MOD_ID: String = "libmmd"
	val logger = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
