package com.micheanl.libmmd

import com.micheanl.libmmd.render.ModelFeatureRenderer
import com.micheanl.libmmd.render.ModelPipelines
import com.micheanl.libmmd.render.WorldModels
import com.micheanl.libmmd.interaction.InteractionController
import com.micheanl.libmmd.player.PlayerModels
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry

object ClientBootstrap : ClientModInitializer {
	override fun onInitializeClient() {
		ModelPipelines.register()
		FeatureRendererRegistry.register(ModelFeatureRenderer.TYPE, ::ModelFeatureRenderer)
		PlayerModels.register()
		WorldModels.register()
		InteractionController.register()
	}
}
