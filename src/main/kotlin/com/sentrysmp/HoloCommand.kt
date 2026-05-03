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
    private val holoConfigFile = File(plugin.dataFolder, "holo-sessions.yml")

    private data class HoloInstance(var location: Location, var stands: List<ArmorStand>, var refreshTaskId: Int?, var ttlTaskId: Int?, val range: String)

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

    private fun saveHoloConfig(key: String, loc: Location, range: String) {
        try {
            plugin.dataFolder.mkdirs()
            val cfg = org.bukkit.configuration.file.YamlConfiguration()
            if (holoConfigFile.exists()) cfg.load(holoConfigFile)
            cfg.set("$key.world", loc.world?.name ?: return)
            cfg.set("$key.x", loc.x)
            cfg.set("$key.y", loc.y)
            cfg.set("$key.z", loc.z)
            cfg.set("$key.range", range)
            cfg.save(holoConfigFile)
        } catch (_: Exception) {}
    }

    private fun removeHoloConfig(key: String) {
        try {
            if (!holoConfigFile.exists()) return
            val cfg = org.bukkit.configuration.file.YamlConfiguration()
            cfg.load(holoConfigFile)
            cfg.set(key, null)
            if (cfg.getKeys(false).isEmpty()) holoConfigFile.delete()
            else cfg.save(holoConfigFile)
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
        if (args.isEmpty() || args[0].lowercase() != "holo") {
            sender.sendMessage("Usage: /sentrysmp holo <add <name> [range]|remove <name>|list>")
            return true
        }
        if (args.size < 2) {
            sender.sendMessage("Usage: /sentrysmp holo <add <name> [range]|remove <name>|list>")
            return true
        }

        val sub = args[1].lowercase()
        when (sub) {
            "list" -> {
                if (active.isEmpty()) {
                    sender.sendMessage("No active holograms.")
                } else {
                    sender.sendMessage("Active holograms (${active.size}):")
                    for ((name, inst) in active) {
                        val loc = inst.location
                        val world = loc.world?.name ?: "?"
                        sender.sendMessage("  - $name [${inst.range}] @ $world ${loc.blockX},${loc.blockY},${loc.blockZ}")
                    }
                }
                return true
            }
            "remove" -> {
                if (args.size < 3) {
                    sender.sendMessage("Usage: /sentrysmp holo remove <name>")
                    return true
                }
                val name = args[2]
                val removed = active.remove(name)
                if (removed == null) {
                    sender.sendMessage("No hologram named '$name'.")
                } else {
                    removed.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                    removed.ttlTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        renderer.removeHologram(removed.stands)
                        unpersistStands(removed.stands)
                        removeHoloConfig(name)
                    })
                    sender.sendMessage("Hologram '$name' removed.")
                }
                return true
            }
            "add" -> {
                if (args.size < 3) {
                    sender.sendMessage("Usage: /sentrysmp holo add <name> [range]")
                    return true
                }
                val name = args[2]
                val range = if (args.size >= 4) args[3].lowercase() else "all"
                if (range !in setOf("all", "today", "week", "month")) {
                    sender.sendMessage("Unknown range: $range. Use all, today, week, or month.")
                    return true
                }
                val player = sender as? Player
                val loc: Location = if (sender is Player) {
                    sender.location.add(0.0, 1.0, 0.0)
                } else {
                    Bukkit.getWorlds().firstOrNull()?.spawnLocation ?: Location(Bukkit.getWorlds().first(), 0.0, 64.0, 0.0)
                }
                saveHoloConfig(name, loc, range)
                spawnHologramForKey(name, loc, range, player) { count ->
                    sender.sendMessage("Hologram '$name' spawned with $count lines for range $range.")
                }
                return true
            }
            else -> {
                sender.sendMessage("Unknown subcommand: $sub. Use add, remove, or list.")
                return true
            }
        }
    }

    private fun toSmallCaps(s: String): String {
        val map = mapOf(
            'A' to 'ᴀ', 'B' to 'ʙ', 'C' to 'ᴄ', 'D' to 'ᴅ', 'E' to 'ᴇ', 'F' to 'ꜰ', 'G' to 'ɢ',
            'H' to 'ʜ', 'I' to 'ɪ', 'J' to 'ᴊ', 'K' to 'ᴋ', 'L' to 'ʟ', 'M' to 'ᴍ', 'N' to 'ɴ',
            'O' to 'ᴏ', 'P' to 'ᴘ', 'Q' to 'ǫ', 'R' to 'ʀ', 'S' to 'ꜱ', 'T' to 'ᴛ', 'U' to 'ᴜ',
            'V' to 'ᴠ', 'W' to 'ᴡ', 'X' to 'x', 'Y' to 'ʏ', 'Z' to 'ᴢ'
        )
        return s.uppercase().map { ch -> map[ch] ?: ch }.joinToString("")
    }

    private fun formatPaid(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.2f", d)

    private fun buildLines(entries: List<ScoreEntry>, player: Player?): List<String> {
        val title = "${ChatColor.RED}${ChatColor.BOLD}€ STORE €${ChatColor.RESET}"
        val subtitle = "${ChatColor.GRAY}${toSmallCaps("LEADERBOARD")}${ChatColor.RESET}"
        val rawRows = entries.take(10).map { e ->
            val rankText = "${ChatColor.RED}${e.rank}.${ChatColor.RESET}"
            val prefix = getLuckPermsPrefix(e.minecraftUsername)
            val prefixText = if (prefix.isNotEmpty()) "$prefix " else ""
            val nameText = "${ChatColor.WHITE}${e.minecraftUsername}${ChatColor.RESET}"
            val scoreText = "${ChatColor.RED}${formatPaid(e.totalPaid)}€${ChatColor.RESET}"
            "$rankText $prefixText$nameText $scoreText"
        }
        val rows = if (player != null) {
            rawRows.map { line ->
                try {
                    val pclazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                    val method = pclazz.getMethod("setPlaceholders", org.bukkit.entity.Player::class.java, String::class.java)
                    (method.invoke(null, player, line) as? String) ?: line
                } catch (t: Throwable) { line }
            }
        } else rawRows
        return listOf(title, subtitle) + rows
    }

    private fun spawnHologramForKey(
        key: String,
        loc: Location,
        range: String,
        player: Player? = null,
        onComplete: ((Int) -> Unit)? = null
    ) {
        val fetcher: () -> ScoreboardResponse? = when (range) {
            "all" -> { { client.getAll() } }
            "today" -> { { client.getToday() } }
            "week" -> { { client.getWeek() } }
            "month" -> { { client.getMonth() } }
            else -> return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val resp = fetcher() ?: return@Runnable
            val lines = buildLines(resp.entries, player)

            Bukkit.getScheduler().runTask(plugin, Runnable {
                active.remove(key)?.let { prev ->
                    prev.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                    prev.ttlTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                    renderer.removeHologram(prev.stands)
                    unpersistStands(prev.stands)
                }

                val stands = renderer.spawnHologram(loc, lines)
                if (stands.isNotEmpty()) {
                    val instance = HoloInstance(loc, stands, null, null, range)
                    active[key] = instance
                    persistStands(stands)

                    if (hologramTtlSeconds > 0L) {
                        val ticks = hologramTtlSeconds * 20L
                        val ttlTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            active.remove(key)?.let { inst ->
                                inst.refreshTaskId?.let { try { Bukkit.getScheduler().cancelTask(it) } catch (_: Exception) {} }
                                renderer.removeHologram(inst.stands)
                                unpersistStands(inst.stands)
                            }
                            removeHoloConfig(key)
                        }, ticks)
                        try { instance.ttlTaskId = ttlTask.taskId } catch (_: NoSuchMethodError) {}
                    }

                    val refreshTicks = Math.max(1L, hologramRefreshSeconds) * 20L
                    val task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                        val updated = fetcher() ?: return@Runnable
                        val lines2 = buildLines(updated.entries, player)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            active[key]?.let { inst ->
                                val oldStands = inst.stands
                                val newStands = renderer.updateHologram(oldStands, inst.location, lines2)
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

                    try { instance.refreshTaskId = task.taskId } catch (_: NoSuchMethodError) {}
                }
                onComplete?.invoke(stands.size)
            })
        })
    }

    fun restoreHolograms() {
        if (!holoConfigFile.exists()) return
        try {
            val cfg = org.bukkit.configuration.file.YamlConfiguration()
            cfg.load(holoConfigFile)
            for (key in cfg.getKeys(false)) {
                val worldName = cfg.getString("$key.world") ?: continue
                val world = Bukkit.getWorld(worldName) ?: continue
                val x = cfg.getDouble("$key.x")
                val y = cfg.getDouble("$key.y")
                val z = cfg.getDouble("$key.z")
                val range = cfg.getString("$key.range") ?: "all"
                val loc = Location(world, x, y, z)
                spawnHologramForKey(key, loc, range)
            }
        } catch (_: Exception) {}
    }
}
