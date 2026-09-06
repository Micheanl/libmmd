package com.micheanl.libmmd.client.command;

import com.micheanl.libmmd.client.model.ModelController;
import com.micheanl.libmmd.client.render.PlayerRenderManager;
import com.micheanl.libmmd.client.animation.ActionCatalog;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public final class ClientCommandRegistrar {
    private ClientCommandRegistrar() {}

    public static void register(ModelController models, PlayerRenderManager players) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommands.literal("libmmd")
                .then(ClientCommands.literal("action")
                    .then(ClientCommands.literal("list").executes(context -> {
                        context.getSource().sendFeedback(Component.translatable(
                            "libmmd.action.list", ActionCatalog.bundled().names().size()));
                        return 1;
                    }))
                    .then(ClientCommands.literal("play")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                for (var name : ActionCatalog.bundled().names()) {
                                    if (name.startsWith(builder.getRemaining())) builder.suggest(name);
                                }
                                return builder.buildFuture();
                            })
                            .executes(context -> {
                                try {
                                    var name = StringArgumentType.getString(context, "name");
                                    models.previewAction(name);
                                    context.getSource().sendFeedback(Component.translatable("libmmd.action.play", name));
                                    return 1;
                                } catch (RuntimeException failure) {
                                    context.getSource().sendError(Component.translatable("libmmd.action.failed", failure.getMessage()));
                                    return 0;
                                }
                            })))
                    .then(ClientCommands.literal("stop").executes(context -> {
                        models.stopPreview();
                        context.getSource().sendFeedback(Component.translatable("libmmd.action.stopped"));
                        return 1;
                    })))
                .then(ClientCommands.literal("load")
                    .then(ClientCommands.argument("path", StringArgumentType.greedyString())
                        .executes(context -> {
                            var path = Path.of(StringArgumentType.getString(context, "path"));
                            try {
                                players.clear();
                                models.load(path);
                                players.validateModel();
                                context.getSource().sendFeedback(Component.literal("Model loaded: " + models.packPath()));
                                return 1;
                            } catch (RuntimeException failure) {
                                context.getSource().sendError(Component.literal("Model load failed: " + failure.getMessage()));
                                return 0;
                            }
                        })))
                .then(ClientCommands.literal("unload").executes(context -> {
                    players.clear();
                    models.unload();
                    context.getSource().sendFeedback(Component.literal("Model unloaded"));
                    return 1;
                }))
        ));
    }
}
