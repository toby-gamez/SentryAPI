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
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import kotlin.coroutines.resume

private val sharedHttpClient: HttpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val voucherJson = Json { ignoreUnknownKeys = true }

private val COMMAND_BLACKLIST = setOf(
    "ban", "ban-ip", "pardon", "pardon-ip",
    "kick",
    "op", "deop",
    "stop", "restart",
    "reload",
    "time", "weather",
    "whitelist"
)

fun Application.configureRoutes(plugin: JavaPlugin, apiKey: String, apiBaseUrl: String, port: Int = 8080) {
    install(ContentNegotiation) {
        json()
    }

    install(CORS) {
        allowHost("localhost:$port", schemes = listOf("http"))
        allowHost("127.0.0.1:$port", schemes = listOf("http"))
        allowHost("www.sentrysmp.eu", schemes = listOf("https"))
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

                // require playerName and ensure the player is currently online
                if (req.playerName.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing playerName"))
                val playerName = req.playerName
                val currentPlayers = fetchPlayers(plugin)
                val isOnline = currentPlayers.players.any { it.name.equals(playerName, ignoreCase = true) }
                if (!isOnline) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Player '$playerName' is not connected"))

                // price must be positive
                if (req.price <= 0.0) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Price must be > 0"))

                // cart is expected as JSON array (List<CartItem>) and is deserialized automatically
                val cartItems = req.cart

                // compute cart total
                val cartTotal = cartItems.map { it.item.price * it.quantity }.sum()

                // determine discountPercent (0 if no voucher provided)
                var discountPercent = 0.0
                if (!req.voucher.isNullOrBlank()) {
                    val voucher = fetchVoucher(apiBaseUrl, req.voucher)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Voucher not found"))
                    if (!voucher.isActive) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Voucher is not active"))
                    discountPercent = voucher.discountPercent
                }

                // minimum allowed price: voucher discount + 5% extra tolerance
                val minAllowedPrice = cartTotal * (1.0 - (discountPercent + 5.0) / 100.0)
                if (req.price < minAllowedPrice) {
                    val body = ValidationErrorResponse(
                        error = "Price validation failed",
                        minAllowedPrice = minAllowedPrice,
                        cartTotal = cartTotal,
                        discountPercent = discountPercent
                    )
                    return@post call.respond(HttpStatusCode.BadRequest, body)
                }

                // all checks passed — dispatch one or more commands
                val outputs = mutableListOf<String>()
                var overallSuccess = true
                for (cmd in req.commands) {
                    val cmdName = cmd.trimStart().split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase() ?: ""
                    if (cmdName in COMMAND_BLACKLIST) {
                        return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Command '$cmdName' is not allowed"))
                    }
                    val r = dispatchCommand(plugin, cmd)
                    outputs.addAll(r.output)
                    if (!r.success) overallSuccess = false
                }
                // broadcast store message to all online players
                try {
                    val itemCount = cartItems.map { it.quantity }.sum()
                    val storeComp = net.kyori.adventure.text.Component.text("[STORE]")
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, net.kyori.adventure.text.format.TextDecoration.State.TRUE)

                    val playerComp = net.kyori.adventure.text.Component.text(" ").append(
                        net.kyori.adventure.text.Component.text(playerName)
                            .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, net.kyori.adventure.text.format.TextDecoration.State.FALSE)
                    )

                    val middleComp = net.kyori.adventure.text.Component.text(" just bought ")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, net.kyori.adventure.text.format.TextDecoration.State.FALSE)

                    val itemsComp = net.kyori.adventure.text.Component.text("$itemCount items!")
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, net.kyori.adventure.text.format.TextDecoration.State.FALSE)

                    val msg = storeComp.append(playerComp).append(middleComp).append(itemsComp)

                    // send on main server thread
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        for (p in Bukkit.getOnlinePlayers()) {
                            try { p.sendMessage(msg) } catch (_: Exception) { }
                        }
                        // launch a red firework at the purchasing player
                        try {
                            val buyer = Bukkit.getPlayerExact(playerName)
                            if (buyer != null) {
                                val firework = buyer.world.spawn(buyer.location, org.bukkit.entity.Firework::class.java)
                                val meta = firework.fireworkMeta
                                val effect = org.bukkit.FireworkEffect.builder()
                                    .with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                                    .withColor(org.bukkit.Color.RED)
                                    .build()
                                meta.addEffect(effect)
                                meta.power = 1
                                firework.fireworkMeta = meta
                            }
                        } catch (_: Exception) { }
                    })
                } catch (_: Exception) {
                    // don't fail the request if broadcasting fails
                }

                call.respond(CommandResponse(outputs, overallSuccess))
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
        val lp = try { net.luckperms.api.LuckPermsProvider.get() } catch (_: Exception) { null }
        val players = Bukkit.getOnlinePlayers().map { p ->
            val rank: String? = try {
                lp?.userManager?.getUser(p.uniqueId)
                    ?.cachedData
                    ?.getMetaData(net.luckperms.api.query.QueryOptions.nonContextual())
                    ?.prefix
                    ?.replace(Regex("&#[0-9A-Fa-f]{6}"), "")
                    ?.replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }
            PlayerInfo(p.name, p.uniqueId.toString(), rank)
        }
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

private fun fetchVoucher(baseUrl: String, code: String): VoucherResponse? {
    val encoded = java.net.URLEncoder.encode(code, "UTF-8")
    val url = if (baseUrl.endsWith("/")) "${baseUrl}Vouchers/$encoded" else "$baseUrl/Vouchers/$encoded"
    return try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val resp = sharedHttpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        voucherJson.decodeFromString(VoucherResponse.serializer(), resp.body())
    } catch (e: Exception) {
        null
    }
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
                ?.replace(Regex("&#[0-9A-Fa-f]{6}"), "")
                ?.replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else null
    } catch (e: Exception) {
        null
    }

    // Fetch Bukkit statistics for online players on the main thread
    val statistics: com.sentrysmp.PlayerStatistics? = try {
        suspendCancellableCoroutine { cont ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    val onlinePlayer = Bukkit.getPlayer(playerName)
                    if (onlinePlayer != null) {
                        // Compute play time in a clear, explicit way to avoid unit confusion.
                        var playTicks = 0L
                        var playSeconds = 0L
                        try {
                            val stat = try {
                                org.bukkit.Statistic.valueOf("PLAY_ONE_TICK")
                            } catch (_: Exception) {
                                try { org.bukkit.Statistic.valueOf("PLAY_ONE_MINUTE") } catch (_: Exception) { null }
                            }
                            if (stat != null) {
                                val statVal = try { onlinePlayer.getStatistic(stat).toLong() } catch (_: Exception) { 0L }
                                // NOTE: on many server versions `PLAY_ONE_MINUTE` actually reports ticks
                                // (legacy name) — treat either statistic value as ticks to avoid overcounting.
                                playTicks = statVal
                                playSeconds = if (playTicks > 0L) playTicks / 20L else 0L
                            }
                        } catch (_: Exception) {
                            playTicks = 0L
                            playSeconds = 0L
                        }
                        val deaths = try { onlinePlayer.getStatistic(org.bukkit.Statistic.DEATHS).toLong() } catch (_: Exception) { 0L }
                        val playerKills = try { onlinePlayer.getStatistic(org.bukkit.Statistic.PLAYER_KILLS).toLong() } catch (_: Exception) { 0L }
                        val mobsKilled = try { onlinePlayer.getStatistic(org.bukkit.Statistic.MOB_KILLS).toLong() } catch (_: Exception) { 0L }
                        // aggregate various "one cm" movement statistics where available
                        val travelStatNames = listOf(
                            "WALK_ONE_CM",
                            "SPRINT_ONE_CM",
                            "FLY_ONE_CM",
                            "SWIM_ONE_CM",
                            "WALK_ON_WATER_ONE_CM",
                            "CLIMB_ONE_CM",
                            "WALK_UNDER_WATER_ONE_CM"
                        )
                        var totalTravelCm = 0L
                        for (name in travelStatNames) {
                            try {
                                val stat = org.bukkit.Statistic.valueOf(name)
                                val v = try { onlinePlayer.getStatistic(stat).toLong() } catch (_: Exception) { 0L }
                                totalTravelCm += v
                            } catch (_: Exception) {
                                // stat not present on this server/Paper version
                            }
                        }
                        val blocksTravelled = totalTravelCm / 100L
                        cont.resume(com.sentrysmp.PlayerStatistics(
                            playTimeSeconds = playSeconds.takeIf { it > 0L },
                            playTimeTicks = playTicks.takeIf { it > 0L },
                            deaths = deaths.takeIf { it > 0L },
                            playerKills = playerKills.takeIf { it > 0L },
                            mobsKilled = mobsKilled.takeIf { it > 0L },
                            blocksTravelled = blocksTravelled.takeIf { it > 0L }
                        ))
                    } else {
                        cont.resume(null)
                    }
                } catch (e: Exception) {
                    cont.resume(null)
                }
            })
        }
    } catch (_: Exception) { null }

    return PlayerStatsResponse(playerName, partial.coins, partial.money, rank, statistics)
}
