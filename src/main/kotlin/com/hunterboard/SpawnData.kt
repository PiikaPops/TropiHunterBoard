package com.hunterboard

import com.cobblemon.mod.common.api.conditional.RegistryLikeIdentifierCondition
import com.cobblemon.mod.common.api.conditional.RegistryLikeTagCondition
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.TimeRange
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

data class BiomeDetail(
    val displayName: String,
    val tagId: String? // e.g. "cobblemon:is_mountain" for tags, null for direct biome IDs
)

data class SpawnEntry(
    val biomes: String,
    val biomeDetails: List<BiomeDetail>,
    val time: String,
    val weather: String,
    val lvMin: Int,
    val lvMax: Int,
    val context: String,
    val bucket: String
)

object SpawnData {

    private var spawnMap: Map<String, List<SpawnEntry>> = emptyMap()
    var isLoading = false
        private set
    var isLoaded = false
        private set
    var loadError: String? = null
        private set

    fun getSpawns(pokemonName: String): List<SpawnEntry> {
        return spawnMap[pokemonName.lowercase()] ?: emptyList()
    }

    fun ensureLoaded() {
        if (isLoaded) return
        loadFromCobblemon()
        if (!isLoaded) {
            loadFromResources()
        }
    }

    private fun loadFromCobblemon() {
        try {
            val pool = CobblemonSpawnPools.WORLD_SPAWN_POOL
            val details = pool.details
            if (details.isEmpty()) return // Pool not yet populated

            val map = mutableMapOf<String, MutableList<SpawnEntry>>()

            for (detail in details) {
                if (detail !is PokemonSpawnDetail) continue
                val species = detail.pokemon.species?.lowercase() ?: continue

                val biomeDetails = mutableListOf<BiomeDetail>()
                var time = "any"
                var weather = "any"

                for (condition in detail.conditions) {
                    extractBiomes(condition, biomeDetails)
                    condition.timeRange?.let { time = identifyTimeRange(it) }
                    extractWeather(condition)?.let { weather = it }
                }

                val lvRange = detail.levelRange
                val entry = SpawnEntry(
                    biomes = biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
                    biomeDetails = biomeDetails.toList(),
                    time = time,
                    weather = weather,
                    lvMin = lvRange?.first ?: 0,
                    lvMax = lvRange?.last ?: 0,
                    context = "",
                    bucket = detail.bucket.name
                )
                map.getOrPut(species) { mutableListOf() }.add(entry)
            }

            spawnMap = map
            isLoaded = true
            HunterBoard.LOGGER.info("Loaded spawn data from Cobblemon API: ${map.size} species")
        } catch (e: Exception) {
            loadError = e.message
            HunterBoard.LOGGER.error("Failed to load spawn data from API: ${e.message}")
        }
    }

    /**
     * Fallback: reads spawn JSON files directly from Cobblemon's mod JAR.
     * Works on servers where the spawn pool API is not populated client-side.
     */
    private fun loadFromResources() {
        try {
            val cobblemonMod = FabricLoader.getInstance()
                .getModContainer("cobblemon").orElse(null)
            if (cobblemonMod == null) {
                HunterBoard.LOGGER.warn("Cobblemon mod not found, cannot load spawn data from resources")
                return
            }

            val map = mutableMapOf<String, MutableList<SpawnEntry>>()

            for (rootPath in cobblemonMod.rootPaths) {
                val spawnDir = rootPath.resolve("data/cobblemon/spawn_pool_world")
                if (!Files.exists(spawnDir)) continue

                Files.walk(spawnDir)
                    .filter { it.toString().endsWith(".json") }
                    .forEach { file ->
                        try {
                            val content = Files.readString(file)
                            parseSpawnFile(content, map)
                        } catch (_: Exception) {}
                    }
            }

            if (map.isNotEmpty()) {
                spawnMap = map
                isLoaded = true
                HunterBoard.LOGGER.info("Loaded spawn data from Cobblemon resources: ${map.size} species")
            }
        } catch (e: Exception) {
            loadError = e.message
            HunterBoard.LOGGER.error("Failed to load spawn data from resources: ${e.message}")
        }
    }

    private fun parseSpawnFile(json: String, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val root = JsonParser.parseString(json).asJsonObject

        val enabled = root.get("enabled")?.asBoolean ?: true
        if (!enabled) return

        val spawns = root.getAsJsonArray("spawns") ?: return

        for (element in spawns) {
            val obj = element.asJsonObject
            val type = obj.get("type")?.asString ?: "pokemon"

            when (type) {
                "pokemon" -> parsePokemonSpawn(obj, map)
                "pokemon-herd" -> parseHerdSpawn(obj, map)
            }
        }
    }

    private fun parsePokemonSpawn(obj: JsonObject, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val pokemonStr = obj.get("pokemon")?.asString ?: return
        val species = pokemonStr.split(" ").first().lowercase()
        val bucket = obj.get("bucket")?.asString ?: "common"

        var lvMin = 1
        var lvMax = 1
        obj.get("level")?.asString?.let { levelStr ->
            val parts = levelStr.split("-")
            lvMin = parts.firstOrNull()?.toIntOrNull() ?: 1
            lvMax = parts.lastOrNull()?.toIntOrNull() ?: lvMin
        }

        val biomeDetails = mutableListOf<BiomeDetail>()
        var time = "any"
        var weather = "any"

        obj.getAsJsonObject("condition")?.let { condition ->
            parseConditionBiomes(condition, biomeDetails)
            condition.get("timeRange")?.asString?.let { time = it }
            parseConditionWeather(condition)?.let { weather = it }
        }

        val entry = SpawnEntry(
            biomes = biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
            biomeDetails = biomeDetails.toList(),
            time = time,
            weather = weather,
            lvMin = lvMin,
            lvMax = lvMax,
            context = "",
            bucket = bucket
        )
        map.getOrPut(species) { mutableListOf() }.add(entry)
    }

    private fun parseHerdSpawn(obj: JsonObject, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val bucket = obj.get("bucket")?.asString ?: "common"
        val herdPokemon = obj.getAsJsonArray("herdablePokemon") ?: return

        val biomeDetails = mutableListOf<BiomeDetail>()
        var time = "any"
        var weather = "any"

        obj.getAsJsonObject("condition")?.let { condition ->
            parseConditionBiomes(condition, biomeDetails)
            condition.get("timeRange")?.asString?.let { time = it }
            parseConditionWeather(condition)?.let { weather = it }
        }

        for (member in herdPokemon) {
            val memberObj = member.asJsonObject
            val pokemonStr = memberObj.get("pokemon")?.asString ?: continue
            val species = pokemonStr.split(" ").first().lowercase()

            var lvMin = 1
            var lvMax = 1
            (memberObj.get("levelRange") ?: obj.get("levelRange") ?: obj.get("level"))
                ?.asString?.let { levelStr ->
                    val parts = levelStr.split("-")
                    lvMin = parts.firstOrNull()?.toIntOrNull() ?: 1
                    lvMax = parts.lastOrNull()?.toIntOrNull() ?: lvMin
                }

            val entry = SpawnEntry(
                biomes = biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
                biomeDetails = biomeDetails.toList(),
                time = time,
                weather = weather,
                lvMin = lvMin,
                lvMax = lvMax,
                context = "",
                bucket = bucket
            )
            map.getOrPut(species) { mutableListOf() }.add(entry)
        }
    }

    private fun parseConditionBiomes(condition: JsonObject, list: MutableList<BiomeDetail>) {
        val biomes = condition.getAsJsonArray("biomes") ?: return
        for (biomeEl in biomes) {
            val biomeStr = biomeEl.asString
            val detail = if (biomeStr.startsWith("#")) {
                val tagId = biomeStr.removePrefix("#")
                val path = tagId.substringAfter(":")
                BiomeDetail(
                    displayName = formatTagPath(path),
                    tagId = tagId
                )
            } else {
                val path = biomeStr.substringAfter(":")
                BiomeDetail(
                    displayName = formatBiomeId(path),
                    tagId = null
                )
            }
            if (detail.displayName.isNotEmpty() && list.none { it.displayName == detail.displayName }) {
                list.add(detail)
            }
        }
    }

    private fun parseConditionWeather(condition: JsonObject): String? {
        val thundering = condition.get("isThundering")?.asBoolean
        val raining = condition.get("isRaining")?.asBoolean
        return when {
            thundering == true -> "thunder"
            raining == true -> "rain"
            raining == false -> "clear"
            else -> null
        }
    }

    private fun extractBiomes(condition: SpawningCondition<*>, list: MutableList<BiomeDetail>) {
        try {
            val biomes = condition.biomes ?: return
            for (biome in biomes) {
                val detail = when (biome) {
                    is RegistryLikeTagCondition<*> -> {
                        val tagId = biome.tag.id
                        BiomeDetail(
                            displayName = formatTagPath(tagId.path),
                            tagId = "${tagId.namespace}:${tagId.path}"
                        )
                    }
                    is RegistryLikeIdentifierCondition<*> -> BiomeDetail(
                        displayName = formatBiomeId(biome.identifier.path),
                        tagId = null
                    )
                    else -> null
                }
                if (detail != null && detail.displayName.isNotEmpty() && list.none { it.displayName == detail.displayName }) {
                    list.add(detail)
                }
            }
        } catch (_: Exception) {}
    }

    private fun formatTagPath(path: String): String {
        return path
            .replace("is_", "")
            .replace("/", " ")
            .replace("_", " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun formatBiomeId(path: String): String {
        return path
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun identifyTimeRange(timeRange: TimeRange): String {
        try {
            for ((name, range) in TimeRange.timeRanges) {
                if (range.ranges == timeRange.ranges) return name
            }
        } catch (_: Exception) {}
        return "any"
    }

    private fun extractWeather(condition: SpawningCondition<*>): String? {
        return try {
            val raining = condition.isRaining
            val thundering = condition.isThundering
            when {
                thundering == true -> "thunder"
                raining == true -> "rain"
                raining == false -> "clear"
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
