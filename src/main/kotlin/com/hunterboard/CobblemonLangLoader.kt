package com.hunterboard

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

object CobblemonLangLoader {

    private var enSpeciesNames: Map<String, String>? = null
    private var frSpeciesNames: Map<String, String>? = null
    private var enMoveNames: Map<String, String>? = null
    private var frMoveNames: Map<String, String>? = null
    private var loaded = false

    /** Normalize species key: strip all non-alphanumeric for consistent lookup */
    private fun normalizeKey(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val cobblemonMod = FabricLoader.getInstance()
                .getModContainer("cobblemon").orElse(null) ?: return

            for (rootPath in cobblemonMod.rootPaths) {
                val enFile = rootPath.resolve("assets/cobblemon/lang/en_us.json")
                val frFile = rootPath.resolve("assets/cobblemon/lang/fr_fr.json")

                if (Files.exists(enFile)) {
                    val (species, moves) = parseLangFile(enFile)
                    enSpeciesNames = species
                    enMoveNames = moves
                }
                if (Files.exists(frFile)) {
                    val (species, moves) = parseLangFile(frFile)
                    frSpeciesNames = species
                    frMoveNames = moves
                }

                if (enSpeciesNames != null) break
            }
            HunterBoard.LOGGER.info("Loaded Cobblemon lang: ${enSpeciesNames?.size ?: 0} EN species, ${frSpeciesNames?.size ?: 0} FR species")
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load Cobblemon lang files: ${e.message}")
        }
    }

    private fun parseLangFile(path: java.nio.file.Path): Pair<Map<String, String>, Map<String, String>> {
        val species = mutableMapOf<String, String>()
        val moves = mutableMapOf<String, String>()

        val content = Files.readString(path)
        val root = JsonParser.parseString(content).asJsonObject

        for ((key, value) in root.entrySet()) {
            when {
                key.startsWith("cobblemon.species.") && key.endsWith(".name") -> {
                    val speciesId = key.removePrefix("cobblemon.species.").removeSuffix(".name")
                    species[normalizeKey(speciesId)] = value.asString
                }
                key.startsWith("cobblemon.move.") && key.endsWith(".name") -> {
                    val moveId = key.removePrefix("cobblemon.move.").removeSuffix(".name")
                    moves[moveId] = value.asString
                }
            }
        }

        return species to moves
    }

    fun getEnglishSpeciesName(speciesId: String): String? {
        ensureLoaded()
        return enSpeciesNames?.get(normalizeKey(speciesId))
    }

    fun getFrenchSpeciesName(speciesId: String): String? {
        ensureLoaded()
        return frSpeciesNames?.get(normalizeKey(speciesId))
    }

    fun getEnglishMoveName(moveId: String): String? {
        ensureLoaded()
        return enMoveNames?.get(moveId.lowercase())
    }

    fun getFrenchMoveName(moveId: String): String? {
        ensureLoaded()
        return frMoveNames?.get(moveId.lowercase())
    }
}
