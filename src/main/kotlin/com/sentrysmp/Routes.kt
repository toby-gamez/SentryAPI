package com.sentrysmp

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.plugins.cors.routing.*
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

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-API-Key")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
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

            get("/player/{name}") {
                val playerName = call.parameters["name"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing player name"))
                val res = fetchPlayerStats(plugin, playerName)
                call.respond(res)
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

/** Captures all log4j2 messages published while the appender is registered. */
private class Log4jCapture(name: String) : org.apache.logging.log4j.core.appender.AbstractAppender(
    name, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY
) {
    val messages = mutableListOf<String>()
    override fun append(event: org.apache.logging.log4j.core.LogEvent) {
        messages.add(event.message.formattedMessage)
    }
}

/**
 * Delegates everything to the real console sender but intercepts all sendMessage
 * variants (legacy String API + Adventure Component API) to capture output.
 */
private class CapturingCommandSender(
    private val delegate: org.bukkit.command.ConsoleCommandSender
) : org.bukkit.command.ConsoleCommandSender by delegate {
    val captured = mutableListOf<String>()
    private val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()

    // Legacy Bukkit API
    override fun sendMessage(message: String) { captured.add(message) }
    override fun sendMessage(vararg messages: String) { captured.addAll(messages) }

    // Adventure API — primary overload used by modern Paper plugins
    override fun sendMessage(message: net.kyori.adventure.text.Component) {
        captured.add(plain.serialize(message))
    }

    // Deprecated Adventure overloads — still emitted by some older plugins
    @Suppress("DEPRECATION", "UnstableApiUsage")
    override fun sendMessage(
        source: net.kyori.adventure.identity.Identified,
        message: net.kyori.adventure.text.Component,
        type: net.kyori.adventure.audience.MessageType
    ) { captured.add(plain.serialize(message)) }

    @Suppress("DEPRECATION", "UnstableApiUsage")
    override fun sendMessage(
        source: net.kyori.adventure.identity.Identity,
        message: net.kyori.adventure.text.Component,
        type: net.kyori.adventure.audience.MessageType
    ) { captured.add(plain.serialize(message)) }
}

private fun runCapturing(
    plugin: JavaPlugin,
    command: String,
    console: org.bukkit.command.ConsoleCommandSender
): String {
    val sender = CapturingCommandSender(console)
    // Paper uses log4j2; plugins (e.g. Essentials, PlayerPoints) log responses there
    val appenderName = "SentryCapture-${Thread.currentThread().id}"
    val capture = Log4jCapture(appenderName)
    capture.start()
    val ctx = org.apache.logging.log4j.LogManager.getContext(false)
            as org.apache.logging.log4j.core.LoggerContext
    ctx.configuration.rootLogger.addAppender(capture, org.apache.logging.log4j.Level.INFO, null)
    ctx.updateLoggers()
    try {
        Bukkit.dispatchCommand(sender, command)
    } finally {
        ctx.configuration.rootLogger.removeAppender(appenderName)
        ctx.updateLoggers()
        capture.stop()
    }
    return (sender.captured + capture.messages).joinToString(" ")
}

private suspend fun fetchPlayerStats(plugin: JavaPlugin, playerName: String): PlayerStatsResponse {
    // Fetch coins and money on the main thread (Bukkit API requirement)
    data class StatsPartial(val coins: Long?, val money: Double?, val error: String?)
    val partial = suspendCancellableCoroutine<StatsPartial> { cont ->
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val console = plugin.server.consoleSender

                val ansi = Regex("\u001B\\[[\\d;]*m")
                val coinsText = ansi.replace(runCapturing(plugin, "points look $playerName", console), "")
                val moneyText = ansi.replace(runCapturing(plugin, "money $playerName", console), "")

                // coins: grab the first comma-formatted integer (e.g. "1,000")
                val coins = Regex("[\\d,]+").findAll(coinsText)
                    .map { it.value.replace(",", "") }
                    .firstOrNull { it.all(Char::isDigit) && it.isNotEmpty() }
                    ?.toLongOrNull()

                // money: grab number that follows "$" or just the first decimal number
                val money = (Regex("\\$([\\d,]+(?:\\.\\d+)?)").find(moneyText)?.groupValues?.get(1)
                    ?: Regex("[\\d,]+(?:\\.\\d+)?").findAll(moneyText)
                        .map { it.value }
                        .firstOrNull { it.replace(",", "").toDoubleOrNull() != null })
                    ?.replace(",", "")?.toDoubleOrNull()

                cont.resume(StatsPartial(coins, money, null))
            } catch (e: Exception) {
                cont.resume(StatsPartial(null, null, e.message))
            }
        })
    }

    if (partial.error != null) return PlayerStatsResponse(playerName, error = partial.error)

    // Fetch rank via LuckPerms API (handles async user loading properly)
    val rank: String? = try {
        val lp = net.luckperms.api.LuckPermsProvider.get()

        // Resolve UUID: instant for online players, async LP lookup for offline
        val uuid: java.util.UUID? = suspendCancellableCoroutine { cont ->
            val onlinePlayer = Bukkit.getPlayer(playerName)
            if (onlinePlayer != null) {
                cont.resume(onlinePlayer.uniqueId)
            } else {
                lp.userManager.lookupUniqueId(playerName).whenComplete { id, _ -> cont.resume(id) }
            }
        }

        if (uuid != null) {
            // Load user: instant if cached (online player), async otherwise
            val user: net.luckperms.api.model.user.User? = suspendCancellableCoroutine { cont ->
                val cached = lp.userManager.getUser(uuid)
                if (cached != null) {
                    cont.resume(cached)
                } else {
                    lp.userManager.loadUser(uuid).whenComplete { u, _ -> cont.resume(u) }
                }
            }
            user?.cachedData
                ?.getMetaData(net.luckperms.api.query.QueryOptions.nonContextual())
                ?.prefix
                ?.takeIf { it.isNotEmpty() }
        } else null
    } catch (e: Exception) {
        null
    }

    return PlayerStatsResponse(playerName, partial.coins, partial.money, rank)
}
