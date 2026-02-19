package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

@Serializable
data class HistoryEntry(
    val speciesName: String,
    val timestamp: Long
)

object SearchHistory {
    private const val MAX_ENTRIES = 50
    private var entries: MutableList<HistoryEntry> = mutableListOf()

    private val SAVE_PATH: Path = FabricLoader.getInstance()
        .configDir.resolve("hunterboard_history.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun addEntry(species: Species) {
        // Use resourceIdentifier path for reliable lookup (e.g. "mr_mime" instead of "mr. mime")
        val name = try {
            species.resourceIdentifier.path
        } catch (_: Exception) {
            species.name.lowercase()
        }
        // Remove old entries (both new and legacy format)
        val sanitized = name.replace(" ", "_").replace(".", "")
        entries.removeAll {
            it.speciesName == name || it.speciesName == sanitized ||
                    it.speciesName.replace(" ", "_").replace(".", "") == sanitized
        }
        entries.add(0, HistoryEntry(name, System.currentTimeMillis()))
        if (entries.size > MAX_ENTRIES) {
            entries = entries.take(MAX_ENTRIES).toMutableList()
        }
        save()
    }

    fun getSpeciesList(): List<Species> {
        return entries.mapNotNull { entry ->
            try {
                PokemonSpecies.getByName(entry.speciesName)
            } catch (_: Exception) {
                // Legacy entries may have invalid chars (e.g. "mr. mime") — sanitize and retry
                try {
                    val sanitized = entry.speciesName.replace(" ", "_").replace(".", "")
                    PokemonSpecies.getByName(sanitized)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun load() {
        try {
            val file = SAVE_PATH.toFile()
            if (file.exists()) {
                val data = json.decodeFromString<SaveData>(file.readText())
                entries = data.entries.toMutableList()
                HunterBoard.LOGGER.info("Loaded ${entries.size} search history entries")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load search history: ${e.message}")
        }
    }

    private fun save() {
        try {
            SAVE_PATH.parent.toFile().mkdirs()
            val data = SaveData(entries.toList())
            SAVE_PATH.toFile().writeText(json.encodeToString(SaveData.serializer(), data))
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to save search history: ${e.message}")
        }
    }

    @Serializable
    private data class SaveData(
        val entries: List<HistoryEntry> = emptyList()
    )
}
