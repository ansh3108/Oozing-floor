package com.lumen.oozing;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

public class SumoCommand {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("sumopos")
                .executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player != null) {
                        var pos = player.getBlockPos();
                        String output = "new BlockPos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
                        context.getSource().sendFeedback(() -> Text.literal("§6Config: §f" + output), false);
                    }
                    return 1;
                }));
        });
    }
}