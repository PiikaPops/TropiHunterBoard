package com.hunterboard

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Loads Pokemon move data from Cobblemon's species JSON files.
 * Fallback for server play where species.moves doesn't sync TM/Egg/Tutor moves.
 */
object MoveData {

    data class MoveSet(
        val levelUp: List<Pair<Int, MoveTemplate>> = emptyList(),
        val tm: List<MoveTemplate> = emptyList(),
        val egg: List<MoveTemplate> = emptyList(),
        val tutor: List<MoveTemplate> = emptyList()
    )

    private val cache = mutableMapOf<String, MoveSet>()
    private var speciesFiles: Map<String, java.nio.file.Path>? = null

    fun getMoves(speciesName: String): MoveSet? {
        val key = speciesName.lowercase()
        cache[key]?.let { return it }
        return loadSpeciesMoves(key)
    }

    private fun loadSpeciesMoves(speciesName: String): MoveSet? {
        try {
            ensureSpeciesIndex()
            val file = speciesFiles?.get(speciesName) ?: return null
            val content = Files.readString(file)
            val root = JsonParser.parseString(content).asJsonObject
            val movesArray = root.getAsJsonArray("moves") ?: return null

            val levelUp = mutableListOf<Pair<Int, MoveTemplate>>()
            val tm = mutableListOf<MoveTemplate>()
            val egg = mutableListOf<MoveTemplate>()
            val tutor = mutableListOf<MoveTemplate>()

            for (element in movesArray) {
                val str = element.asString
                val colonIdx = str.indexOf(':')
                if (colonIdx < 0) continue

                val prefix = str.substring(0, colonIdx)
                val moveName = str.substring(colonIdx + 1)
                val template = Moves.getByName(moveName) ?: continue

                when {
                    prefix == "tm" -> tm.add(template)
                    prefix == "egg" -> egg.add(template)
                    prefix == "tutor" -> tutor.add(template)
                    prefix.toIntOrNull() != null -> levelUp.add(prefix.toInt() to template)
                }
            }

            val moveSet = MoveSet(
                levelUp = levelUp.sortedBy { it.first },
                tm = tm.sortedBy { it.displayName.string },
                egg = egg.sortedBy { it.displayName.string },
                tutor = tutor.sortedBy { it.displayName.string }
            )
            cache[speciesName] = moveSet
            return moveSet
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("Failed to load moves for $speciesName: ${e.message}")
            return null
        }
    }

    private fun ensureSpeciesIndex() {
        if (speciesFiles != null) return

        val cobblemonMod = FabricLoader.getInstance()
            .getModContainer("cobblemon").orElse(null) ?: return

        val index = mutableMapOf<String, java.nio.file.Path>()

        for (rootPath in cobblemonMod.rootPaths) {
            val speciesDir = rootPath.resolve("data/cobblemon/species")
            if (!Files.exists(speciesDir)) continue

            Files.walk(speciesDir)
                .filter { it.toString().endsWith(".json") }
                .forEach { file ->
                    // File name = species name (e.g. "bulbasaur.json")
                    val name = file.fileName.toString().removeSuffix(".json").lowercase()
                    index[name] = file
                }
        }

        speciesFiles = index
        HunterBoard.LOGGER.info("Indexed ${index.size} species files from Cobblemon JAR")
    }
}
