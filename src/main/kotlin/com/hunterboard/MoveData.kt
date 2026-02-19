package com.hunterboard

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Loads Pokemon move data from Cobblemon's species JSON files.
 * Fallback for server play where species.moves doesn't sync TM/Egg/Tutor moves.
 */
data class MoveLearner(
    val speciesName: String,
    val method: String,   // "level-up", "tm", "egg", "tutor"
    val level: Int = -1   // only for level-up
)

object MoveData {

    // All moves cache
    private var allMovesCache: List<MoveTemplate>? = null

    // Reverse index: move internal name -> list of learners
    private var reverseMoveIndex: Map<String, List<MoveLearner>>? = null
    @Volatile var reverseIndexReady = false
        private set

    fun getAllMoves(): List<MoveTemplate> {
        allMovesCache?.let { return it }
        val moves = try {
            Moves.all().filter { it.name.isNotBlank() }
                .sortedBy { it.displayName.string.lowercase() }
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to load moves: ${e.message}")
            emptyList()
        }
        allMovesCache = moves
        return moves
    }

    fun buildReverseIndex() {
        if (reverseMoveIndex != null) return
        try {
            ensureSpeciesIndex()
            val files = speciesFiles
            if (files == null || files.isEmpty()) {
                reverseMoveIndex = emptyMap()
                reverseIndexReady = true
                return
            }

            // Pre-build dex number map for sorting (avoids repeated getByName later)
            val dexNumbers = mutableMapOf<String, Int>()
            for (species in PokemonSpecies.species) {
                dexNumbers[species.name.lowercase()] = species.nationalPokedexNumber
            }

            val index = mutableMapOf<String, MutableList<MoveLearner>>()

            // Read directly from Cobblemon JAR JSON files (reliable, always has data)
            for ((speciesName, file) in files) {
                try {
                    val content = Files.readString(file)
                    val root = JsonParser.parseString(content).asJsonObject
                    val movesArray = root.getAsJsonArray("moves") ?: continue

                    for (element in movesArray) {
                        val str = element.asString
                        val colonIdx = str.indexOf(':')
                        if (colonIdx < 0) continue

                        val prefix = str.substring(0, colonIdx)
                        val moveName = str.substring(colonIdx + 1)

                        val learner = when {
                            prefix == "tm" -> MoveLearner(speciesName, "tm")
                            prefix == "egg" -> MoveLearner(speciesName, "egg")
                            prefix == "tutor" -> MoveLearner(speciesName, "tutor")
                            prefix.toIntOrNull() != null -> MoveLearner(speciesName, "level-up", prefix.toInt())
                            else -> continue
                        }
                        index.getOrPut(moveName) { mutableListOf() }.add(learner)
                    }
                } catch (_: Exception) {}
            }

            // Deduplicate per species+method and sort by dex number
            reverseMoveIndex = index.mapValues { (_, learners) ->
                learners.distinctBy { "${it.speciesName}|${it.method}|${it.level}" }
                    .sortedBy { dexNumbers[it.speciesName] ?: 9999 }
            }
            reverseIndexReady = true
            HunterBoard.LOGGER.info("Built move reverse index: ${index.size} moves")
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to build reverse index: ${e.message}")
            reverseMoveIndex = emptyMap()
            reverseIndexReady = true
        }
    }

    fun getLearnersForMove(moveName: String): List<MoveLearner> {
        return reverseMoveIndex?.get(moveName) ?: emptyList()
    }

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
