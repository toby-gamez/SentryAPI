package com.sentrysmp

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.NettyApplicationEngine
import java.util.concurrent.TimeUnit
import org.bukkit.plugin.java.JavaPlugin

class CommandApiPlugin : JavaPlugin() {
    private var httpServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var holoCommand: HoloCommand? = null

    override fun onEnable() {
        saveDefaultConfig()
        val port = config.getInt("port", 8080)
        val apiKey = config.getString("api-key") ?: "change-me"

        // build scoreboard client from config
        val scoreboardBase = config.getString("scoreboard-base-url") ?: "https://www.sentrysmp.eu/api"
        val scoreboardCacheTtl = config.getLong("scoreboard-cache-ttl" )
        val cacheTtl = if (scoreboardCacheTtl <= 0L) 60L else scoreboardCacheTtl
        val client = ScoreboardClient(scoreboardBase, cacheTtl)

        val holoTtl = config.getLong("hologram-ttl-seconds")
        val hologramTtl = if (holoTtl < 0L) 0L else holoTtl
        val holoRefresh = config.getLong("hologram-refresh-seconds")
        val hologramRefresh = if (holoRefresh <= 0L) 5L else holoRefresh

        // clean up any stands left over from a previous (crashed) session
        HoloCommand.cleanupOrphanedStands(this)

        // register command executor
        val holo = HoloCommand(this, client, hologramTtl, hologramRefresh)
        holoCommand = holo
        this.getCommand("sentrysmp")?.setExecutor(holo)

        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            configureRoutes(this@CommandApiPlugin, apiKey)
        }
        server.start(false)
        httpServer = server
        logger.info("SentryAPI HTTP server started on 127.0.0.1:$port")
    }

    override fun onDisable() {
        holoCommand?.removeAll()
        try {
                httpServer?.stop(1000, 5000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.warning("Error stopping HTTP server: ${e.message}")
        }
        logger.info("SentryAPI HTTP server stopped")
    }
}
