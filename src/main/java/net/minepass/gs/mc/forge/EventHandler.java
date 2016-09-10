/*
 *  This file is part of MinePass, licensed under the MIT License (MIT).
 *
 *  Copyright (c) MinePass.net <http://www.minepass.net>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package net.minepass.gs.mc.forge;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSettings;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minepass.api.gameserver.MPPlayer;
import net.minepass.gs.GameserverTasks;
import net.minepass.gs.mc.MinePassMC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EventHandler {

    private final MP_ForgeMod mod;
    private final MinePassMC minepass;

    private final int taskTriggerSeconds = 2;
    private Integer taskTriggerTicks;
    private Integer taskTriggerCounter;
    private GameserverTasks tasks;

    /**
     * The most backward compatible way to access a list of current
     * players by UUID is to maintain the list via login/out events.
     */
    private HashMap<UUID, EntityPlayerMP> currentPlayers;

    public EventHandler(final MP_ForgeMod mod) {
        this.mod = mod;
        this.minepass = mod.getMinepass();
        this.currentPlayers = new HashMap<>();
        this.taskTriggerTicks = taskTriggerSeconds * 20;
        this.taskTriggerCounter = 0;
        this.tasks = new GameserverTasks(minepass) {
            @Override
            protected Map<UUID, String> getCurrentPlayers() {
                HashMap<UUID, String> players = new HashMap<>(currentPlayers.size());
                for (UUID id : currentPlayers.keySet()) {
                    players.put(id, currentPlayers.get(id).getCommandSenderName());
                }
                return players;
            }

            @Override
            protected void updateAndReloadLocalAuth() {
                minepass.updateLocalWhitelist();
                mod.getMinecraftServer().getConfigurationManager().loadWhiteList();
                mod.logger.info("Whitelist updated");
            }

            @Override
            protected void kickPlayer(UUID playerId, String message) {
                EntityPlayerMP p = currentPlayers.get(playerId);
                if (p != null) p.playerNetServerHandler.kickPlayerFromServer(message);
            }

            @Override
            protected void warnPlayer(UUID playerId, String message) {
                EntityPlayerMP p = currentPlayers.get(playerId);
                if (p != null) {
                    p.addChatComponentMessage(new ChatComponentText(ChatFormatting.GOLD + message));
                }
            }

            @Override
            protected void warnPlayerPass(UUID playerId, String message) {
                EntityPlayerMP p = currentPlayers.get(playerId);
                if (p != null) {
                    p.addChatComponentMessage(IChatComponent.Serializer.func_150699_a(String.format(
                            "[\"\",{\"text\":\"%s\",\"color\":\"aqua\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"%s\"}}]",
                            message,
                            minepass.getServer().join_url
                    )));
                    p.addChatComponentMessage(IChatComponent.Serializer.func_150699_a(String.format(
                            "[\"\",{\"text\":\"%s\",\"color\":\"aqua\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"%s\"}}]",
                            "Press '/' then click this message to get your MinePass.",
                            minepass.getServer().join_url
                    )));
                }
            }
        };
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayerMP forgePlayer = (EntityPlayerMP) event.player;
        UUID playerUUID = forgePlayer.getUniqueID();
        MPPlayer player = minepass.getPlayer(playerUUID);

        currentPlayers.put(playerUUID, forgePlayer);

        if (player != null) {
            WorldSettings.GameType minecraftGameMode = null;
            Pattern privPattern = Pattern.compile("mc:(?<name>[a-z]+)");
            Pattern commandPattern = Pattern.compile("mc:/(?<command>.+)");

            Matcher pm;
            for (String p : player.privileges) {
                if ((pm = privPattern.matcher(p)).find()) {
                    // Standard privileges.
                    //
                    switch (pm.group("name")) {
                        case "default":
                            minecraftGameMode = WorldSettings.GameType.NOT_SET;
                            break;
                        case "survival":
                            minecraftGameMode = WorldSettings.GameType.SURVIVAL;
                            break;
                        case "creative":
                            minecraftGameMode = WorldSettings.GameType.CREATIVE;
                            break;
                        case "adventure":
                            minecraftGameMode = WorldSettings.GameType.ADVENTURE;
                            break;
                    }
                } else if ((pm = commandPattern.matcher(p)).find()) {
                    // Command privileges.
                    //
                    String command = pm.group("command");
                    command = command.replaceAll("\\$name", player.name);
                    command = command.replaceAll("\\$uuid", playerUUID.toString());
                    minepass.log.debug("Sending login command: ".concat(command), this);
                    mod.getMinecraftServer().getCommandManager().executeCommand(
                            mod.getMinecraftServer(),
                            command
                    );
                }
            }

            if (minecraftGameMode != null) {
                if (!forgePlayer.theItemInWorldManager.getGameType().equals(minecraftGameMode)) {
                    if (minecraftGameMode != WorldSettings.GameType.NOT_SET) {
                        forgePlayer.setGameType(minecraftGameMode);
                    }
                }
            } else {
                forgePlayer.playerNetServerHandler.kickPlayerFromServer("Your current MinePass does not permit access to this server.");
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayerMP forgePlayer = (EntityPlayerMP) event.player;
        UUID playerUUID = forgePlayer.getUniqueID();

        currentPlayers.remove(playerUUID);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTick(TickEvent.ServerTickEvent event) {
        taskTriggerCounter++;
        if (taskTriggerCounter >= taskTriggerTicks) {
            taskTriggerCounter = 0;
            tasks.runTasks();
        }
    }
}
