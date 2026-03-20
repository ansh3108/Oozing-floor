package com.lumen.oozing;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class OozingMod implements ModInitializer {
    public static boolean isGameActive = true;
    private final AtomicInteger playerCounter = new AtomicInteger(0);
    private final Map<UUID, Integer> playerLives = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            playerLives.put(player.getUuid(), 3);
            
            int index = playerCounter.getAndIncrement() % 3;
            BlockPos startPos = (index == 0) ? SumoConfig.BOX_A : (index == 1) ? SumoConfig.BOX_B : SumoConfig.BOX_C;

            teleportPlayer(server.getOverworld(), player, startPos);
            player.changeGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
            player.getInventory().insertStack(new ItemStack(Items.WIND_CHARGE, 30));
            
            player.sendMessage(Text.literal("§6§lBATTLE START! §e3 Lives Remaining."), false);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!isGameActive) return;

            ServerWorld world = server.getOverworld();
            var players = server.getPlayerManager().getPlayerList();
            int survivalCount = 0;
            ServerPlayerEntity potentialWinner = null;

            for (ServerPlayerEntity player : players) {
                if(player.interactionManager.getGameMode() == GameMode.SURVIVAL) {
                    survivalCount++;
                    potentialWinner = player;

                    if(player.getY() < SumoConfig.VOID_Y) {
                        UUID uuid = player.getUuid();
                        int lives = playerLives.getOrDefault(uuid, 1) - 1;
                        playerLives.put(uuid, lives);

                        BlockPos fallLocation = player.getBlockPos();
                        spawnSlimeHazard(world, new BlockPos(fallLocation.getX(), (int)SumoConfig.ARENA_CENTER.getY(), fallLocation.getZ()));

                        if(lives > 0) {
                            teleportPlayer(world, player, SumoConfig.LOBBY_SPAWN);
                            player.sendMessage(Text.literal("§c§lOuch! §eLives left: " + lives), true);
                            player.playSound(SoundEvents.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
                        } else {
                            player.changeGameMode(GameMode.SPECTATOR);
                            teleportPlayer(world, player, SumoConfig.LOBBY_SPAWN);
                            player.sendMessage(Text.literal("§4§lELIMINATED! §7You are now a spectator."), false);
                            player.playSound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
                        }
                    }
                    if(player.getInventory().count(Items.WIND_CHARGE) <= 0) {
                        player.getInventory().insertStack(new ItemStack(Items.WIND_CHARGE, 30));
                    }
                }
            }
            if(survivalCount == 1 && players.size() > 1) {
                isGameActive = false;
                String winnerName = potentialWinner.getName().getString();
                for (ServerPlayerEntity p : players) {
                    p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§6§l" + winnerName)));
                    p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§ehas won the Oozing Floor!")));
                    p.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                }
            }
            if(server.getTicks() % 400 == 0) {
                for (ServerPlayerEntity p : players) {
                    if (p.interactionManager.getGameMode() == GameMode.SURVIVAL) {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.OOZING, 400, 0));
                        p.playSound(SoundEvents.BLOCK_SLIME_BLOCK_BREAK, 1.0f, 0.5f);
                    }
                }
            }
        });
        SumoCommand.register();
    }
    private void teleportPlayer(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        player.teleport(world, (double)pos.getX() + 0.5, (double)pos.getY(), (double)pos.getZ() + 0.5, Collections.emptySet(), 0.0f, 0.0f, true);
    }
    private void spawnSlimeHazard(ServerWorld world, BlockPos pos) {
        SlimeEntity slime = EntityType.SLIME.spawn(world, pos, SpawnReason.EVENT);
        if (slime != null) slime.setSize(1, true);
    }
}