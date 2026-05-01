package com.sentrysmp

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.NettyApplicationEngine
import java.util.concurrent.TimeUnit
import org.bukkit.plugin.java.JavaPlugin

class CommandApiPlugin : JavaPlugin() {
    private var httpServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    override fun onEnable() {
        saveDefaultConfig()
        val port = config.getInt("port", 8080)
        val apiKey = config.getString("api-key") ?: "change-me"

        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            configureRoutes(this@CommandApiPlugin, apiKey)
        }
        server.start(false)
        httpServer = server
        logger.info("SentryAPI HTTP server started on 127.0.0.1:$port")
    }

    override fun onDisable() {
        try {
                httpServer?.stop(1000, 5000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.warning("Error stopping HTTP server: ${e.message}")
        }
        logger.info("SentryAPI HTTP server stopped")
    }
}
