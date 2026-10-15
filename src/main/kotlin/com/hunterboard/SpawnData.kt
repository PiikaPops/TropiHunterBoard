package com.hunterboard

import com.cobblemon.mod.common.api.conditional.RegistryLikeIdentifierCondition
import com.cobblemon.mod.common.api.conditional.RegistryLikeTagCondition
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.IntRanges
import com.cobblemon.mod.common.api.spawning.TimeRange
import com.cobblemon.mod.common.api.spawning.condition.AreaTypeSpawningCondition
import com.cobblemon.mod.common.api.spawning.condition.FishingSpawningCondition
import com.cobblemon.mod.common.api.spawning.condition.GroundedTypeSpawningCondition
import com.cobblemon.mod.common.api.spawning.condition.SeafloorTypeSpawningCondition
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition
import com.cobblemon.mod.common.api.spawning.condition.SubmergedTypeSpawningCondition
import com.cobblemon.mod.common.api.spawning.condition.SurfaceTypeSpawningCondition
import com.cobblemon.mod.common.api.spawning.detail.PokemonHerdSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    val weightMultipliers: List<WeightMultiplierData> = emptyList(),
    val requiresSlimeChunk: Boolean = false,
    /** Human-readable variant label ("Classic", "Red"…) when this entry comes from a cosmetic feature aspect */
    val formLabel: String = "",

    // ---- Cobblemon 1.8 ----
    /** This spawn can also be triggered by a dropped Poké Snack (1.8 "isPokeSnack" condition) */
    val isPokeSnack: Boolean = false,
    /** True when the Poké Snack condition is the ONLY way to get this spawn */
    val pokeSnackOnly: Boolean = false,
    /** This spawn is an Alpha boss herd (Cobblemon 1.8; always the "boss" bucket) */
    val isAlphaSpawn: Boolean = false,
    /** All moon phases this spawn accepts (1.8 turned moonPhase into a MoonPhaseRange) */
    val moonPhases: List<Int> = emptyList(),
    /** Required world markers (1.8 "markers" condition) */
    val markers: List<String> = emptyList(),
    /** Fishing: required rod item id, e.g. "cobblemon:love_rod" */
    val rodType: String? = null,
    /** Fishing: required bait item id, e.g. "cobblemon:love_sweet" */
    val bait: String? = null,
    val minLureLevel: Int? = null,
    val maxLureLevel: Int? = null,
    /** Water spawns: depth below the surface */
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    /** Required fluid id/tag for surface & submerged spawns */
    val fluid: String? = null,
    /** Free space needed above the spawn position */
    val minHeight: Int? = null,
    val maxHeight: Int? = null,
    /** Dimensions this spawn is restricted to */
    val dimensions: List<String> = emptyList(),
    /** Herd spawns: maximum herd size and the species that can appear in it */
    val herdSize: Int? = null,
    val herdMembers: List<HerdMemberData> = emptyList(),
    /** Items the Pokémon can spawn holding */
    val heldItems: List<HeldItemData> = emptyList(),
    /**
     * A Tropimon "filler" spawn: a very low weight catch-all the server adds so that no
     * biome ever comes up empty. Not part of Cobblemon's own data.
     */
    val isFiller: Boolean = false
)

/** One possible member of a herd spawn (Cobblemon 1.8 "pokemon-herd" spawns) */
data class HerdMemberData(
    val species: String,
    val weight: Float,
    val lvMin: Int,
    val lvMax: Int,
    val maxTimes: Int,
    val isLeader: Boolean,
    /** Cobblemon 1.8 Alpha: an oversized boss variant leading the herd */
    val isAlpha: Boolean = false
)

/** An item a spawning Pokémon may be holding, with its chance in percent */
data class HeldItemData(
    val item: String,
    val percentage: Double
)

/**
 * A ready-to-draw extra line of spawn information (text + ARGB colour).
 * [habitatId] is set on habitat rows so the screens can offer a biome tooltip.
 */
data class SpawnLine(val text: String, val color: Int, val habitatId: String? = null)

object SpawnData {

    private const val SPAWN_POOL_FOLDER = "spawn_pool_world"

    private var spawnMap: Map<String, List<SpawnEntry>> = emptyMap()
    var isLoading = false
        private set
    var isLoaded = false
        private set
    var loadError: String? = null
        private set

    /** Normalize any species name to a canonical key (accents/gender symbols folded, non-alphanumeric stripped) */
    private fun normalizeKey(name: String): String = NameUtil.normalize(name)

    /**
     * Bidirectional alias map for TRUE regional forms between the short JSON form name
     * ("alola") and the Cobblemon aspect tag ("alolan"). These are distinct from regional
     * biases, which use a different aspect format (see storeSpawn).
     */
    /** Per-feature value synonyms, where the spawn file and the aspect disagree on a name. */
    private val VALUE_ALIASES = mapOf(
        "squawkabilly_color" to mapOf("white" to "gray")
    )

    private val FORM_ALIASES = mapOf(
        "galar"    to "galarian",
        "galarian" to "galar",
        "alola"    to "alolan",
        "alolan"   to "alola",
        "hisui"    to "hisuian",
        "hisuian"  to "hisui",
        "paldea"   to "paldean",
        "paldean"  to "paldea"
    )

    /** Regional form identifiers — real variants that must stay OUT of the base species bucket */
    private val REGIONAL_FORMS = setOf(
        "galar", "galarian", "alola", "alolan",
        "hisui", "hisuian", "paldea", "paldean"
    )

    /**
     * True if this form is a cosmetic feature aspect (e.g. Arbok "snake_pattern=classic",
     * Flabébé "flower=red", Unown "character=a") rather than a regional form or bias.
     * JSON spawn files use "feature=value"; the Cobblemon API exposes the same thing
     * as "feature-value" (see species_features aspectFormat).
     */
    private fun isCosmeticFeature(form: String): Boolean {
        if (form.startsWith("region_bias=") || form.startsWith("region-bias-")) return false
        if (form in REGIONAL_FORMS) return false
        return true
    }

    /**
     * Store a spawn entry under its normalized key, handling four form categories distinctly:
     *  - Regional bias (spawn JSON "region_bias=alola" ↔ species aspect "region-bias-alola")
     *  - True regional form (spawn JSON "alola" ↔ species aspect "alolan")
     *  - Cosmetic feature aspect (spawn JSON "snake_pattern=classic" ↔ aspect "snake-pattern-classic")
     *  - Any other form (stored as-is)
     * Regional bias and regional form are kept in SEPARATE key buckets because they are
     * different concepts in Cobblemon — a bias is a soft spawn tint, a form is a distinct variant.
     * Cosmetic features are ALSO stored under the base key: species like Arbok, Unown, Vivillon
     * or Flabébé spawn exclusively with such aspects, so without this the base species would
     * appear to have no spawns at all.
     */
    /**
     * Every name the API might use for one form token of a spawn file.
     *
     * The two sides never agreed on a spelling. A spawn file writes `striped=white`; the
     * species file calls the same form `whitestriped`. Cobblemon builds that aspect from
     * the *value* and the *tail* of the feature name, in whichever order reads best, so
     * one rule cannot cover it — all the shapes it actually produces are generated here
     * and matched against. The cases that drove this, one per rule:
     *
     *   striped=white               → whitestriped, white-striped   (Basculin)
     *   wolf_form=dusk              → dusk-form                     (Lycanroc, Rockruff)
     *   blossom_form=sunshine       → sunshine-form                 (Cherrim)
     *   sea=east                    → east-sea                      (Shellos, Gastrodon)
     *   matcha_authenticity=artisan → artisan                       (Poltchageist, Sinistcha)
     *   roaming=true                → roaming                       (Gimmighoul)
     *   squawkabilly_color=blue     → squawkabilly-color-blue       (Squawkabilly)
     */
    private fun formAliases(token: String): Set<String> {
        val out = mutableSetOf(token)
        when {
            token.startsWith("region_bias=") ->
                out.add("region-bias-" + token.removePrefix("region_bias="))
            token.startsWith("region-bias-") ->
                out.add("region_bias=" + token.removePrefix("region-bias-"))
            else -> {
                FORM_ALIASES[token]?.let { out.add(it) }
                if (token.contains("=")) {
                    val feature = token.substringBefore("=")
                    val rawValue = token.substringAfter("=")
                    // Cobblemon does not always name a value the same on both sides:
                    // Squawkabilly's white bird is "squawkabilly_color=white" in the spawn
                    // file and "squawkabilly-color-gray" as an aspect. Scoped per feature
                    // so a synonym cannot leak into an unrelated species.
                    val value = VALUE_ALIASES[feature]?.get(rawValue) ?: rawValue
                    if (value != rawValue) out.add("$feature-$rawValue".replace("_", "-"))
                    // the tail of the feature is what the aspect keeps: wolf_form -> form
                    val tail = feature.substringAfterLast("_")
                    out.add(token.replace("_", "-").replace("=", "-"))   // feature-value
                    out.add(value)                                        // value alone
                    out.add("$value-$tail")                               // east-sea, dusk-form
                    out.add("$value$tail")                                // whitestriped
                    // a boolean feature becomes the bare feature name when true
                    if (value == "true") out.add(feature.replace("_", "-"))
                }
            }
        }
        return out.filter { it.isNotEmpty() }.toSet()
    }

    private fun storeSpawn(map: MutableMap<String, MutableList<SpawnEntry>>, species: String, form: String, entry: SpawnEntry) {
        val baseKey = normalizeKey(species)
        if (form.isNotEmpty()) {
            // A form can carry several tokens ("paldean bull_breed=aqua"), and Cobblemon
            // even repeats the species name in a few files ("squawkabilly
            // squawkabilly_color=blue"), so each token is aliased on its own and the whole
            // string is kept as well.
            val aliases = mutableSetOf(form)
            aliases.addAll(formAliases(form))
            val tokens = form.split(" ").filter { it.isNotBlank() }
            if (tokens.size > 1) {
                for (token in tokens) {
                    if (normalizeKey(token) == baseKey) continue   // the repeated species name
                    aliases.addAll(formAliases(token))
                }
            }
            for (alias in aliases) {
                map.getOrPut("$baseKey $alias") { mutableListOf() }.add(entry)
            }
            // Cosmetic feature aspects also feed the base species (deduplicated),
            // otherwise e.g. Arbok — whose every spawn carries a snake_pattern — shows nothing.
            if (isCosmeticFeature(form)) {
                val labeled = entry.copy(formLabel = prettyFormLabel(form))
                val baseList = map.getOrPut(baseKey) { mutableListOf() }
                if (labeled !in baseList) baseList.add(labeled)
            }
        } else {
            map.getOrPut(baseKey) { mutableListOf() }.add(entry)
        }
    }

    /**
     * "snake_pattern=classic" → "Classic", "flower=red" → "Red",
     * "snake-pattern-classic" → "Classic" (API aspect form).
     */
    private fun prettyFormLabel(form: String): String {
        val value = when {
            form.contains("=") -> form.substringAfterLast("=")
            form.contains("-") -> form.substringAfterLast("-")
            else -> form
        }
        return value.replace("_", " ").replace("-", " ")
            .split(" ").filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /** True when this species appears in an Alpha boss herd (as the Alpha itself). */
    fun hasAlphaSpawn(pokemonName: String): Boolean {
        val key = normalizeKey(pokemonName)
        return spawnMap[key]?.any { entry ->
            entry.herdMembers.any { it.isAlpha && normalizeKey(it.species) == key }
        } == true
    }

    fun getSpawns(pokemonName: String, formName: String = ""): List<SpawnEntry> {
        val key = normalizeKey(pokemonName)
        if (formName.isNotEmpty()) {
            val formKey = "$key ${formName.lowercase()}"
            val formSpawns = spawnMap[formKey]
            if (!formSpawns.isNullOrEmpty()) return formSpawns
        }
        return spawnMap[key] ?: emptyList()
    }

    /**
     * Drop everything derived from the server. Spawn pools, presets and habitats are
     * server data: keeping world A's pool while playing world B would show wrong spawns.
     */
    fun invalidate() {
        spawnMap = emptyMap()
        presetIndex = null
        extraLineCache.clear()
        isLoaded = false
        loadError = null
    }

    fun ensureLoaded() {
        if (isLoaded) return
        loadFromCobblemon()
        if (!isLoaded) {
            loadFromResources()
        }
    }

    /**
     * Lay the Tropimon server's spawn files over whatever was just loaded.
     *
     * A "replace" file stands in for the Cobblemon file of the same name, so every base
     * entry for the species it covers is dropped before its own are added — otherwise the
     * vanilla and server versions of a spawn would both be listed. Species are collected
     * across all replace files first, so two files touching one species cannot wipe each
     * other. "add" files (the fillers) are appended and nothing is removed.
     */
    private fun applyTropimonOverrides(map: MutableMap<String, MutableList<SpawnEntry>>) {
        val files = try { TropimonData.spawnPoolFiles() } catch (_: Exception) { emptyList() }
        if (files.isEmpty()) return

        val parsed = mutableListOf<Pair<Boolean, MutableMap<String, MutableList<SpawnEntry>>>>()
        val replacedSpecies = mutableSetOf<String>()

        for (file in files) {
            val overlay = mutableMapOf<String, MutableList<SpawnEntry>>()
            try { parseSpawnFile(file.json, overlay) } catch (e: Exception) {
                HunterBoard.LOGGER.warn("Tropimon spawn file ${file.fileName} unreadable: ${e.message}")
                continue
            }
            if (file.replacesVanilla) replacedSpecies.addAll(overlay.keys)
            parsed.add(file.replacesVanilla to overlay)
        }

        for (key in replacedSpecies) map.remove(key)
        var added = 0
        for ((_, overlay) in parsed) {
            for ((key, entries) in overlay) {
                map.getOrPut(key) { mutableListOf() }.addAll(entries)
                added += entries.size
            }
        }
        HunterBoard.LOGGER.info(
            "Tropimon overrides: ${replacedSpecies.size} species replaced, $added spawns applied"
        )
    }

    /**
     * Everything we can pull out of a spawn detail's condition list.
     * Shared between single-Pokemon spawns and herd spawns.
     */
    private class ConditionInfo {
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
        var moonPhases: List<Int> = emptyList()
        var slimeChunk = false
        var isPokeSnack = false
        val markers = mutableListOf<String>()
        var rodType: String? = null
        var bait: String? = null
        var minLureLevel: Int? = null
        var maxLureLevel: Int? = null
        var minDepth: Int? = null
        var maxDepth: Int? = null
        var fluid: String? = null
        var minHeight: Int? = null
        var maxHeight: Int? = null
        val dimensions = mutableListOf<String>()
    }

    /**
     * Read every condition on a spawn detail through the typed Cobblemon API.
     *
     * Cobblemon declares these as private fields with public getters, so the old
     * `Class.getField` reflection silently returned null for all of them; going
     * through the typed properties is both correct and checked at compile time.
     */
    private fun collectConditions(conditions: List<SpawningCondition<*>>): ConditionInfo {
        val info = ConditionInfo()
        for (condition in conditions) {
            try {
                extractBiomes(condition, info.biomeDetails)
                condition.timeRange?.let { info.time = identifyTimeRange(it) }
                extractWeather(condition)?.let { info.weather = it }
                extractStructures(condition, info.structures)
                condition.canSeeSky?.let { info.canSeeSky = it }
                condition.minY?.let { info.minY = it.toInt() }
                condition.maxY?.let { info.maxY = it.toInt() }
                condition.minLight?.let { info.minLight = it }
                condition.maxLight?.let { info.maxLight = it }
                condition.minSkyLight?.let { info.minSkyLight = it }
                condition.maxSkyLight?.let { info.maxSkyLight = it }
                condition.moonPhase?.let { info.moonPhases = phasesOf(it) }
                if (condition.isSlimeChunk == true) info.slimeChunk = true
                if (condition.isPokeSnack == true) info.isPokeSnack = true
                for (marker in condition.markers.orEmpty()) {
                    if (marker !in info.markers) info.markers.add(marker)
                }
                for (dim in condition.dimensions.orEmpty()) {
                    val id = dim.toString()
                    if (id !in info.dimensions) info.dimensions.add(id)
                }
            } catch (_: Exception) {}

            // Conditions that only exist on a specific spawnable-position type
            try {
                when (condition) {
                    is FishingSpawningCondition -> {
                        addBlocks(condition.neededNearbyBlocks, info.nearbyBlocks)
                        condition.minLureLevel?.let { info.minLureLevel = it }
                        condition.maxLureLevel?.let { info.maxLureLevel = it }
                        condition.bait?.let { info.bait = it.toString() }
                        condition.rodType?.let { info.rodType = it.toString() }
                    }
                    is AreaTypeSpawningCondition<*> -> {
                        addBlocks(condition.neededNearbyBlocks, info.nearbyBlocks)
                        condition.minHeight?.let { info.minHeight = it }
                        condition.maxHeight?.let { info.maxHeight = it }
                        when (condition) {
                            is GroundedTypeSpawningCondition<*> ->
                                addBlocks(condition.neededBaseBlocks, info.baseBlocks)
                            is SeafloorTypeSpawningCondition<*> ->
                                addBlocks(condition.neededBaseBlocks, info.baseBlocks)
                            is SubmergedTypeSpawningCondition<*> -> {
                                condition.minDepth?.let { info.minDepth = it }
                                condition.maxDepth?.let { info.maxDepth = it }
                                conditionName(condition.fluid)?.let { info.fluid = it }
                            }
                            is SurfaceTypeSpawningCondition<*> -> {
                                condition.minDepth?.let { info.minDepth = it }
                                condition.maxDepth?.let { info.maxDepth = it }
                                conditionName(condition.fluid)?.let { info.fluid = it }
                            }
                            else -> {}
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
        return info
    }

    /** Readable id of a RegistryLikeCondition ("#cobblemon:natural" for tags) */
    private fun conditionName(cond: Any?): String? = when (cond) {
        is RegistryLikeTagCondition<*> -> "#${cond.tag.id.namespace}:${cond.tag.id.path}"
        is RegistryLikeIdentifierCondition<*> -> "${cond.identifier.namespace}:${cond.identifier.path}"
        else -> null
    }

    private fun addBlocks(blocks: List<*>?, list: MutableList<String>) {
        if (blocks == null) return
        for (block in blocks) {
            val name = conditionName(block) ?: continue
            if (name !in list) list.add(name)
        }
    }

    /**
     * Flatten an IntRanges. Cobblemon 1.8 turned moonPhase into a MoonPhaseRange,
     * so the old "read an Int" path never matched. Absurdly wide ranges are dropped
     * so a malformed datapack cannot explode the list.
     */
    private fun phasesOf(ranges: IntRanges): List<Int> = try {
        ranges.ranges
            .filter { it.last - it.first in 0..15 }
            .flatMap { it.toList() }
            .distinct()
            .sorted()
    } catch (_: Exception) { emptyList() }

    /**
     * Spawn "presets" (natural, water, treetop...) are merged into the conditions when
     * Cobblemon loads a spawn, so the live API object no longer carries their names.
     * We recover them by indexing the shipped JSON by spawn id ("bulbasaur-1").
     */
    private var presetIndex: Map<String, List<String>>? = null

    private fun presetsFor(id: String?): List<String> {
        if (id.isNullOrEmpty()) return emptyList()
        return presetIndex?.get(id) ?: emptyList()
    }

    private fun buildPresetIndex() {
        if (presetIndex != null) return
        val index = mutableMapOf<String, List<String>>()
        try {
            CobblemonResources.forEachJson(SPAWN_POOL_FOLDER) { file ->
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                val spawns = root.getAsJsonArray("spawns") ?: return@forEachJson
                for (element in spawns) {
                    val obj = element.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val presets = obj.getAsJsonArray("presets")
                        ?.mapNotNull { el -> el.asString.takeIf { it.isNotEmpty() } }
                        ?: emptyList()
                    if (presets.isNotEmpty()) index[id] = presets
                }
            }
        } catch (_: Exception) {}
        presetIndex = index
    }

    private fun loadFromCobblemon() {
        extraLineCache.clear()
        try {
            val pool = CobblemonSpawnPools.WORLD_SPAWN_POOL
            val details = pool.details
            if (details.isEmpty()) return // Pool not yet populated

            buildPresetIndex()
            val map = mutableMapOf<String, MutableList<SpawnEntry>>()

            for (detail in details) {
                try {
                    when (detail) {
                        is PokemonSpawnDetail -> readPokemonDetail(detail, map)
                        is PokemonHerdSpawnDetail -> readHerdDetail(detail, map)
                        else -> {}
                    }
                } catch (_: Exception) {}
            }

            applyTropimonOverrides(map)
            for (entries in map.values) mergeSnackVariants(entries)
            spawnMap = map
            isLoaded = true
            HunterBoard.LOGGER.info("Loaded spawn data from Cobblemon API: ${map.size} species")
        } catch (e: Exception) {
            loadError = e.message
            HunterBoard.LOGGER.error("Failed to load spawn data from API: ${e.message}")
        }
    }

    /** Everything shared by every SpawnDetail subtype; level range is filled in by the caller. */
    private fun baseEntry(detail: SpawnDetail, info: ConditionInfo): SpawnEntry {
        val excludedBiomes = mutableListOf<BiomeDetail>()
        try {
            for (anti in detail.anticonditions) extractBiomes(anti, excludedBiomes)
        } catch (_: Exception) {}

        val presetList = presetsFor(try { detail.id } catch (_: Exception) { null })
        val slimeChunk = info.slimeChunk || presetList.any { it.contains("slime", ignoreCase = true) }

        // 1.8: spawnablePositionType is a typed object, not a String
        val spawnCtx = try {
            detail.spawnablePositionType?.name?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        val spawnWeight = try { detail.weight.toDouble() } catch (_: Exception) { null }

        return SpawnEntry(
            biomes = info.biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
            biomeDetails = info.biomeDetails.toList(),
            time = info.time,
            weather = info.weather,
            lvMin = 0,
            lvMax = 0,
            context = "",
            // 1.8: bucket is a plain String (SpawnBucket was removed) and "boss" is new
            bucket = try { detail.bucket } catch (_: Exception) { "" },
            structures = info.structures.toList(),
            canSeeSky = info.canSeeSky,
            minY = info.minY,
            maxY = info.maxY,
            neededNearbyBlocks = info.nearbyBlocks.toList(),
            neededBaseBlocks = info.baseBlocks.toList(),
            minLight = info.minLight,
            maxLight = info.maxLight,
            minSkyLight = info.minSkyLight,
            maxSkyLight = info.maxSkyLight,
            moonPhase = info.moonPhases.firstOrNull(),
            weight = spawnWeight,
            spawnContext = spawnCtx,
            excludedBiomes = excludedBiomes.toList(),
            presets = presetList,
            weightMultipliers = extractWeightMultipliers(detail),
            requiresSlimeChunk = slimeChunk,
            isPokeSnack = info.isPokeSnack,
            moonPhases = info.moonPhases,
            markers = info.markers.toList(),
            rodType = info.rodType,
            bait = info.bait,
            minLureLevel = info.minLureLevel,
            maxLureLevel = info.maxLureLevel,
            minDepth = info.minDepth,
            maxDepth = info.maxDepth,
            fluid = info.fluid,
            minHeight = info.minHeight,
            maxHeight = info.maxHeight,
            dimensions = info.dimensions.toList()
        )
    }

    private fun readPokemonDetail(detail: PokemonSpawnDetail, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val species = detail.pokemon.species?.lowercase() ?: return
        val form = try { detail.pokemon.aspects.firstOrNull()?.lowercase() ?: "" } catch (_: Exception) { "" }

        val info = collectConditions(detail.conditions)
        val lvRange = detail.levelRange
        val heldItems = try {
            detail.heldItems.orEmpty().map { HeldItemData(it.item, it.percentage) }
        } catch (_: Exception) { emptyList() }

        val entry = baseEntry(detail, info).copy(
            lvMin = lvRange?.first ?: 0,
            lvMax = lvRange?.last ?: 0,
            heldItems = heldItems
        )
        storeSpawn(map, species, form, entry)
    }

    /**
     * Herd spawns ("pokemon-herd") were skipped entirely by the API path, so every
     * herd-only Pokemon looked like it had no spawns at all. Each member is stored as
     * its own entry, tagged with the herd size and the species it travels with.
     */
    private fun readHerdDetail(detail: PokemonHerdSpawnDetail, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val members = try { detail.herdablePokemon } catch (_: Exception) { return }
        if (members.isEmpty()) return

        val info = collectConditions(detail.conditions)
        val base = baseEntry(detail, info)
        val herdLevels = detail.levelRange

        val memberData = members.mapNotNull { member ->
            val name = member.pokemon.species?.lowercase() ?: return@mapNotNull null
            val range = member.levelRange ?: herdLevels
            HerdMemberData(
                species = name,
                weight = member.weight,
                lvMin = range?.first ?: 0,
                lvMax = range?.last ?: 0,
                maxTimes = member.maxTimes,
                isLeader = member.isLeader,
                isAlpha = try { member.pokemon.isAlpha == true } catch (_: Exception) { false }
            )
        }

        for (member in members) {
            val species = member.pokemon.species?.lowercase() ?: continue
            val form = try { member.pokemon.aspects.firstOrNull()?.lowercase() ?: "" } catch (_: Exception) { "" }
            val range = member.levelRange ?: herdLevels
            val entry = base.copy(
                lvMin = range?.first ?: 0,
                lvMax = range?.last ?: 0,
                herdSize = detail.maxHerdSize,
                herdMembers = memberData,
                isAlphaSpawn = memberData.any { it.isAlpha }
            )
            storeSpawn(map, species, form, entry)
        }
    }

    /**
     * Fallback: reads spawn JSON files directly from Cobblemon's mod JAR.
     * Works on servers where the spawn pool API is not populated client-side.
     */
    private fun loadFromResources() {
        try {
            val map = mutableMapOf<String, MutableList<SpawnEntry>>()

            // Every mod, not just Cobblemon: add-ons and biome-compat packs ship
            // their spawns under their own namespace.
            CobblemonResources.forEachJson(SPAWN_POOL_FOLDER) { file ->
                parseSpawnFile(Files.readString(file), map)
            }

            if (map.isNotEmpty()) {
                applyTropimonOverrides(map)
                for (entries in map.values) mergeSnackVariants(entries)
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

    /** Read a JSON "condition"/"anticondition" object into the same shape the API path produces. */
    private fun parseCondition(condition: JsonObject, info: ConditionInfo) {
        parseConditionBiomes(condition, info.biomeDetails)
        condition.get("timeRange")?.asString?.let { info.time = it }
        parseConditionWeather(condition)?.let { info.weather = it }
        parseConditionStructures(condition, info.structures)
        jsonBool(condition, "canSeeSky")?.let { info.canSeeSky = it }
        jsonInt(condition, "minY")?.let { info.minY = it }
        jsonInt(condition, "maxY")?.let { info.maxY = it }
        jsonInt(condition, "minLight")?.let { info.minLight = it }
        jsonInt(condition, "maxLight")?.let { info.maxLight = it }
        jsonInt(condition, "minSkyLight")?.let { info.minSkyLight = it }
        jsonInt(condition, "maxSkyLight")?.let { info.maxSkyLight = it }
        parseConditionBlocks(condition, "neededNearbyBlocks", info.nearbyBlocks)
        parseConditionBlocks(condition, "neededBaseBlocks", info.baseBlocks)
        if (jsonBool(condition, "isSlimeChunk") == true) info.slimeChunk = true

        // ---- Cobblemon 1.8 ----
        if (jsonBool(condition, "isPokeSnack") == true) info.isPokeSnack = true
        // moonPhase became a MoonPhaseRange, serialised as "0" or "2-4"
        condition.get("moonPhase")?.let { el ->
            val phases = parsePhaseRange(try { el.asString } catch (_: Exception) { null })
            if (phases.isNotEmpty()) info.moonPhases = phases
        }
        condition.getAsJsonArray("markers")?.forEach { el ->
            val marker = try { el.asString } catch (_: Exception) { null } ?: return@forEach
            if (marker.isNotEmpty() && marker !in info.markers) info.markers.add(marker)
        }
        condition.getAsJsonArray("dimensions")?.forEach { el ->
            val dim = try { el.asString } catch (_: Exception) { null } ?: return@forEach
            if (dim.isNotEmpty() && dim !in info.dimensions) info.dimensions.add(dim)
        }
        condition.get("rodType")?.asString?.let { info.rodType = it }
        condition.get("bait")?.asString?.let { info.bait = it }
        jsonInt(condition, "minLureLevel")?.let { info.minLureLevel = it }
        jsonInt(condition, "maxLureLevel")?.let { info.maxLureLevel = it }
        jsonInt(condition, "minDepth")?.let { info.minDepth = it }
        jsonInt(condition, "maxDepth")?.let { info.maxDepth = it }
        jsonInt(condition, "minHeight")?.let { info.minHeight = it }
        jsonInt(condition, "maxHeight")?.let { info.maxHeight = it }
        condition.get("fluid")?.asString?.let { info.fluid = it }
    }

    private fun jsonInt(obj: JsonObject, key: String): Int? =
        try { if (obj.has(key)) obj.get(key).asInt else null } catch (_: Exception) { null }

    private fun jsonBool(obj: JsonObject, key: String): Boolean? =
        try { if (obj.has(key)) obj.get(key).asBoolean else null } catch (_: Exception) { null }

    /** "0" -> [0], "2-4" -> [2,3,4], "0,4" -> [0,4] */
    private fun parsePhaseRange(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Int>()
        for (chunk in raw.split(",")) {
            val part = chunk.trim()
            if (part.isEmpty()) continue
            val dash = part.indexOf('-', 1)
            if (dash > 0) {
                val from = part.substring(0, dash).trim().toIntOrNull()
                val to = part.substring(dash + 1).trim().toIntOrNull()
                if (from != null && to != null && to - from in 0..15) {
                    for (v in from..to) if (v !in out) out.add(v)
                }
            } else {
                part.toIntOrNull()?.let { if (it !in out) out.add(it) }
            }
        }
        return out.sorted()
    }

    /** Shared "everything except species and level" for a JSON spawn object. */
    private fun jsonBaseEntry(obj: JsonObject, info: ConditionInfo): SpawnEntry {
        val excludedBiomes = mutableListOf<BiomeDetail>()
        obj.getAsJsonObject("anticondition")?.let { anti -> parseConditionBiomes(anti, excludedBiomes) }

        val presetList = mutableListOf<String>()
        obj.getAsJsonArray("presets")?.forEach { el ->
            val preset = try { el.asString } catch (_: Exception) { null } ?: return@forEach
            if (preset.isNotEmpty()) presetList.add(preset)
        }
        val slimeChunk = info.slimeChunk || presetList.any { it.contains("slime", ignoreCase = true) }

        val heldItems = mutableListOf<HeldItemData>()
        obj.getAsJsonArray("heldItems")?.forEach { el ->
            try {
                val item = el.asJsonObject
                val id = item.get("item")?.asString ?: return@forEach
                heldItems.add(HeldItemData(id, item.get("percentage")?.asDouble ?: 100.0))
            } catch (_: Exception) {}
        }

        return SpawnEntry(
            biomes = info.biomeDetails.joinToString(", ") { it.displayName }.ifEmpty { "Any" },
            biomeDetails = info.biomeDetails.toList(),
            time = info.time,
            weather = info.weather,
            lvMin = 0,
            lvMax = 0,
            context = "",
            bucket = obj.get("bucket")?.asString ?: "common",
            structures = info.structures.toList(),
            canSeeSky = info.canSeeSky,
            minY = info.minY,
            maxY = info.maxY,
            neededNearbyBlocks = info.nearbyBlocks.toList(),
            neededBaseBlocks = info.baseBlocks.toList(),
            minLight = info.minLight,
            maxLight = info.maxLight,
            minSkyLight = info.minSkyLight,
            maxSkyLight = info.maxSkyLight,
            moonPhase = info.moonPhases.firstOrNull(),
            weight = try { obj.get("weight")?.asDouble } catch (_: Exception) { null },
            spawnContext = obj.get("spawnablePositionType")?.asString?.lowercase() ?: "",
            excludedBiomes = excludedBiomes.toList(),
            presets = presetList.toList(),
            weightMultipliers = parseJsonWeightMultipliers(obj),
            requiresSlimeChunk = slimeChunk,
            isPokeSnack = info.isPokeSnack,
            moonPhases = info.moonPhases,
            markers = info.markers.toList(),
            rodType = info.rodType,
            bait = info.bait,
            minLureLevel = info.minLureLevel,
            maxLureLevel = info.maxLureLevel,
            minDepth = info.minDepth,
            maxDepth = info.maxDepth,
            fluid = info.fluid,
            minHeight = info.minHeight,
            maxHeight = info.maxHeight,
            dimensions = info.dimensions.toList(),
            heldItems = heldItems.toList()
        )
    }

    private fun parseLevelRange(raw: String?): Pair<Int, Int> {
        if (raw.isNullOrBlank()) return 1 to 1
        val parts = raw.split("-")
        val lo = parts.firstOrNull()?.trim()?.toIntOrNull() ?: 1
        val hi = parts.lastOrNull()?.trim()?.toIntOrNull() ?: lo
        return lo to hi
    }

    private fun parsePokemonSpawn(obj: JsonObject, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val pokemonStr = obj.get("pokemon")?.asString ?: return
        val parts = pokemonStr.split(" ")
        val species = parts.first().lowercase()
        val form = if (parts.size > 1) parts.drop(1).joinToString(" ").lowercase() else ""

        val info = ConditionInfo()
        obj.getAsJsonObject("condition")?.let { parseCondition(it, info) }

        val (lvMin, lvMax) = parseLevelRange(obj.get("level")?.asString)
        // Tropimon names every filler "<species>-filler-<position>"; no spawn in Cobblemon's
        // own 1544 pool files carries "filler" in its id, so this cannot collide.
        val filler = try { obj.get("id")?.asString?.contains("filler") == true } catch (_: Exception) { false }
        storeSpawn(map, species, form,
            jsonBaseEntry(obj, info).copy(lvMin = lvMin, lvMax = lvMax, isFiller = filler))
    }

    private fun parseHerdSpawn(obj: JsonObject, map: MutableMap<String, MutableList<SpawnEntry>>) {
        val herdPokemon = obj.getAsJsonArray("herdablePokemon") ?: return

        val info = ConditionInfo()
        obj.getAsJsonObject("condition")?.let { parseCondition(it, info) }

        val base = jsonBaseEntry(obj, info)
        val herdSize = jsonInt(obj, "maxHerdSize")
        val herdLevel = obj.get("levelRange")?.asString ?: obj.get("level")?.asString

        val memberData = mutableListOf<HerdMemberData>()
        for (member in herdPokemon) {
            try {
                val memberObj = member.asJsonObject
                val name = memberObj.get("pokemon")?.asString?.split(" ")?.first()?.lowercase() ?: continue
                val (lo, hi) = parseLevelRange(memberObj.get("levelRange")?.asString ?: herdLevel)
                memberData.add(HerdMemberData(
                    species = name,
                    weight = try { memberObj.get("weight")?.asFloat ?: 0f } catch (_: Exception) { 0f },
                    lvMin = lo,
                    lvMax = hi,
                    maxTimes = jsonInt(memberObj, "maxTimes") ?: 0,
                    isLeader = jsonBool(memberObj, "isLeader") ?: false,
                    isAlpha = memberObj.get("pokemon")?.asString?.contains("alpha=true") == true
                ))
            } catch (_: Exception) {}
        }

        for (member in herdPokemon) {
            val memberObj = try { member.asJsonObject } catch (_: Exception) { continue }
            val pokemonStr = memberObj.get("pokemon")?.asString ?: continue
            val herdParts = pokemonStr.split(" ")
            val species = herdParts.first().lowercase()
            val form = if (herdParts.size > 1) herdParts.drop(1).joinToString(" ").lowercase() else ""
            val (lvMin, lvMax) = parseLevelRange(memberObj.get("levelRange")?.asString ?: herdLevel)

            storeSpawn(map, species, form, base.copy(
                lvMin = lvMin,
                lvMax = lvMax,
                herdSize = herdSize,
                herdMembers = memberData.toList(),
                isAlphaSpawn = memberData.any { it.isAlpha }
            ))
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

    /**
     * Structures are `Either<Identifier, TagKey<Structure>>`; the old code stringified
     * the Either itself, producing names like "Village Plains]".
     */
    private fun extractStructures(condition: SpawningCondition<*>, list: MutableList<String>) {
        try {
            val structures = condition.structures ?: return
            for (structure in structures) {
                val raw = structure.map({ id -> id.toString() }, { tag -> "#" + tag.id.toString() })
                val name = formatStructureId(raw)
                if (name.isNotEmpty() && name !in list) list.add(name)
            }
        } catch (_: Exception) {}
    }

    /** Extract weight multipliers from Cobblemon API (SpawnDetail.weightMultipliers) */
    private fun extractWeightMultipliers(detail: SpawnDetail): List<WeightMultiplierData> {
        return try {
            val wmList = detail.weightMultipliers
            if (wmList.isEmpty()) return emptyList()
            wmList.mapNotNull { wm ->
                try {
                    WeightMultiplierData(wm.multiplier, describeMultiplierConditions(wm.conditions))
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
                cond.timeRange?.let { parts.add(identifyTimeRange(it)) }
                if (cond.isPokeSnack == true) parts.add("pokesnack")
                cond.moonPhase?.let { range ->
                    val phases = phasesOf(range)
                    if (phases.isNotEmpty()) parts.add("moon:" + phases.joinToString("/"))
                }
            } catch (_: Exception) {}
            try {
                if (cond is FishingSpawningCondition) {
                    val minLure = cond.minLureLevel
                    val maxLure = cond.maxLureLevel
                    if (minLure != null || maxLure != null) {
                        parts.add(when {
                            minLure != null && maxLure != null && minLure == maxLure -> "lure:$minLure"
                            minLure != null && maxLure != null -> "lure:$minLure-$maxLure"
                            minLure != null -> "lure:$minLure+"
                            else -> "lure:$maxLure"
                        })
                    }
                    cond.bait?.let { parts.add("bait:$it") }
                    cond.rodType?.let { parts.add("rod:$it") }
                }
            } catch (_: Exception) {}
        }
        return if (parts.isEmpty()) "?" else parts.joinToString(", ")
    }

    /**
     * Cobblemon 1.8 ships most Poké Snack spawns as a duplicate of an existing natural
     * spawn with `isPokeSnack` added. Listing both would double the spawn list for no
     * new information, so a snack entry that matches a natural one just flags it;
     * snack entries with no natural twin are kept and marked as snack-only.
     */
    private fun mergeSnackVariants(entries: MutableList<SpawnEntry>) {
        val snackEntries = entries.filter { it.isPokeSnack }
        if (snackEntries.isEmpty()) return
        for (snack in snackEntries) {
            val naturalTwin = snack.copy(isPokeSnack = false)
            val twinIndex = entries.indexOfFirst { !it.isPokeSnack && it == naturalTwin }
            if (twinIndex >= 0) {
                entries[twinIndex] = entries[twinIndex].copy(isPokeSnack = true)
                entries.remove(snack)
            } else {
                val index = entries.indexOf(snack)
                if (index >= 0) entries[index] = snack.copy(pokeSnackOnly = true)
            }
        }
    }

    /**
     * Key used by the screens to collapse near-identical spawn entries (level range aside).
     * Kept here so the render pass and the card-height pass can never drift apart.
     */
    fun dedupKey(spawn: SpawnEntry): String = buildString {
        append(spawn.biomes).append('|')
        append(spawn.time).append('|')
        append(spawn.weather).append('|')
        append(spawn.structures).append('|')
        append(spawn.canSeeSky).append('|')
        append(spawn.bucket).append('|')
        append(spawn.spawnContext).append('|')
        append(spawn.minY).append('|')
        append(spawn.maxY).append('|')
        append(spawn.minSkyLight).append('|')
        append(spawn.maxSkyLight).append('|')
        append(spawn.presets).append('|')
        append(spawn.formLabel).append('|')
        // Cobblemon 1.8 discriminators, otherwise e.g. two fishing spawns that differ
        // only by rod or bait would collapse into a single line.
        append(spawn.isPokeSnack).append('|')
        append(spawn.pokeSnackOnly).append('|')
        append(spawn.rodType).append('|')
        append(spawn.bait).append('|')
        append(spawn.minLureLevel).append('|')
        append(spawn.maxLureLevel).append('|')
        append(spawn.fluid).append('|')
        append(spawn.minDepth).append('|')
        append(spawn.maxDepth).append('|')
        append(spawn.herdSize).append('|')
        append(spawn.markers).append('|')
        append(spawn.dimensions).append('|')
        // a filler must never collapse into a real spawn that happens to match: the badge
        // is the whole point of listing it
        append(spawn.isFiller)
    }

    // ---- Cobblemon 1.8 display helpers -------------------------------------------------
    // Both PokemonDetailScreen and SpawnInfoScreen draw the same spawn block, so the
    // extra 1.8 lines are built once here; the screens just draw (and count) them.

    private const val COLOR_SNACK = 0xFFFF9955.toInt()
    private const val COLOR_FISHING = 0xFF55BBFF.toInt()
    private const val COLOR_HERD = 0xFFBB88DD.toInt()
    private const val COLOR_ALPHA = 0xFFFF5544.toInt()
    private const val COLOR_ITEM = 0xFF88CCAA.toInt()
    private const val COLOR_NEUTRAL = 0xFF887755.toInt()

    /**
     * Extra information introduced or fixed in Cobblemon 1.8: Poke Snack spawns,
     * fishing requirements (rod / bait / lure), water depth, herds, held items,
     * markers and dimension restrictions.
     */
    // The screens call this once per spawn per frame, from both the render pass and the
    // card-height pass, and it does registry lookups. Entries live in `spawnMap` for the
    // session, so an identity cache keyed by the current language is enough.
    private val extraLineCache = java.util.IdentityHashMap<SpawnEntry, List<SpawnLine>>()
    private var extraLineCacheLang = ""

    private fun languageToken(): String =
        Translations.effectiveNameLanguage() + "/" + Translations.isFrench()

    fun extraLines(spawn: SpawnEntry): List<SpawnLine> {
        val lang = languageToken()
        if (lang != extraLineCacheLang) {
            extraLineCache.clear()
            extraLineCacheLang = lang
        }
        extraLineCache[spawn]?.let { return it }
        val built = buildExtraLines(spawn)
        extraLineCache[spawn] = built
        return built
    }

    private fun buildExtraLines(spawn: SpawnEntry): List<SpawnLine> {
        val lines = mutableListOf<SpawnLine>()

        // Poke Snack spawning (new in 1.8): dropped food attracts extra Pokemon
        if (spawn.pokeSnackOnly) {
            lines.add(SpawnLine("  \u2618 ${Translations.tr("Poke Snack only")}", COLOR_SNACK))
        } else if (spawn.isPokeSnack) {
            lines.add(SpawnLine("  \u2618 ${Translations.tr("Also via Poke Snack")}", COLOR_SNACK))
        }

        // Fishing requirements
        val fishing = mutableListOf<String>()
        spawn.rodType?.let { fishing.add("${Translations.tr("Rod:")} ${Translations.itemName(it)}") }
        spawn.bait?.let { fishing.add("${Translations.tr("Bait:")} ${Translations.itemName(it)}") }
        if (spawn.minLureLevel != null || spawn.maxLureLevel != null) {
            val lureLabel = Translations.tr("Lure")
            val min = spawn.minLureLevel
            val max = spawn.maxLureLevel
            fishing.add(when {
                min != null && max != null && min == max -> "$lureLabel $min"
                min != null && max != null -> "$lureLabel $min-$max"
                min != null -> "$lureLabel $min+"
                else -> "$lureLabel \u2264$max"
            })
        }
        if (fishing.isNotEmpty()) {
            lines.add(SpawnLine("  \u2635 ${fishing.joinToString(" | ")}", COLOR_FISHING))
        }

        // Water depth / fluid / required free space
        val space = mutableListOf<String>()
        if (spawn.minDepth != null || spawn.maxDepth != null) {
            space.add("${Translations.tr("Depth:")} ${spawn.minDepth ?: "?"}-${spawn.maxDepth ?: "?"}")
        }
        spawn.fluid?.let { space.add("${Translations.tr("Fluid:")} ${Translations.blockName(it)}") }
        if (spawn.minHeight != null || spawn.maxHeight != null) {
            space.add("${Translations.tr("Space:")} ${spawn.minHeight ?: "?"}-${spawn.maxHeight ?: "?"}")
        }
        if (space.isNotEmpty()) {
            lines.add(SpawnLine("  ${space.joinToString(" | ")}", COLOR_NEUTRAL))
        }

        // Alpha boss herd — the whole "boss" bucket is Alpha spawns in 1.8
        if (spawn.isAlphaSpawn) {
            val alpha = spawn.herdMembers.firstOrNull { it.isAlpha }
            val label = if (alpha != null)
                "${Translations.tr("Alpha:")} ${Translations.pokemonName(alpha.species)} (Lv.${alpha.lvMin}-${alpha.lvMax})"
            else Translations.tr("Alpha boss herd")
            lines.add(SpawnLine("  \u2726 $label", COLOR_ALPHA))
        }

        // Herd composition
        if (spawn.herdMembers.isNotEmpty()) {
            val size = spawn.herdSize?.let { "\u00d7$it" } ?: ""
            val names = spawn.herdMembers.joinToString(", ") { member ->
                val name = Translations.pokemonName(member.species)
                when {
                    member.isAlpha -> "$name\u2726"
                    member.isLeader -> "$name\u2605"
                    else -> name
                }
            }
            lines.add(SpawnLine("  \u26F0 ${Translations.tr("Herd")}$size: $names", COLOR_HERD))
        }

        // Items the Pokemon can spawn holding
        if (spawn.heldItems.isNotEmpty()) {
            val items = spawn.heldItems.joinToString(", ") { held ->
                "${Translations.itemName(held.item)} (${trimPercent(held.percentage)}%)"
            }
            lines.add(SpawnLine("  \u2692 ${Translations.tr("Held:")} $items", COLOR_ITEM))
        }

        // Markers and dimension restrictions
        if (spawn.markers.isNotEmpty()) {
            lines.add(SpawnLine("  ${Translations.tr("Marker:")} ${spawn.markers.joinToString(", ")}", COLOR_NEUTRAL))
        }
        if (spawn.dimensions.isNotEmpty()) {
            val names = spawn.dimensions.joinToString(", ") { Translations.dimensionName(it) }
            lines.add(SpawnLine("  ${Translations.tr("Dimension:")} $names", COLOR_NEUTRAL))
        }

        return lines
    }

    private fun trimPercent(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)

    /** Every moon phase this spawn accepts, already translated ("Full Moon, New Moon"). */
    fun moonPhaseLabel(spawn: SpawnEntry): String? {
        val phases = spawn.moonPhases.ifEmpty { listOfNotNull(spawn.moonPhase) }
        if (phases.isEmpty()) return null
        return phases.joinToString(", ") { Translations.formatMoonPhase(it) }
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
