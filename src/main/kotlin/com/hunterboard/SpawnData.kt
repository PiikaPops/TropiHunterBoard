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
    val tagId: String?, // e.g. "cobblemon:is_mountain" for tags, null for direct biome IDs
    val biomeId: String? = null // e.g. "minecraft:dark_forest" for direct biome IDs
)

data class WeightMultiplierData(
    val multiplier: Float,
    val conditionLabel: String // human-readable, e.g. "Thunder", "Night", "Lure 3+"
)

data class SpawnEntry(
    val biomes: String,
    val biomeDetails: List<BiomeDetail>,
    val time: String,
    val weather: String,
    val lvMin: Int,
    val lvMax: Int,
    val context: String,
    val bucket: String,
    val structures: List<String> = emptyList(),
    val canSeeSky: Boolean? = null,
    val minY: Int? = null,
    val maxY: Int? = null,
    val neededNearbyBlocks: List<String> = emptyList(),
    val neededBaseBlocks: List<String> = emptyList(),
    val minLight: Int? = null,
    val maxLight: Int? = null,
    val minSkyLight: Int? = null,
    val maxSkyLight: Int? = null,
    val moonPhase: Int? = null,
    val weight: Double? = null,
    val spawnContext: String = "",
    val excludedBiomes: List<BiomeDetail> = emptyList(),
    val presets: List<String> = emptyList(),
    val weightMultipliers: List<WeightMultiplierData> = emptyList()
)

object SpawnData {

    private var spawnMap: Map<String, List<SpawnEntry>> = emptyMap()
    var isLoading = false
        private set
    var isLoaded = false
        private set
    var loadError: String? = null
        private set

    fun getSpawns(pokemonName: String, formName: String = ""): List<SpawnEntry> {
        if (formName.isNotEmpty()) {
            val formKey = "${pokemonName.lowercase()} ${formName.lowercase()}"
            val formSpawns = spawnMap[formKey]
            if (!formSpawns.isNullOrEmpty()) return formSpawns
        }
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
                val form = try {
                    detail.pokemon.aspects.firstOrNull()?.lowercase() ?: ""
                } catch (_: Exception) { "" }

                val biomeDetails = mutableListOf<BiomeDetail>()
                var time = "any"
                var weather = "any"
                val structures = mutableListOf<String>()
                var canSeeSky: Boolean? = null
                var minY: Int? = null
                var maxY: Int? = null
                val nearbyBlocks = mutableListOf<String>()
                val baseBlocks = mutableListOf<String>()
                var minLight: Int? = null
                var maxLight: Int? = null
                var minSkyLight: Int? = null
                var maxSkyLight: Int? = null
                var moonPhase: Int? = null

                for (condition in detail.conditions) {
                    extractBiomes(condition, biomeDetails)
                    condition.timeRange?.let { time = identifyTimeRange(it) }
                    extractWeather(condition)?.let { weather = it }
                    extractStructures(condition, structures)
                    val sky = extractCanSeeSky(condition)
                    if (sky != null) canSeeSky = sky
                    extractIntField(condition, "minY")?.let { minY = it }
                    extractIntField(condition, "maxY")?.let { maxY = it }
                    extractIntField(condition, "minLight")?.let { minLight = it }
                    extractIntField(condition, "maxLight")?.let { maxLight = it }
                    extractIntField(condition, "minSkyLight")?.let { minSkyLight = it }
                    extractIntField(condition, "maxSkyLight")?.let { maxSkyLight = it }
                    extractIntField(condition, "moonPhase")?.let { moonPhase = it }
                    extractBlockConditions(condition, "neededNearbyBlocks", nearbyBlocks)
                    extractBlockConditions(condition, "neededBaseBlocks", baseBlocks)
                }

                // Anticonditions (excluded biomes)
                val excludedBiomes = mutableListOf<BiomeDetail>()
                try {
                    for (anti in detail.anticonditions) {
                        extractBiomes(anti, excludedBiomes)
                    }
                } catch (_: Exception) {}

                // Presets
                val presetList = try {
                    val pField = detail::class.java.getField("presets")
                    @Suppress("UNCHECKED_CAST")
                    (pField.get(detail) as? List<String>)?.toList() ?: emptyList()
                } catch (_: Exception) { emptyList<String>() }

                val spawnCtx = try {
                    val field = detail::class.java.getField("spawnablePositionType")
                    (field.get(detail) as? String)?.lowercase() ?: ""
                } catch (_: Exception) { "" }
                val spawnWeight = try { detail.weight.toDouble() } catch (_: Exception) { null }

                // Weight multipliers
                val multipliers = extractWeightMultipliers(detail)

                val lvRange = detail.levelRange
                val entry = SpawnEntry(
                    biomes = biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
                    biomeDetails = biomeDetails.toList(),
                    time = time,
                    weather = weather,
                    lvMin = lvRange?.first ?: 0,
                    lvMax = lvRange?.last ?: 0,
                    context = "",
                    bucket = detail.bucket.name,
                    structures = structures.toList(),
                    canSeeSky = canSeeSky,
                    minY = minY,
                    maxY = maxY,
                    neededNearbyBlocks = nearbyBlocks.toList(),
                    neededBaseBlocks = baseBlocks.toList(),
                    minLight = minLight,
                    maxLight = maxLight,
                    minSkyLight = minSkyLight,
                    maxSkyLight = maxSkyLight,
                    moonPhase = moonPhase,
                    weight = spawnWeight,
                    spawnContext = spawnCtx,
                    excludedBiomes = excludedBiomes.toList(),
                    presets = presetList,
                    weightMultipliers = multipliers
                )
                if (form.isNotEmpty()) {
                    map.getOrPut("$species $form") { mutableListOf() }.add(entry)
                } else {
                    map.getOrPut(species) { mutableListOf() }.add(entry)
                }
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
        val parts = pokemonStr.split(" ")
        val species = parts.first().lowercase()
        val form = if (parts.size > 1) parts.drop(1).joinToString(" ").lowercase() else ""
        val bucket = obj.get("bucket")?.asString ?: "common"

        var lvMin = 1
        var lvMax = 1
        obj.get("level")?.asString?.let { levelStr ->
            val lvParts = levelStr.split("-")
            lvMin = lvParts.firstOrNull()?.toIntOrNull() ?: 1
            lvMax = lvParts.lastOrNull()?.toIntOrNull() ?: lvMin
        }

        val biomeDetails = mutableListOf<BiomeDetail>()
        var time = "any"
        var weather = "any"
        val structures = mutableListOf<String>()
        var canSeeSky: Boolean? = null
        var minY: Int? = null
        var maxY: Int? = null
        val nearbyBlocks = mutableListOf<String>()
        val baseBlocks = mutableListOf<String>()
        var minLight: Int? = null
        var maxLight: Int? = null
        var minSkyLight: Int? = null
        var maxSkyLight: Int? = null
        var moonPhase: Int? = null

        obj.getAsJsonObject("condition")?.let { condition ->
            parseConditionBiomes(condition, biomeDetails)
            condition.get("timeRange")?.asString?.let { time = it }
            parseConditionWeather(condition)?.let { weather = it }
            parseConditionStructures(condition, structures)
            if (condition.has("canSeeSky")) canSeeSky = condition.get("canSeeSky").asBoolean
            if (condition.has("minY")) minY = condition.get("minY").asInt
            if (condition.has("maxY")) maxY = condition.get("maxY").asInt
            if (condition.has("minLight")) minLight = condition.get("minLight").asInt
            if (condition.has("maxLight")) maxLight = condition.get("maxLight").asInt
            if (condition.has("minSkyLight")) minSkyLight = condition.get("minSkyLight").asInt
            if (condition.has("maxSkyLight")) maxSkyLight = condition.get("maxSkyLight").asInt
            if (condition.has("moonPhase")) moonPhase = condition.get("moonPhase").asInt
            parseConditionBlocks(condition, "neededNearbyBlocks", nearbyBlocks)
            parseConditionBlocks(condition, "neededBaseBlocks", baseBlocks)
        }

        val spawnCtx = obj.get("spawnablePositionType")?.asString?.lowercase() ?: ""
        val spawnWeight = try { obj.get("weight")?.asDouble } catch (_: Exception) { null }

        // Anticondition (excluded biomes)
        val excludedBiomes = mutableListOf<BiomeDetail>()
        obj.getAsJsonObject("anticondition")?.let { anti ->
            parseConditionBiomes(anti, excludedBiomes)
        }

        // Presets
        val presetList = mutableListOf<String>()
        obj.getAsJsonArray("presets")?.forEach { el ->
            val p = el.asString
            if (p.isNotEmpty()) presetList.add(p)
        }

        // Weight multipliers (JSON supports both singular "weightMultiplier" and plural "weightMultipliers")
        val multipliers = parseJsonWeightMultipliers(obj)

        val entry = SpawnEntry(
            biomes = biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
            biomeDetails = biomeDetails.toList(),
            time = time,
            weather = weather,
            lvMin = lvMin,
            lvMax = lvMax,
            context = "",
            bucket = bucket,
            structures = structures.toList(),
            canSeeSky = canSeeSky,
            minY = minY,
            maxY = maxY,
            neededNearbyBlocks = nearbyBlocks.toList(),
            neededBaseBlocks = baseBlocks.toList(),
            minLight = minLight,
            maxLight = maxLight,
            minSkyLight = minSkyLight,
            maxSkyLight = maxSkyLight,
            moonPhase = moonPhase,
            weight = spawnWeight,
            spawnContext = spawnCtx,
            excludedBiomes = excludedBiomes.toList(),
            presets = presetList.toList(),
            weightMultipliers = multipliers
        )
        if (form.isNotEmpty()) {
            map.getOrPut("$species $form") { mutableListOf() }.add(entry)
        } else {
            map.getOrPut(species) { mutableListOf() }.add(entry)
        }
    }

    private fun parseHerdSpawn(obj: JsonObject, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val bucket = obj.get("bucket")?.asString ?: "common"
        val herdPokemon = obj.getAsJsonArray("herdablePokemon") ?: return

        val biomeDetails = mutableListOf<BiomeDetail>()
        var time = "any"
        var weather = "any"
        val structures = mutableListOf<String>()
        var canSeeSky: Boolean? = null
        var minY: Int? = null
        var maxY: Int? = null
        val nearbyBlocks = mutableListOf<String>()
        val baseBlocks = mutableListOf<String>()
        var minLight: Int? = null
        var maxLight: Int? = null
        var minSkyLight: Int? = null
        var maxSkyLight: Int? = null
        var moonPhase: Int? = null

        obj.getAsJsonObject("condition")?.let { condition ->
            parseConditionBiomes(condition, biomeDetails)
            condition.get("timeRange")?.asString?.let { time = it }
            parseConditionWeather(condition)?.let { weather = it }
            parseConditionStructures(condition, structures)
            if (condition.has("canSeeSky")) canSeeSky = condition.get("canSeeSky").asBoolean
            if (condition.has("minY")) minY = condition.get("minY").asInt
            if (condition.has("maxY")) maxY = condition.get("maxY").asInt
            if (condition.has("minLight")) minLight = condition.get("minLight").asInt
            if (condition.has("maxLight")) maxLight = condition.get("maxLight").asInt
            if (condition.has("minSkyLight")) minSkyLight = condition.get("minSkyLight").asInt
            if (condition.has("maxSkyLight")) maxSkyLight = condition.get("maxSkyLight").asInt
            if (condition.has("moonPhase")) moonPhase = condition.get("moonPhase").asInt
            parseConditionBlocks(condition, "neededNearbyBlocks", nearbyBlocks)
            parseConditionBlocks(condition, "neededBaseBlocks", baseBlocks)
        }

        val spawnCtx = obj.get("spawnablePositionType")?.asString?.lowercase() ?: ""
        val spawnWeight = try { obj.get("weight")?.asDouble } catch (_: Exception) { null }

        val excludedBiomes = mutableListOf<BiomeDetail>()
        obj.getAsJsonObject("anticondition")?.let { anti ->
            parseConditionBiomes(anti, excludedBiomes)
        }

        val presetList = mutableListOf<String>()
        obj.getAsJsonArray("presets")?.forEach { el ->
            val p = el.asString
            if (p.isNotEmpty()) presetList.add(p)
        }

        val multipliers = parseJsonWeightMultipliers(obj)

        for (member in herdPokemon) {
            val memberObj = member.asJsonObject
            val pokemonStr = memberObj.get("pokemon")?.asString ?: continue
            val herdParts = pokemonStr.split(" ")
            val species = herdParts.first().lowercase()
            val form = if (herdParts.size > 1) herdParts.drop(1).joinToString(" ").lowercase() else ""

            var lvMin = 1
            var lvMax = 1
            (memberObj.get("levelRange") ?: obj.get("levelRange") ?: obj.get("level"))
                ?.asString?.let { levelStr ->
                    val lvParts = levelStr.split("-")
                    lvMin = lvParts.firstOrNull()?.toIntOrNull() ?: 1
                    lvMax = lvParts.lastOrNull()?.toIntOrNull() ?: lvMin
                }

            val entry = SpawnEntry(
                biomes = biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
                biomeDetails = biomeDetails.toList(),
                time = time,
                weather = weather,
                lvMin = lvMin,
                lvMax = lvMax,
                context = "",
                bucket = bucket,
                structures = structures.toList(),
                canSeeSky = canSeeSky,
                minY = minY,
                maxY = maxY,
                neededNearbyBlocks = nearbyBlocks.toList(),
                neededBaseBlocks = baseBlocks.toList(),
                minLight = minLight,
                maxLight = maxLight,
                minSkyLight = minSkyLight,
                maxSkyLight = maxSkyLight,
                moonPhase = moonPhase,
                weight = spawnWeight,
                spawnContext = spawnCtx,
                excludedBiomes = excludedBiomes.toList(),
                presets = presetList.toList(),
                weightMultipliers = multipliers
            )
            if (form.isNotEmpty()) {
                map.getOrPut("$species $form") { mutableListOf() }.add(entry)
            } else {
                map.getOrPut(species) { mutableListOf() }.add(entry)
            }
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
                    tagId = null,
                    biomeId = biomeStr
                )
            }
            if (detail.displayName.isNotEmpty() && list.none { it.displayName == detail.displayName }) {
                list.add(detail)
            }
        }
    }

    private fun parseConditionStructures(condition: JsonObject, list: MutableList<String>) {
        val structures = condition.getAsJsonArray("structures") ?: return
        for (el in structures) {
            val raw = el.asString
            val name = formatStructureId(raw)
            if (name.isNotEmpty() && name !in list) list.add(name)
        }
    }

    private fun parseConditionBlocks(condition: JsonObject, key: String, list: MutableList<String>) {
        val blocks = condition.getAsJsonArray(key) ?: return
        for (el in blocks) {
            val raw = el.asString
            if (raw.isNotEmpty() && raw !in list) list.add(raw)
        }
    }

    private fun formatStructureId(raw: String): String {
        // "#minecraft:village" -> "Village", "minecraft:mansion" -> "Mansion"
        return raw.removePrefix("#")
            .substringAfter(":")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
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
                        tagId = null,
                        biomeId = "${biome.identifier.namespace}:${biome.identifier.path}"
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

    private fun extractStructures(condition: SpawningCondition<*>, list: MutableList<String>) {
        try {
            val structures = condition.structures ?: return
            for (structure in structures) {
                val name = formatStructureId(structure.toString())
                if (name.isNotEmpty() && name !in list) list.add(name)
            }
        } catch (_: Exception) {}
    }

    private fun extractCanSeeSky(condition: SpawningCondition<*>): Boolean? {
        return try {
            condition.canSeeSky
        } catch (_: Exception) { null }
    }

    private fun extractIntField(condition: SpawningCondition<*>, fieldName: String): Int? {
        return try {
            val field = condition::class.java.getField(fieldName)
            field.get(condition) as? Int
        } catch (_: Exception) { null }
    }

    private fun extractBlockConditions(condition: SpawningCondition<*>, fieldName: String, list: MutableList<String>) {
        try {
            val field = condition::class.java.getField(fieldName)
            val blocks = field.get(condition) as? List<*> ?: return
            for (block in blocks) {
                val name = when (block) {
                    is RegistryLikeTagCondition<*> -> "#${block.tag.id.namespace}:${block.tag.id.path}"
                    is RegistryLikeIdentifierCondition<*> -> "${block.identifier.namespace}:${block.identifier.path}"
                    else -> continue
                }
                if (name !in list) list.add(name)
            }
        } catch (_: Exception) {}
    }

    /** Extract weight multipliers from Cobblemon API (SpawnDetail.weightMultipliers) */
    private fun extractWeightMultipliers(detail: PokemonSpawnDetail): List<WeightMultiplierData> {
        return try {
            val wmList = detail.weightMultipliers
            if (wmList.isEmpty()) return emptyList()
            wmList.mapNotNull { wm ->
                try {
                    val mult = wm.multiplier
                    val label = describeMultiplierConditions(wm.conditions)
                    WeightMultiplierData(mult, label)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Build a human-readable label from a list of SpawningConditions */
    private fun describeMultiplierConditions(conditions: List<SpawningCondition<*>>): String {
        val parts = mutableListOf<String>()
        for (cond in conditions) {
            try {
                cond.isThundering?.let { if (it) parts.add("thunder") }
                cond.isRaining?.let { if (it && !parts.contains("thunder")) parts.add("rain") }
            } catch (_: Exception) {}
            try {
                cond.timeRange?.let { parts.add(identifyTimeRange(it)) }
            } catch (_: Exception) {}
            try {
                val minLure = extractIntField(cond, "minLureLevel")
                val maxLure = extractIntField(cond, "maxLureLevel")
                if (minLure != null || maxLure != null) {
                    val label = when {
                        minLure != null && maxLure != null && minLure == maxLure -> "lure:$minLure"
                        minLure != null && maxLure != null -> "lure:$minLure-$maxLure"
                        minLure != null -> "lure:$minLure+"
                        else -> "lure:$maxLure"
                    }
                    parts.add(label)
                }
            } catch (_: Exception) {}
            try {
                extractIntField(cond, "moonPhase")?.let { parts.add("moon:$it") }
            } catch (_: Exception) {}
        }
        return if (parts.isEmpty()) "?" else parts.joinToString(", ")
    }

    /** Parse weight multipliers from JSON (supports both "weightMultiplier" singular and "weightMultipliers" array) */
    private fun parseJsonWeightMultipliers(obj: JsonObject): List<WeightMultiplierData> {
        val result = mutableListOf<WeightMultiplierData>()
        // Plural array form: "weightMultipliers": [...]
        obj.getAsJsonArray("weightMultipliers")?.forEach { el ->
            parseOneJsonMultiplier(el.asJsonObject)?.let { result.add(it) }
        }
        // Singular object form: "weightMultiplier": {...}
        if (result.isEmpty()) {
            obj.getAsJsonObject("weightMultiplier")?.let { wm ->
                parseOneJsonMultiplier(wm)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun parseOneJsonMultiplier(wm: JsonObject): WeightMultiplierData? {
        val mult = wm.get("multiplier")?.asFloat ?: return null
        val cond = wm.getAsJsonObject("condition")
        val label = if (cond != null) describeJsonMultiplierCondition(cond) else "?"
        return WeightMultiplierData(mult, label)
    }

    private fun describeJsonMultiplierCondition(cond: JsonObject): String {
        val parts = mutableListOf<String>()
        if (cond.has("isThundering") && cond.get("isThundering").asBoolean) parts.add("thunder")
        if (cond.has("isRaining") && cond.get("isRaining").asBoolean && "thunder" !in parts) parts.add("rain")
        if (cond.has("timeRange")) parts.add(cond.get("timeRange").asString)
        val minLure = if (cond.has("minLureLevel")) cond.get("minLureLevel").asInt else null
        val maxLure = if (cond.has("maxLureLevel")) cond.get("maxLureLevel").asInt else null
        if (minLure != null || maxLure != null) {
            val label = when {
                minLure != null && maxLure != null && minLure == maxLure -> "lure:$minLure"
                minLure != null && maxLure != null -> "lure:$minLure-$maxLure"
                minLure != null -> "lure:$minLure+"
                else -> "lure:$maxLure"
            }
            parts.add(label)
        }
        if (cond.has("moonPhase")) parts.add("moon:${cond.get("moonPhase").asInt}")
        return if (parts.isEmpty()) "?" else parts.joinToString(", ")
    }
}
