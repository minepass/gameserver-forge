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

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minepass.api.gameserver.MPAsciiArt;
import net.minepass.api.gameserver.MPConfigException;
import net.minepass.api.gameserver.MPStartupException;
import net.minepass.api.gameserver.MPWorldServerDetails;
import net.minepass.api.gameserver.embed.solidtx.TxLog;
import net.minepass.api.gameserver.embed.solidtx.log.TxLogOutput;
import net.minepass.gs.mc.MinePassMC;
import org.apache.logging.log4j.Logger;
import net.minepass.api.gameserver.embed.solidtx.TxStack;
import net.minepass.api.gameserver.embed.solidtx.TxSync;

@Mod(modid = MP_ForgeMod.MODID, version = MP_ForgeMod.VERSION, acceptableRemoteVersions = "*")
public class MP_ForgeMod {

    public static final String MODID = "minepass-forge";
    public static final String VERSION = "@VERSION@";

    public Logger logger;

    private MinePassMC minepass;
    private EventHandler eventHandler;
    private Thread syncThread;
    private Boolean debug;
    private String api_host;
    private String server_uuid;
    private String server_secret;


    public MinePassMC getMinepass() {
        return minepass;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("Loading MinePass configuration");

        Configuration config = new Configuration(event.getSuggestedConfigurationFile());

        config.load();

        this.debug = config.get(Configuration.CATEGORY_GENERAL, "debug_enabled", false).getBoolean();
        this.api_host = config.get(Configuration.CATEGORY_GENERAL, "setup_api_host", "").getString();
        this.server_uuid = config.get(Configuration.CATEGORY_GENERAL, "setup_server_id", "").getString();
        this.server_secret = config.get(Configuration.CATEGORY_GENERAL, "setup_server_secret", "").getString();

        config.save();

        if (debug) {
            TxStack.debug = true;
        }

        // Redirect SolidTX output through Forge logger.
        TxLog.output = new TxLogOutput() {
            @Override
            public void sendLine(TxLog.Level level, String output) {
                output = output.replaceFirst("[^ ]+ ", "");  // remove timestamp
                output = output.replaceFirst("/.+\\[", "/");     // remove level
                switch (level) {
                    case DEBUG:
                        if (TxStack.debug) {
                            logger.info(output);
                        }
                        break;
                    case INFO:
                        logger.info(output);
                        break;
                    case WARN:
                        logger.warn(output);
                        break;
                    case ERROR:
                        logger.error(output);
                        break;
                }
            }
        };
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        try {
            /**
             * The MinePass network stack is built upon SolidTX, an MIT licensed project
             * developed in collaboration with BinaryBabel OSS.
             *
             * The source code for the MinePass game server stack is available at:
             *   https://github.com/minepass/gameserver-core
             *
             * The source code and documentation for SolidTX is available at:
             *   https://github.com/org-binbab/solid-tx
             *
             */
            this.minepass = new MinePassMC(
                    "ForgePlugin ".concat(MP_ForgeMod.VERSION), api_host, server_uuid, server_secret
            );
            minepass.setContext(this);

            logger.info("MinePass Core Version: " + minepass.getVersion());
            logger.info("MinePass API Endpoint: " + api_host);
            logger.info("MinePass World Server UUID: " + minepass.getServerUUID());
        } catch (MPConfigException e) {
            for (String x : MPAsciiArt.getNotice("Configuration Update Required")) {
                logger.info(x);
            }
            logger.warn("Run the server configuration wizard at http://minepass.net");
            logger.warn("Then paste the configuration into config/minepass-forge.cfg");
            return;
        } catch (MPStartupException e) {
            e.printStackTrace();
            return;
        }

        // Register handler for game events.
        eventHandler = new EventHandler(this);
        MinecraftForge.EVENT_BUS.register(eventHandler);
        FMLCommonHandler.instance().bus().register(eventHandler);
    }

    @Mod.EventHandler
    public void postStart(FMLServerStartedEvent event) {
        if (minepass != null && minepass.getServer() != null) {
            logger.info("Requiring whitelist enabled.");
            MinecraftServer.getServer().getConfigurationManager().loadWhiteList();
            MinecraftServer.getServer().getConfigurationManager().setWhiteListEnabled(true);

            // Start sync thread.
            syncThread = new Thread(new TxSync(minepass, 10));
            syncThread.setDaemon(false);  // ensure any disk writing finishes
            syncThread.start();
            for (String x : MPAsciiArt.getLogo("System Ready")) {
                logger.info(x);
            }

            // Send server details.
            MPWorldServerDetails details = new MPWorldServerDetails();
            details.game_version = MinecraftServer.getServer().getMinecraftVersion()
                    + " / " + MinecraftForge.getBrandingVersion();
            for (ModContainer m : Loader.instance().getModList()) {
                String mainClass = "";
                if (m.getMod() != null) {
                    mainClass = m.getMod().getClass().getName();
                }
                details.addPlugin(m.getName(), m.getVersion(), mainClass);
            }
            if (!minepass.getServer().whitelist_imported) {
                details.importWhitelist(MinePassMC.whitelistBackupFile);
            }
            minepass.sendObject(details, null);
        } else {
            logger.warn("MinePass shutting down server due to missing configuration.");
            MinecraftServer.getServer().initiateShutdown();
        }
    }

    @Mod.EventHandler
    public void preStop(FMLServerStoppingEvent event) {
        // Stop sync thread.
        if (syncThread != null) {
            syncThread.interrupt();
        }
    }
}
