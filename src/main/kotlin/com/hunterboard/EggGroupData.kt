package com.hunterboard

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Loads egg group data from Cobblemon's species JSON files.
 * Fallback for servers where species.eggGroups is not synced to the client.
 */
object EggGroupData {

    // Map of species name (lowercase) -> list of egg group names (lowercase)
    private var eggGroupMap: Map<String, List<String>> = emptyMap()
    var isLoaded = false
        private set

    fun ensureLoaded() {
        if (isLoaded) return
        loadFromResources()
    }

    /**
     * Returns egg groups for a species as a list of lowercase strings.
     * Returns null if no local data found (caller should fallback to species.eggGroups).
     */
    fun getEggGroups(speciesName: String): List<String>? {
        ensureLoaded()
        return eggGroupMap[speciesName.lowercase()]
    }

    private fun loadFromResources() {
        try {
            val cobblemonMod = FabricLoader.getInstance()
                .getModContainer("cobblemon").orElse(null)
            if (cobblemonMod == null) {
                HunterBoard.LOGGER.warn("Cobblemon mod not found, cannot load egg group data")
                return
            }

            val map = mutableMapOf<String, List<String>>()

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
                eggGroupMap = map
                isLoaded = true
                HunterBoard.LOGGER.info("Loaded egg group data for ${map.size} species")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to load egg group data: ${e.message}")
        }
    }

    private fun parseSpeciesFile(json: String, map: MutableMap<String, List<String>>) {
        val root = JsonParser.parseString(json).asJsonObject
        val name = root.get("name")?.asString?.lowercase() ?: return
        val arr = root.getAsJsonArray("eggGroups") ?: return
        val groups = arr.mapNotNull { it?.asString?.lowercase() }.filter { it.isNotEmpty() }
        if (groups.isNotEmpty()) {
            map[name] = groups
        }
    }
}
