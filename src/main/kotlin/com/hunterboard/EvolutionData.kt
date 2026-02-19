package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

data class EvoNode(
    val speciesName: String,
    val species: Species?,
    val children: List<EvoNode>,
    val isCurrent: Boolean,
    val condition: String = ""
)

object EvolutionData {

    private val cache = mutableMapOf<String, EvoNode?>()

    // Fallback data from JSON (for servers)
    private var jsonEvolutionTargets: Map<String, List<EvoTarget>> = emptyMap()  // species -> list of evolution targets with conditions
    private var jsonPreEvolutions: Map<String, String> = emptyMap()      // species -> pre-evolution name
    private var jsonLoaded = false

    fun getEvolutionTree(species: Species): EvoNode? {
        val key = species.name.lowercase()
        if (cache.containsKey(key)) return cache[key]

        val base = findBaseSpecies(species)
        val tree = buildTree(base, species.name.lowercase(), mutableSetOf())
        // Only cache if the tree has more than just the root (i.e., there are evolutions)
        val result = if (tree.children.isNotEmpty() || tree.isCurrent && hasPreEvolution(species)) tree else null
        cache[key] = result
        return result
    }

    private fun hasPreEvolution(species: Species): Boolean {
        // Check API first
        try {
            val pre = species.preEvolution
            if (pre != null) return true
        } catch (_: Exception) {}
        // Check JSON fallback
        ensureJsonLoaded()
        return jsonPreEvolutions.containsKey(species.name.lowercase())
    }

    private fun findBaseSpecies(species: Species): Species {
        var current = species
        val visited = mutableSetOf(current.name.lowercase())
        var maxDepth = 10

        while (maxDepth-- > 0) {
            val preName = getPreEvolutionName(current)
            if (preName == null) break
            val pre = PokemonSpecies.getByName(preName) ?: break
            if (pre.name.lowercase() in visited) break
            visited.add(pre.name.lowercase())
            current = pre
        }
        return current
    }

    private fun getPreEvolutionName(species: Species): String? {
        // Try Cobblemon API
        try {
            val pre = species.preEvolution
            if (pre != null) {
                val preSpecies = pre.species
                if (preSpecies != null) return preSpecies.name.lowercase()
            }
        } catch (_: Exception) {}

        // Fallback to JSON
        ensureJsonLoaded()
        return jsonPreEvolutions[species.name.lowercase()]
    }

    data class EvoTarget(val name: String, val condition: String)

    private fun getEvolutionTargets(species: Species): List<EvoTarget> {
        // Try JSON first (has condition data)
        ensureJsonLoaded()
        val jsonTargets = jsonEvolutionTargets[species.name.lowercase()]
        if (jsonTargets != null && jsonTargets.isNotEmpty()) return jsonTargets

        // Fallback to API (no conditions)
        try {
            val evos = species.evolutions
            if (evos.isNotEmpty()) {
                return evos.mapNotNull { evo ->
                    try {
                        val result = evo.result
                        val speciesName = result.species
                        speciesName?.lowercase()?.let { EvoTarget(it, "") }
                    } catch (_: Exception) { null }
                }.distinctBy { it.name }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    private fun buildTree(species: Species, highlightName: String, visited: MutableSet<String>, condition: String = ""): EvoNode {
        val name = species.name.lowercase()
        visited.add(name)

        val targets = getEvolutionTargets(species)
        val children = targets.mapNotNull { target ->
            if (target.name in visited) return@mapNotNull null
            val childSpecies = PokemonSpecies.getByName(target.name) ?: return@mapNotNull null
            buildTree(childSpecies, highlightName, visited, target.condition)
        }

        return EvoNode(
            speciesName = name,
            species = species,
            children = children,
            isCurrent = name == highlightName,
            condition = condition
        )
    }

    // --- JSON Fallback (for servers) ---

    private fun ensureJsonLoaded() {
        if (jsonLoaded) return
        jsonLoaded = true
        loadFromResources()
    }

    private fun loadFromResources() {
        try {
            val cobblemonMod = FabricLoader.getInstance()
                .getModContainer("cobblemon").orElse(null) ?: return

            val evos = mutableMapOf<String, MutableList<EvoTarget>>()
            val preEvos = mutableMapOf<String, String>()

            for (rootPath in cobblemonMod.rootPaths) {
                val speciesDir = rootPath.resolve("data/cobblemon/species")
                if (!Files.exists(speciesDir)) continue

                Files.walk(speciesDir)
                    .filter { it.toString().endsWith(".json") }
                    .forEach { file ->
                        try {
                            val content = Files.readString(file)
                            parseSpeciesEvolutions(content, evos, preEvos)
                        } catch (_: Exception) {}
                    }
            }

            jsonEvolutionTargets = evos
            jsonPreEvolutions = preEvos
            HunterBoard.LOGGER.info("Loaded evolution data for ${evos.size} species (JSON fallback)")
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to load evolution data from resources: ${e.message}")
        }
    }

    private fun parseSpeciesEvolutions(
        json: String,
        evos: MutableMap<String, MutableList<EvoTarget>>,
        preEvos: MutableMap<String, String>
    ) {
        val root = JsonParser.parseString(json).asJsonObject
        val name = root.get("name")?.asString?.lowercase() ?: return

        val evoArray = root.getAsJsonArray("evolutions") ?: return

        val targets = mutableListOf<EvoTarget>()
        for (evoEl in evoArray) {
            val evoObj = evoEl.asJsonObject
            val result = evoObj.get("result")?.asString ?: continue
            val resultSpecies = result.split(" ").first().lowercase()
            if (resultSpecies.isEmpty()) continue

            // Parse condition from requirements
            val condition = parseCondition(evoObj)
            targets.add(EvoTarget(resultSpecies, condition))

            // Build pre-evolution link
            preEvos[resultSpecies] = name
        }
        if (targets.isNotEmpty()) evos[name] = targets
    }

    private fun parseCondition(evoObj: com.google.gson.JsonObject): String {
        val parts = mutableListOf<String>()
        val evoVariant = evoObj.get("variant")?.asString ?: ""

        // Stone evolutions: requiredContext is on the evo object, not in requirements
        if (evoVariant == "item_interact") {
            val item = evoObj.get("requiredContext")?.asString
            if (item != null) {
                parts.add("item:$item")
            } else {
                parts.add("item")
            }
        }

        // Trade evolutions
        if (evoVariant == "trade") {
            parts.add("trade")
        }

        // Parse requirements array
        val reqs = evoObj.getAsJsonArray("requirements")
        if (reqs != null) {
            for (reqEl in reqs) {
                val req = reqEl.asJsonObject
                val variant = req.get("variant")?.asString ?: continue
                when (variant) {
                    "level" -> {
                        val lvl = req.get("minLevel")?.asInt
                        if (lvl != null) parts.add("Lv. $lvl")
                    }
                    "friendship" -> {
                        val amount = req.get("amount")?.asInt
                        if (amount != null) parts.add("friendship:$amount")
                        else parts.add("friendship")
                    }
                    "held_item" -> {
                        val item = req.get("itemCondition")?.asString
                        if (item != null) {
                            parts.add("held:$item")
                        } else {
                            parts.add("held_item")
                        }
                    }
                    "time_range" -> {
                        val range = req.get("range")?.asString ?: ""
                        parts.add("time:$range")
                    }
                    "biome" -> {
                        val biome = req.get("biomeCondition")?.asString ?: ""
                        parts.add("biome:$biome")
                    }
                    "move_learned" -> {
                        val move = req.get("move")?.asString ?: ""
                        parts.add("move:$move")
                    }
                    "has_move_type" -> {
                        val type = req.get("type")?.asString ?: ""
                        parts.add("move_type:$type")
                    }
                    "weather" -> {
                        val weather = req.get("weather")?.asString ?: ""
                        parts.add("weather:$weather")
                    }
                    "moon_phase" -> {
                        val phase = req.get("moonPhase")?.asInt
                        if (phase != null) parts.add("moon:$phase")
                    }
                    "world" -> {} // skip, not useful
                    "any" -> {} // skip, just a container
                    "party" -> {} // skip
                    "gender" -> {
                        val gender = req.get("gender")?.asString ?: ""
                        parts.add("gender:$gender")
                    }
                    "attack_defence_ratio" -> {
                        val ratio = req.get("ratio")?.asString ?: ""
                        parts.add("atk_def:$ratio")
                    }
                    "random" -> {
                        val chance = req.get("percentage")?.asFloat
                        if (chance != null) parts.add("random:$chance")
                    }
                    "has_pokemon_in_party" -> {
                        val pokemon = req.get("target")?.asString ?: ""
                        parts.add("party_pokemon:$pokemon")
                    }
                    "recoil_damage" -> {
                        val damage = req.get("damage")?.asInt
                        if (damage != null) parts.add("recoil:$damage")
                    }
                    "damage_taken" -> {
                        val damage = req.get("damage")?.asInt
                        if (damage != null) parts.add("damage_taken:$damage")
                    }
                    "use_move" -> {
                        val move = req.get("move")?.asString ?: ""
                        val times = req.get("amount")?.asInt
                        parts.add("use_move:$move:${times ?: ""}")
                    }
                    "defeat" -> {
                        val count = req.get("amount")?.asInt
                        val target = req.get("target")?.asString ?: ""
                        parts.add("defeat:$target:${count ?: ""}")
                    }
                    "walked_steps" -> {
                        val steps = req.get("amount")?.asInt
                        if (steps != null) parts.add("steps:$steps")
                    }
                    "properties" -> {} // internal, skip
                    else -> parts.add(variant)
                }
            }
        }

        return parts.joinToString("+")
    }
}
