package com.micheanl.libmmd.client.bootstrap;

import com.micheanl.libmmd.client.command.ClientCommandRegistrar;
import com.micheanl.libmmd.client.model.ModelController;
import com.micheanl.libmmd.client.render.ModelPipeline;
import com.micheanl.libmmd.client.render.PlayerRenderManager;
import com.micheanl.libmmd.client.render.RenderFeature;
import com.micheanl.libmmd.client.render.RenderNode;
import com.micheanl.libmmd.client.runtime.ClientNativeRuntime;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.util.HashSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

@Environment(EnvType.CLIENT)
public final class ClientBootstrap implements ClientModInitializer {
    private static ClientNativeRuntime nativeRuntime;
    private static ModelController models;
    private static PlayerRenderManager players;

    @Override
    public void onInitializeClient() {
        nativeRuntime = ClientNativeRuntime.open();
        try {
            models = new ModelController(nativeRuntime.runtime());
            players = new PlayerRenderManager(nativeRuntime.scene(), models);
            RenderPipelines.register(ModelPipeline.INSTANCE);
            ClientCommandRegistrar.register(models, players);
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.level == null) {
                    players.clear();
                } else {
                    var activeIds = new HashSet<Integer>();
                    for (var player : client.level.players()) {
                        activeIds.add(player.getId());
                        players.sample(player, player == client.player && client.gameMode != null && client.gameMode.isDestroying());
                    }
                    players.retain(activeIds);
                }
                nativeRuntime.update(1.0f / 20.0f);
            });
            FeatureRendererRegistry.register(RenderNode.TYPE, RenderFeature::new);
            Runtime.getRuntime().addShutdownHook(new Thread(ClientBootstrap::close, "model-runtime-shutdown"));
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public static boolean submitPlayer(
        AvatarRenderState state,
        SubmitNodeCollector collector,
        CameraRenderState camera
    ) {
        return players != null && players.submit(state, collector, camera);
    }

    public static void triggerAction(Player player, String action, InteractionHand hand) {
        if (players != null) players.trigger(player, action, hand);
    }

    private static synchronized void close() {
        if (players != null) {
            players.close();
            players = null;
        }
        if (models != null) {
            models.close();
            models = null;
        }
        if (nativeRuntime != null) {
            nativeRuntime.close();
            nativeRuntime = null;
        }
    }
}
