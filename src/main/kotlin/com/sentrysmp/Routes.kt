package com.sentrysmp

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.plugins.swagger.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun Application.configureRoutes(plugin: JavaPlugin, apiKey: String) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        // simple health/root endpoint (allow unauthenticated access for health checks)
        get("/") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
        // serve swagger UI from resources
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")

        // API routes
        route("") {
            intercept(ApplicationCallPipeline.Plugins) {
                // allow swagger and root without API key
                if (call.request.path() == "/" || call.request.path().startsWith("/swagger")) return@intercept
                val key = call.request.header("X-API-Key")
                if (key == null || key != apiKey) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                    finish()
                }
            }

            post("/command") {
                val req = call.receive<CommandRequest>()
                val res = dispatchCommand(plugin, req.command)
                call.respond(res)
            }

            get("/players") {
                val res = fetchPlayers(plugin)
                call.respond(res)
            }

            get("/banlist") {
                val res = fetchBanlist(plugin)
                call.respond(res)
            }
        }
    }
}

private suspend fun dispatchCommand(plugin: JavaPlugin, command: String): CommandResponse =
    suspendCancellableCoroutine { cont ->
        // Dispatch command on the main server thread. Capturing CommandSender output is
        // brittle across Paper API versions (message method signatures changed), so
        // return success flag without captured messages.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val success = Bukkit.dispatchCommand(plugin.server.consoleSender, command)
                cont.resume(CommandResponse(listOf(), success))
            } catch (e: Exception) {
                cont.resume(CommandResponse(listOf(e.message ?: "error"), false))
            }
        })
    }

private suspend fun fetchPlayers(plugin: JavaPlugin): PlayersResponse = suspendCancellableCoroutine { cont ->
    Bukkit.getScheduler().runTask(plugin, Runnable {
        val players = Bukkit.getOnlinePlayers().map { p -> PlayerInfo(p.name, p.uniqueId.toString()) }
        cont.resume(PlayersResponse(players))
    })
}

private suspend fun fetchBanlist(plugin: JavaPlugin): BanlistResponse = suspendCancellableCoroutine { cont ->
    Bukkit.getScheduler().runTask(plugin, Runnable {
        val banList = Bukkit.getBanList(io.papermc.paper.ban.BanListType.PROFILE)
        val reasonByUuid = HashMap<java.util.UUID, String?>()
        for (entry in banList.getEntries<org.bukkit.BanEntry<com.destroystokyo.paper.profile.PlayerProfile>>()) {
            val uuid = entry.banTarget.id
            if (uuid != null) reasonByUuid[uuid] = entry.reason
        }
        val banned = Bukkit.getBannedPlayers().map { p ->
            BanEntry(p.name ?: "", p.uniqueId.toString(), reasonByUuid[p.uniqueId])
        }
        cont.resume(BanlistResponse(banned))
    })
}

// Note: capturing CommandSender messages was omitted due to API signature differences
// across Paper versions (Adventure components vs String messages). If you need
// captured console output, we can implement a version-specific adapter.
