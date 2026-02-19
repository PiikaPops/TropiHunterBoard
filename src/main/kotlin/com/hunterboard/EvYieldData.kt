package com.hunterboard

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Loads EV yield data from Cobblemon's species JSON files.
 * Fallback for servers where species.evYield is not synced to the client.
 */
object EvYieldData {

    // Map of species name (lowercase) -> stat name -> ev value
    private var evMap: Map<String, Map<String, Int>> = emptyMap()
    var isLoaded = false
        private set

    private val STAT_KEYS = listOf("hp", "attack", "defence", "special_attack", "special_defence", "speed")

    fun ensureLoaded() {
        if (isLoaded) return
        loadFromResources()
    }

    /**
     * Returns EV yield for a species as a list of "StatLabel value" strings.
     * Returns null if no data found (caller should fallback to species.evYield).
     */
    fun getEvYield(speciesName: String): Map<String, Int>? {
        ensureLoaded()
        return evMap[speciesName.lowercase()]
    }

    private fun loadFromResources() {
        try {
            val cobblemonMod = FabricLoader.getInstance()
                .getModContainer("cobblemon").orElse(null)
            if (cobblemonMod == null) {
                HunterBoard.LOGGER.warn("Cobblemon mod not found, cannot load EV yield data")
                return
            }

            val map = mutableMapOf<String, Map<String, Int>>()

            for (rootPath in cobblemonMod.rootPaths) {
                val speciesDir = rootPath.resolve("data/cobblemon/species")
                if (!Files.exists(speciesDir)) continue

                Files.walk(speciesDir)
                    .filter { it.toString().endsWith(".json") }
                    .forEach { file ->
                        try {
                            val content = Files.readString(file)
                            parseSpeciesFile(content, map)
                        } catch (_: Exception) {}
                    }
            }

            if (map.isNotEmpty()) {
                evMap = map
                isLoaded = true
                HunterBoard.LOGGER.info("Loaded EV yield data for ${map.size} species")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to load EV yield data: ${e.message}")
        }
    }

    private fun parseSpeciesFile(json: String, map: MutableMap<String, Map<String, Int>>) {
        val root = JsonParser.parseString(json).asJsonObject
        val name = root.get("name")?.asString?.lowercase() ?: return
        val evYieldObj = root.getAsJsonObject("evYield") ?: return

        val evs = mutableMapOf<String, Int>()
        for (key in STAT_KEYS) {
            val value = evYieldObj.get(key)?.asInt ?: 0
            if (value > 0) evs[key] = value
        }
        if (evs.isNotEmpty()) {
            map[name] = evs
        }
    }
}
