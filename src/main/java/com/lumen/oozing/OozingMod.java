package com.lumen.oozing;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class OozingMod implements ModInitializer {
    public static boolean isGameActive = true;
    private final Map<UUID, Integer> playerLives = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private int resetTimer = -1;
    private ScoreboardObjective objective;

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> player.isCreative());

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if(player.isCreative()) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);

            if(stack.isOf(Items.WIND_CHARGE)) return ActionResult.PASS;
            if(stack.getItem() instanceof BlockItem) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if(entity instanceof PlayerEntity victim) {
                lastAttacker.put(victim.getUuid(), player.getUuid());
            }
            return ActionResult.PASS;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            initScoreboard(server);
            var dispatcher = server.getCommandManager().getDispatcher();
            var source = server.getCommandSource();
            try {
                dispatcher.execute("gamerule doMobGriefing false", source);
                dispatcher.execute("gamerule doDaylightCycle false", source);
                dispatcher.execute("gamerule doMobSpawning false", source);
                dispatcher.execute("time set day", source);
            } catch (Exception e) {}

            ServerPlayerEntity player = handler.getPlayer();
            setupPlayer(server, player, server.getPlayerManager().getPlayerList().indexOf(player));
            updateScore(server, player, 3);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerWorld world = server.getOverworld();
            var players = server.getPlayerManager().getPlayerList();
            long ticks = server.getTicks();

            List<Entity> toRemove = new ArrayList<>();
            for(Entity entity : world.iterateEntities()) {
                if(entity instanceof ItemEntity || (!(entity instanceof PlayerEntity) && !(entity instanceof SlimeEntity) && !(entity instanceof ProjectileEntity))) {
                    toRemove.add(entity);
                }
            }
            toRemove.forEach(Entity::discard);

            long timeInCycle = ticks % 600;
            int secondsRemaining = (int) ((600 - timeInCycle) / 20);
            if (secondsRemaining <= 5 && isGameActive && resetTimer <= 0) {
                Text timerText = Text.literal("§eFloor change in: §6§l" + secondsRemaining + "s");
                for (ServerPlayerEntity p : players) {
                    p.networkHandler.sendPacket(new OverlayMessageS2CPacket(timerText));
                    if (ticks % 20 == 0) p.playSound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 0.5f, 2.0f);
                }
            }

            if(resetTimer > 0) {
                resetTimer--;
                if (resetTimer == 0) restartGame(server);
                return;}

            if(!isGameActive) return;

            int survivalCount = 0;
            ServerPlayerEntity potentialWinner = null;

            for (ServerPlayerEntity player : players) {
                if(player.interactionManager.getGameMode() == GameMode.SURVIVAL) {
                    survivalCount++;
                    potentialWinner = player;

                    if(player.getY() < SumoConfig.VOID_Y) {
                        UUID uuid = player.getUuid();
                        int lives = playerLives.getOrDefault(uuid, 3) - 1;
                        playerLives.put(uuid, lives);
                        updateScore(server, player, lives);
                        broadcastKillMessage(server, player);

                        spawnSlimeHazard(world, SumoConfig.ARENA_CENTER.add(0, 1, 0));

                        if(lives > 0) {
                            teleportPlayer(world, player, SumoConfig.LOBBY_SPAWN);
                        } else {
                            player.changeGameMode(GameMode.SPECTATOR);
                            teleportPlayer(world, player, SumoConfig.LOBBY_SPAWN);
                        }
                    }

                    if(player.getInventory().count(Items.WIND_CHARGE) < 64) {
                        player.getInventory().insertStack(new ItemStack(Items.WIND_CHARGE, 64));
                    }
                    if(player.getInventory().count(Items.STICK) <= 0) {
                        giveStick(server, player);
                    }
                }
            }

            if(survivalCount == 1 && players.size() > 1) {
                isGameActive = false;
                resetTimer = 200;
                String winnerName = potentialWinner.getName().getString();
                for(ServerPlayerEntity p : players) {
                    p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§6§l" + winnerName)));
                    p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§eWinner!")));
                }
            }

            if(ticks % 600 == 0) flipFloor(world, (ticks / 600) % 2 == 0);

            if(ticks % 400 == 0) {
                for (ServerPlayerEntity p : players) {
                    if (p.interactionManager.getGameMode() == GameMode.SURVIVAL) {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.OOZING, 400, 0));
                    }
                }
            }
        });

        SumoCommand.register();
    }

    private void spawnSlimeHazard(ServerWorld world, BlockPos pos) {
        SlimeEntity slime = EntityType.SLIME.spawn(world, pos, SpawnReason.EVENT);
        if(slime != null) slime.setSize(2, true); 
    }

    private void flipFloor(ServerWorld world, boolean toHoney) {
    BlockPos center = SumoConfig.ARENA_CENTER;
    int r = SumoConfig.ARENA_RADIUS;
    var state = toHoney ? Blocks.HONEY_BLOCK.getDefaultState() : Blocks.SLIME_BLOCK.getDefaultState();
    
    double floorTop = center.getY() + 1.0; 

    for(ServerPlayerEntity player : world.getPlayers()) {
        if(player.getBlockPos().isWithinDistance(center, r + 2)) {
            double playerY = player.getY();
            
            if(Math.abs(playerY - floorTop) < 1.5) {
                player.teleport(player.getX(), floorTop + 0.5, player.getZ(), false);
                
                player.addVelocity(0, 0.1, 0);
                player.velocityDirty = true;
            }
        }
    }
    for(int x = -r; x <= r; x++) {
        for (int z = -r; z <= r; z++) {
            world.setBlockState(center.add(x, 0, z), state, 3);
        }
    }

    String msg = toHoney ? "§6§lSTRETCHY HONEY!" : "§a§lBOUNCY SLIME!";
    world.getServer().getPlayerManager().broadcast(Text.literal(msg), true);
}
    
    private void giveStick(MinecraftServer server, ServerPlayerEntity player) {
        ItemStack stick = new ItemStack(Items.STICK);
        Registry<Enchantment> registry = server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        Optional<RegistryEntry.Reference<Enchantment>> kbRef = registry.getOptional(Enchantments.KNOCKBACK);
        if (kbRef.isPresent()) {
            ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
            builder.add(kbRef.get(), 2);
            stick.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        }
        stick.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§a§lSumo Stick"));
        player.getInventory().insertStack(stick);
    }

    private void broadcastKillMessage(MinecraftServer server, ServerPlayerEntity victim) {
        UUID attackerUuid = lastAttacker.get(victim.getUuid());
        String victimName = victim.getName().getString();
        if(attackerUuid != null) {
            ServerPlayerEntity attacker = server.getPlayerManager().getPlayer(attackerUuid);
            if(attacker != null && !attacker.getUuid().equals(victim.getUuid())) {
                server.getPlayerManager().broadcast(Text.literal("§f" + victimName + " §7shoved by §6" + attacker.getName().getString()), false);
                lastAttacker.remove(victim.getUuid());
                return;
            }
        }
        server.getPlayerManager().broadcast(Text.literal("§f" + victimName + " §7fell!"), false);
    }

    private void initScoreboard(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective old = scoreboard.getNullableObjective("sumo_lives");
        if (old != null) scoreboard.removeObjective(old);
        objective = scoreboard.addObjective("sumo_lives", ScoreboardCriterion.DUMMY, Text.literal("§6§lOOZING SUMO"), ScoreboardCriterion.RenderType.INTEGER, true, null);
        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);
    }

    private void updateScore(MinecraftServer server, ServerPlayerEntity player, int lives) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreAccess score = scoreboard.getOrCreateScore(player, objective);
        score.setScore(lives);
    }

    private void setupPlayer(MinecraftServer server, ServerPlayerEntity player, int index) {
        playerLives.put(player.getUuid(), 3);
        lastAttacker.remove(player.getUuid());
        BlockPos startPos = (index % 3 == 0) ? SumoConfig.BOX_A : (index % 3 == 1) ? SumoConfig.BOX_B : SumoConfig.BOX_C;
        teleportPlayer(server.getOverworld(), player, startPos);
        player.changeGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().insertStack(new ItemStack(Items.WIND_CHARGE, 64));
        player.getInventory().insertStack(new ItemStack(Items.WIND_CHARGE, 64));
        giveStick(server, player);
    }

    private void restartGame(MinecraftServer server) {
        isGameActive = true;
        initScoreboard(server);
        var players = server.getPlayerManager().getPlayerList();
        for(int i = 0; i < players.size(); i++) {
            setupPlayer(server, players.get(i), i);
            updateScore(server, players.get(i), 3);
        }
        flipFloor(server.getOverworld(), false);
    }

    private void teleportPlayer(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        player.teleport(world, (double)pos.getX() + 0.5, (double)pos.getY(), (double)pos.getZ() + 0.5, Collections.emptySet(), 0.0f, 0.0f, true);
    }
}

 