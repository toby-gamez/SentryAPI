package com.sentrysmp

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.plugin.java.JavaPlugin

class HologramRenderer(private val plugin: JavaPlugin) {
    fun spawnHologram(location: Location, lines: List<String>): List<ArmorStand> {
        val world = location.world ?: return emptyList()
        val stands = mutableListOf<ArmorStand>()
        // Start slightly above the location
        var y = location.y + lines.size * 0.25
        for (line in lines) {
            val loc = Location(world, location.x, y, location.z)
            val ent = world.spawnEntity(loc, EntityType.ARMOR_STAND) as ArmorStand
            ent.isVisible = false
            ent.setGravity(false)
            try { ent.isMarker = true } catch (_: NoSuchMethodError) {}
            ent.isCustomNameVisible = true
            try {
                ent.setCustomName(line)
            } catch (_: NoSuchMethodError) {
                try { ent.customName = line } catch (_: NoSuchMethodError) {}
            }
            try { ent.isInvulnerable = true } catch (_: NoSuchMethodError) {}
            try { ent.setBasePlate(false) } catch (_: NoSuchMethodError) {}
            stands.add(ent)
            y -= 0.25
        }
        return stands
    }

    fun updateHologram(stands: List<ArmorStand>, anchor: Location, lines: List<String>): List<ArmorStand> {
        val world = anchor.world ?: return stands
        val mutable = stands.toMutableList()
        // ensure we have enough stands
        if (lines.size > mutable.size) {
            var y = anchor.y + lines.size * 0.25
            // existing stands will be repositioned below; create new ones above as needed
            for (i in mutable.size until lines.size) {
                val loc = Location(world, anchor.x, y, anchor.z)
                val ent = world.spawnEntity(loc, EntityType.ARMOR_STAND) as ArmorStand
                ent.isVisible = false
                ent.setGravity(false)
                try { ent.isMarker = true } catch (_: NoSuchMethodError) {}
                ent.isCustomNameVisible = true
                try { ent.setCustomName(lines[i]) } catch (_: NoSuchMethodError) { try { ent.customName = lines[i] } catch (_: NoSuchMethodError) {} }
                try { ent.isInvulnerable = true } catch (_: NoSuchMethodError) {}
                try { ent.setBasePlate(false) } catch (_: NoSuchMethodError) {}
                mutable.add(ent)
                y -= 0.25
            }
        }

        // if there are more stands than lines, remove extras
        if (lines.size < mutable.size) {
            val toRemove = mutable.size - lines.size
            for (i in 0 until toRemove) {
                val idx = mutable.size - 1
                try { mutable[idx].remove() } catch (_: Exception) {}
                mutable.removeAt(idx)
            }
        }

        // reposition and update names
        var y = anchor.y + lines.size * 0.25
        for (i in 0 until lines.size) {
            val ent = mutable[i]
            try { ent.teleport(Location(world, anchor.x, y, anchor.z)) } catch (_: Exception) {}
            try { ent.setCustomName(lines[i]) } catch (_: NoSuchMethodError) { try { ent.customName = lines[i] } catch (_: NoSuchMethodError) {} }
            y -= 0.25
        }

        return mutable.toList()
    }

    fun removeHologram(stands: List<ArmorStand>) {
        for (s in stands) {
            try { s.remove() } catch (_: Exception) {}
        }
    }
}
