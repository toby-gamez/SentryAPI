package com.sentrysmp

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HoloCommand(
    private val plugin: JavaPlugin,
    private val client: ScoreboardClient,
    private val hologramTtlSeconds: Long = 60L,
    private val hologramRefreshSeconds: Long = 5L
) : CommandExecutor {
    private val renderer = HologramRenderer(plugin)
    private val holosFile = File(plugin.dataFolder, "holos.txt")

    private data class HoloInstance(var location: Location, var stands: List<ArmorStand>, var refreshTaskId: Int?, var ttlTaskId: Int?)

    private val active = ConcurrentHashMap<String, HoloInstance>()

    private fun persistStands(stands: List<ArmorStand>) {
        try {
            plugin.dataFolder.mkdirs()
            val uuids = stands.joinToString("\n") { it.uniqueId.toString() } + "\n"
            holosFile.appendText(uuids)
        } catch (_: Exception) {}
    }

    private fun unpersistStands(stands: List<ArmorStand>) {
        try {
            if (!holosFile.exists()) return
            val removing = stands.map { it.uniqueId.toString() }.toSet()
            val remaining = holosFile.readLines().filter { it.isNotBlank() && it !in removing }
            if (remaining.isEmpty()) holosFile.delete()
            else holosFile.writeText(remaining.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    fun removeAll() {
        for ((_, inst) in active) {
            inst.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
            inst.ttlTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
            renderer.removeHologram(inst.stands)
        }
        active.clear()
        try { holosFile.delete() } catch (_: Exception) {}
    }

    companion object {
        fun cleanupOrphanedStands(plugin: JavaPlugin) {
            val holosFile = File(plugin.dataFolder, "holos.txt")
            if (!holosFile.exists()) return
            try {
                val uuids = holosFile.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { try { UUID.fromString(it) } catch (_: Exception) { null } }
                    .toSet()
                for (world in Bukkit.getWorlds()) {
                    for (entity in world.entities) {
                        if (entity.uniqueId in uuids) {
                            try { entity.remove() } catch (_: Exception) {}
                        }
                    }
                }
                holosFile.delete()
            } catch (_: Exception) {}
        }
    }

    private fun getLuckPermsPrefix(username: String): String {
        return try {
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return ""
            val lp = net.luckperms.api.LuckPermsProvider.get()
            val online = Bukkit.getPlayerExact(username)
            val user = if (online != null) {
                lp.userManager.getUser(online.uniqueId)
            } else {
                val uuid = lp.userManager.lookupUniqueId(username).join() ?: return ""
                lp.userManager.loadUser(uuid).join()
            } ?: return ""
            val meta = user.cachedData.getMetaData(net.luckperms.api.query.QueryOptions.nonContextual())
            val raw = meta.prefix ?: return ""
            ChatColor.translateAlternateColorCodes('&', raw)
        } catch (t: Throwable) {
            ""
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("Usage: /sentrysmp holo add <all|today|week|month>")
            return true
        }

        if (args[0].lowercase() != "holo") return false
        if (args.size < 2) {
            sender.sendMessage("Usage: /sentrysmp holo <add <range>|remove>")
            return true
        }

        val sub = args[1].lowercase()
        if (sub == "remove") {
            val key = if (sender is Player) sender.name else "console"
            val removed = active.remove(key)
            if (removed == null) {
                sender.sendMessage("No active hologram to remove.")
            } else {
                removed.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                removed.ttlTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    renderer.removeHologram(removed.stands)
                    unpersistStands(removed.stands)
                })
                sender.sendMessage("Hologram removed.")
            }
            return true
        }
        if (sub != "add") {
            sender.sendMessage("Unknown subcommand: $sub")
            return true
        }

        val range = if (args.size >= 3) args[2].lowercase() else "all"
        val fetcher: () -> ScoreboardResponse? = when (range) {
            "all" -> { { client.getAll() } }
            "today" -> { { client.getToday() } }
            "week" -> { { client.getWeek() } }
            "month" -> { { client.getMonth() } }
            else -> {
                sender.sendMessage("Unknown range: ${'$'}range")
                return true
            }
        }

        // fetch async and spawn on main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val resp = fetcher()
            if (resp == null) {
                sender.sendMessage("Failed to fetch scoreboard data for $range")
                return@Runnable
            }

            fun formatPaid(d: Double): String {
                return if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.2f", d)
            }

            // Title + subtitle
            fun toSmallCaps(s: String): String {
                val map = mapOf(
                    'A' to 'ᴀ', 'B' to 'ʙ', 'C' to 'ᴄ', 'D' to 'ᴅ', 'E' to 'ᴇ', 'F' to 'ꜰ', 'G' to 'ɢ',
                    'H' to 'ʜ', 'I' to 'ɪ', 'J' to 'ᴊ', 'K' to 'ᴋ', 'L' to 'ʟ', 'M' to 'ᴍ', 'N' to 'ɴ',
                    'O' to 'ᴏ', 'P' to 'ᴘ', 'Q' to 'ǫ', 'R' to 'ʀ', 'S' to 'ꜱ', 'T' to 'ᴛ', 'U' to 'ᴜ',
                    'V' to 'ᴠ', 'W' to 'ᴡ', 'X' to 'x', 'Y' to 'ʏ', 'Z' to 'ᴢ'
                )
                return s.uppercase().map { ch -> map[ch] ?: ch }.joinToString("")
            }

            val title = "${ChatColor.RED}${ChatColor.BOLD}€ STORE €${ChatColor.RESET}"
            val subtitle = "${ChatColor.GRAY}${toSmallCaps("LEADERBOARD")}${ChatColor.RESET}"

            // Build simple rows with single spaces between columns: rank, prefix, name, score
            val rawRows = resp.entries.take(10).map { e ->
                val rankText = "${ChatColor.RED}${e.rank}.${ChatColor.RESET}"
                val prefix = getLuckPermsPrefix(e.minecraftUsername)
                val prefixText = if (prefix.isNotEmpty()) "$prefix " else ""
                val nameText = "${ChatColor.WHITE}${e.minecraftUsername}${ChatColor.RESET}"
                val scoreText = "${ChatColor.RED}${formatPaid(e.totalPaid)}€${ChatColor.RESET}"
                "$rankText $prefixText$nameText $scoreText"
            }

            val withHeader = listOf(title, subtitle) + rawRows

            val lines = if (sender is Player) {
                val player = sender as Player
                val replaced = rawRows.map { line ->
                    try {
                        val pclazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        val method = pclazz.getMethod("setPlaceholders", org.bukkit.entity.Player::class.java, String::class.java)
                        val replaced = method.invoke(null, player, line) as? String
                        replaced ?: line
                    } catch (t: Throwable) {
                        line
                    }
                }
                listOf(title, subtitle) + replaced
            } else withHeader

            val loc: Location = if (sender is Player) {
                sender.location.add(0.0, 1.0, 0.0)
            } else {
                // console: use world spawn
                Bukkit.getWorlds().firstOrNull()?.spawnLocation ?: Location(Bukkit.getWorlds().first(), 0.0, 64.0, 0.0)
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val key = if (sender is Player) sender.name else "console"
                // remove previous hologram for this sender if present (cancel its updater)
                active.remove(key)?.let { prev ->
                    prev.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                    prev.ttlTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                    renderer.removeHologram(prev.stands)
                    unpersistStands(prev.stands)
                }

                val stands = renderer.spawnHologram(loc, lines)
                if (stands.isNotEmpty()) {
                    val instance = HoloInstance(loc, stands, null, null)
                    active[key] = instance
                    persistStands(stands)

                    // schedule removal after TTL (convert seconds to ticks); 0 means no TTL
                    if (hologramTtlSeconds > 0L) {
                        val ticks = hologramTtlSeconds * 20L
                        val ttlTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            // cancel updater and remove hologram
                            active.remove(key)?.let { inst ->
                                inst.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                                renderer.removeHologram(inst.stands)
                                unpersistStands(inst.stands)
                            }
                        }, ticks)
                        try { instance.ttlTaskId = ttlTask.taskId } catch (_: NoSuchMethodError) { /* best-effort */ }
                    }

                    // schedule periodic refresh: fetch async and update on main thread
                    val refreshTicks = Math.max(1L, hologramRefreshSeconds) * 20L
                    val task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                        val updated = fetcher()
                        if (updated == null) return@Runnable
                        // build new lines similar to above
                        val rawRows2 = updated.entries.take(10).map { e ->
                            val rankText = "${ChatColor.RED}${e.rank}.${ChatColor.RESET}"
                            val prefix = getLuckPermsPrefix(e.minecraftUsername)
                            val prefixText = if (prefix.isNotEmpty()) "$prefix " else ""
                            val nameText = "${ChatColor.WHITE}${e.minecraftUsername}${ChatColor.RESET}"
                            val scoreText = "${ChatColor.RED}${if (e.totalPaid % 1.0 == 0.0) e.totalPaid.toInt().toString() else String.format("%.2f", e.totalPaid)}€${ChatColor.RESET}"
                            "$rankText $prefixText$nameText $scoreText"
                        }
                        val lines2 = if (sender is Player) {
                            val player = sender as Player
                            val replaced = rawRows2.map { line ->
                                try {
                                    val pclazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                                    val method = pclazz.getMethod("setPlaceholders", org.bukkit.entity.Player::class.java, String::class.java)
                                    val replaced = method.invoke(null, player, line) as? String
                                    replaced ?: line
                                } catch (t: Throwable) {
                                    line
                                }
                            }
                            listOf(title, subtitle) + replaced
                        } else listOf(title, subtitle) + rawRows2

                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            active[key]?.let { inst ->
                                val oldStands = inst.stands
                                val newStands = renderer.updateHologram(oldStands, inst.location, lines2)
                                // sync persistence: unpersist removed stands, persist added stands
                                val oldUuids = oldStands.map { it.uniqueId }.toSet()
                                val newUuids = newStands.map { it.uniqueId }.toSet()
                                val removed = oldStands.filter { it.uniqueId !in newUuids }
                                val added = newStands.filter { it.uniqueId !in oldUuids }
                                if (removed.isNotEmpty()) unpersistStands(removed)
                                if (added.isNotEmpty()) persistStands(added)
                                inst.stands = newStands
                            }
                        })
                    }, refreshTicks, refreshTicks)

                    try { instance.refreshTaskId = task.taskId } catch (_: NoSuchMethodError) { /* best-effort */ }
                }
                sender.sendMessage("Spawned hologram with ${stands.size} lines for range $range")
            })
        })

        return true
    }
}
