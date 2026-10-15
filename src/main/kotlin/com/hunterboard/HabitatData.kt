package com.hunterboard

import com.cobblemon.mod.common.api.habitats.HabitatPools
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.MinecraftClient
import net.minecraft.registry.RegistryKeys
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.nio.file.Files

/**
 * One way a species can appear out of a habitat block.
 *
 * Habitat blocks are new in Cobblemon 1.8: they are placed in the world, grow through
 * a number of phases, and spawn from their own pool instead of the world spawn pool.
 * None of this shows up in `CobblemonSpawnPools.WORLD_SPAWN_POOL`, so a Pokémon that
 * only comes from a habitat looks like it has no spawns at all.
 */
data class HabitatEntry(
    val habitatId: String,
    /** Translation key shipped by Cobblemon, e.g. "cobblemon.habitat.berry_patch.name" */
    val habitatName: String,
    val bucket: String,
    val lvMin: Int,
    val lvMax: Int,
    val weight: Float,
    val phases: List<Int>,
    val positionType: String,
    val time: String,
    val minLight: Int?,
    val maxLight: Int?
)

/** One habitat pool, summarised for the wiki list. */
data class HabitatSummary(
    val habitatId: String,
    val displayName: String,
    val speciesCount: Int,
    val phaseCount: Int,
    val entries: List<HabitatEntry>,
    /** Normalised species keys found in this habitat, for searching. */
    val speciesKeys: List<String>
)

object HabitatData {

    private var habitatMap: Map<String, List<HabitatEntry>> = emptyMap()
    var isLoaded = false
        private set

    private fun normalizeKey(name: String): String = NameUtil.normalize(name)

    fun getHabitats(pokemonName: String): List<HabitatEntry> =
        habitatMap[normalizeKey(pokemonName)] ?: emptyList()

    fun invalidate() {
        habitatMap = emptyMap()
        lineCache.clear()
        biomeSpecIndex = null
        isLoaded = false
    }

    fun ensureLoaded() {
        if (isLoaded) return
        loadFromCobblemon()
        if (!isLoaded) loadFromResources()
    }

    /**
     * Lay the Tropimon server's habitat pools over whatever was just loaded.
     *
     * 46 of the 53 pools the server ships differ from Cobblemon's — different species,
     * and often a different number of phases. Nothing about that reaches the client
     * (`HabitatPools.sync()` sends no packet), so the pools are replaced wholesale here,
     * by habitat id: every entry belonging to a pool the server redefines is dropped
     * before the server's own spawns are read in.
     */
    private fun applyTropimonOverrides(map: MutableMap<String, MutableList<HabitatEntry>>) {
        val files = try { TropimonData.habitatPoolFiles() } catch (_: Exception) { emptyList() }
        if (files.isEmpty()) return

        val overridden = files.map { "cobblemon:" + it.fileName.removeSuffix(".json") }.toSet()
        for (entries in map.values) entries.removeAll { it.habitatId in overridden }

        var read = 0
        for (file in files) {
            try {
                val root = JsonParser.parseString(file.json).asJsonObject
                val before = map.values.sumOf { it.size }
                parseHabitatFile(root, file.fileName.removeSuffix(".json"), map)
                read += map.values.sumOf { it.size } - before
            } catch (e: Exception) {
                HunterBoard.LOGGER.warn("Tropimon habitat file ${file.fileName} unreadable: ${e.message}")
            }
        }
        // a species can end up with no habitat left at all
        map.entries.removeAll { it.value.isEmpty() }
        HunterBoard.LOGGER.info("Tropimon overrides: ${overridden.size} habitat pools replaced, $read spawns applied")
    }

    /** Live registry — populated on the client once the server syncs its habitat pools. */
    private fun loadFromCobblemon() {
        try {
            val pools = HabitatPools.habitatPoolsById
            if (pools.isEmpty()) return

            val map = mutableMapOf<String, MutableList<HabitatEntry>>()
            for ((id, pool) in pools) {
                val habitatId = id.toString()
                val habitatName = try { pool.name } catch (_: Exception) { habitatId }
                for (spawn in pool.spawns) {
                    try {
                        val species = spawn.species?.name?.lowercase() ?: continue
                        val levels = spawn.levelRange
                        map.getOrPut(normalizeKey(species)) { mutableListOf() }.add(
                            HabitatEntry(
                                habitatId = habitatId,
                                habitatName = habitatName,
                                bucket = spawn.bucket,
                                lvMin = levels?.first ?: 0,
                                lvMax = levels?.last ?: 0,
                                weight = spawn.weight,
                                phases = spawn.phases?.ranges
                                    ?.filter { it.last - it.first in 0..31 }
                                    ?.flatMap { it.toList() }?.distinct()?.sorted()
                                    ?: emptyList(),
                                positionType = spawn.spawnablePositionType?.lowercase() ?: "",
                                time = spawn.timeRange?.let { identifyTimeRange(it) } ?: "any",
                                minLight = spawn.minLight,
                                maxLight = spawn.maxLight
                            )
                        )
                    } catch (_: Exception) {}
                }
            }

            if (map.isNotEmpty()) {
                applyTropimonOverrides(map)
                habitatMap = map
                isLoaded = true
                HunterBoard.LOGGER.info("Loaded habitat data from Cobblemon API: ${map.size} species")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load habitat data from API: ${e.message}")
        }
    }

    private fun identifyTimeRange(timeRange: Any): String {
        return try {
            val ranges = (timeRange as? com.cobblemon.mod.common.api.spawning.TimeRange)?.ranges ?: return "any"
            for ((name, range) in com.cobblemon.mod.common.api.spawning.TimeRange.timeRanges) {
                if (range.ranges == ranges) return name
            }
            "any"
        } catch (_: Exception) { "any" }
    }

    /** Fallback: read the habitat pool JSON straight out of the Cobblemon jar. */
    private fun loadFromResources() {
        try {
            val map = mutableMapOf<String, MutableList<HabitatEntry>>()

            CobblemonResources.forEachJson("habitat_pools") { file ->
                val fileName = file.fileName.toString().removeSuffix(".json")
                parseHabitatFile(JsonParser.parseString(Files.readString(file)).asJsonObject, fileName, map)
            }

            if (map.isNotEmpty()) {
                applyTropimonOverrides(map)
                habitatMap = map
                isLoaded = true
                HunterBoard.LOGGER.info("Loaded habitat data from Cobblemon resources: ${map.size} species")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load habitat data from resources: ${e.message}")
        }
    }

    private fun parseHabitatFile(root: JsonObject, fileName: String, map: MutableMap<String, MutableList<HabitatEntry>>) {
        val habitatId = "cobblemon:$fileName"
        val habitatName = root.get("name")?.asString ?: habitatId
        val spawns = root.getAsJsonArray("spawns") ?: return

        for (element in spawns) {
            try {
                val obj = element.asJsonObject
                val species = obj.get("species")?.asString?.lowercase() ?: continue
                val (lvMin, lvMax) = parseRange(obj.get("levelRange")?.asString)
                map.getOrPut(normalizeKey(species)) { mutableListOf() }.add(
                    HabitatEntry(
                        habitatId = habitatId,
                        habitatName = habitatName,
                        bucket = obj.get("bucket")?.asString ?: "common",
                        lvMin = lvMin,
                        lvMax = lvMax,
                        weight = try { obj.get("weight")?.asFloat ?: 0f } catch (_: Exception) { 0f },
                        phases = parsePhases(obj.get("phases")?.asString),
                        positionType = obj.get("spawnablePositionType")?.asString?.lowercase() ?: "",
                        time = obj.get("timeRange")?.asString ?: "any",
                        minLight = try { if (obj.has("minLight")) obj.get("minLight").asInt else null } catch (_: Exception) { null },
                        maxLight = try { if (obj.has("maxLight")) obj.get("maxLight").asInt else null } catch (_: Exception) { null }
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun parseRange(raw: String?): Pair<Int, Int> {
        if (raw.isNullOrBlank()) return 0 to 0
        val parts = raw.split("-")
        val lo = parts.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        val hi = parts.lastOrNull()?.trim()?.toIntOrNull() ?: lo
        return lo to hi
    }

    /** "1", "1-5" or "1,3-4" -> the list of phases */
    private fun parsePhases(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Int>()
        for (chunk in raw.split(",")) {
            val part = chunk.trim()
            if (part.isEmpty()) continue
            val dash = part.indexOf('-', 1)
            if (dash > 0) {
                val from = part.substring(0, dash).trim().toIntOrNull()
                val to = part.substring(dash + 1).trim().toIntOrNull()
                if (from != null && to != null && to - from in 0..31) {
                    for (v in from..to) if (v !in out) out.add(v)
                }
            } else {
                part.toIntOrNull()?.let { if (it !in out) out.add(it) }
            }
        }
        return out.sorted()
    }

    // ---- Where a habitat generates -------------------------------------------------
    //
    // This cannot come from the registries: RegistryKeys.STRUCTURE is not synced to the
    // client at all, and the Biome objects that ARE synced carry only climate and
    // effects — their generation settings are stripped from the network codec. Asking
    // the client for either returned nothing, which is why every habitat used to say
    // "biomes unknown".
    //
    // The structure JSON shipped in the jar does declare them
    // ("biomes": "#cobblemon:is_cherry_blossom"), and biome *tags* are synced, so the
    // tag resolves against the live registry through BiomeTagResolver.

    private var biomeSpecIndex: Map<String, List<String>>? = null

    private fun normalizeId(id: String): String =
        id.substringAfter(":").substringAfterLast("/").replace("_", "").removeSuffix("s")

    private fun buildBiomeSpecIndex(): Map<String, List<String>> {
        val index = mutableMapOf<String, List<String>>()
        try {
            CobblemonResources.forEachJson("worldgen/structure/habitats") { file ->
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                val element = root.get("biomes") ?: return@forEachJson
                val specs = when {
                    element.isJsonPrimitive -> listOf(element.asString)
                    element.isJsonArray -> element.asJsonArray.mapNotNull {
                        try { it.asString } catch (_: Exception) { null }
                    }
                    else -> emptyList()
                }
                if (specs.isEmpty()) return@forEachJson
                val name = file.fileName.toString().removeSuffix(".json")
                index[normalizeId(name)] = specs
            }
        } catch (_: Exception) {}
        return index
    }

    private fun specsFor(habitatId: String): List<String> {
        val index = biomeSpecIndex ?: buildBiomeSpecIndex().also { biomeSpecIndex = it }
        for (key in HabitatStructures.fileKeysFor(habitatId)) {
            index[key]?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }

    /** The raw biome condition, e.g. "#cobblemon:is_cherry_blossom". */
    fun biomeTagsFor(habitatId: String): List<String> = specsFor(habitatId)

    /**
     * Concrete biomes a habitat can generate in. Empty for the 17 habitats Cobblemon
     * places as worldgen features instead of structures — their biomes are attached in
     * code, so no data file states them.
     */
    fun biomesFor(habitatId: String): List<String> {
        val specs = specsFor(habitatId)
        if (specs.isEmpty()) return emptyList()
        val out = linkedSetOf<String>()
        for (spec in specs) {
            if (spec.startsWith("#")) {
                out.addAll(BiomeTagResolver.resolve(spec.removePrefix("#")))
            } else {
                val parts = spec.split(":", limit = 2)
                out.add(
                    if (parts.size == 2) Translations.biomeName(parts[0], parts[1])
                    else Translations.biomeName("minecraft", spec)
                )
            }
        }
        return out.sorted()
    }

    /**
     * The habitat's biome condition as [BiomeDetail]s, so the wiki can render them with
     * the same hover behaviour as spawn conditions — hovering a tag expands it into the
     * biomes it really covers, grouped by mod.
     */
    fun biomeDetailsFor(habitatId: String): List<BiomeDetail> = specsFor(habitatId).map { spec ->
        if (spec.startsWith("#")) {
            val tagId = spec.removePrefix("#")
            BiomeDetail(displayName = formatTagPath(tagId.substringAfter(":")), tagId = tagId)
        } else {
            val path = spec.substringAfter(":")
            BiomeDetail(displayName = prettify(path), tagId = null, biomeId = spec)
        }
    }

    /** "is_cherry_blossom" -> "Cherry Blossom", "nether/is_wasteland" -> "Nether Wasteland" */
    private fun formatTagPath(path: String): String = path
        .replace("is_", "")
        .replace("/", " ")
        .replace("_", " ")
        .trim()
        .split(" ")
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Human-readable label for the raw condition when the tag cannot be expanded. */
    fun biomeTagLabel(habitatId: String): String? {
        val specs = specsFor(habitatId)
        if (specs.isEmpty()) return null
        return specs.joinToString(", ") { spec ->
            spec.removePrefix("#").substringAfter(":")
                .removePrefix("is_").replace("/", " ").replace("_", " ")
                .split(" ").filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    /** Every habitat we know about, sorted by display name. */
    fun allHabitats(): List<HabitatSummary> {
        ensureLoaded()
        val byId = mutableMapOf<String, MutableList<Pair<String, HabitatEntry>>>()
        for ((speciesKey, entries) in habitatMap) {
            for (entry in entries) {
                byId.getOrPut(entry.habitatId) { mutableListOf() }.add(speciesKey to entry)
            }
        }
        return byId.map { (id, pairs) ->
            val sample = pairs.first().second
            HabitatSummary(
                habitatId = id,
                displayName = habitatDisplayName(sample),
                speciesCount = pairs.map { it.first }.distinct().size,
                // Cobblemon's getPhaseCount() counts DISTINCT phases, not the highest
                // number; they coincide for every shipped pool but not necessarily
                // for a datapack with gaps.
                phaseCount = pairs.flatMap { it.second.phases }.distinct().size,
                entries = pairs.map { it.second },
                speciesKeys = pairs.map { it.first }.distinct()
            )
        }.sortedBy { it.displayName }
    }

    /**
     * Work out which pool a placed habitat block uses.
     *
     * The block only syncs its DisplaySpecies list, so we match that against the known
     * pools. Requiring a strong overlap keeps two similar pools from being confused.
     */
    fun identifyPool(speciesIds: List<String>): HabitatSummary? {
        if (speciesIds.isEmpty()) return null
        val target = speciesIds.map { normalizeKey(it.substringAfter(":")) }.toSet()
        if (target.isEmpty()) return null

        var best: HabitatSummary? = null
        var bestScore = 0
        for (habitat in allHabitats()) {
            val keys = habitat.speciesKeys.toSet()
            if (keys.isEmpty()) continue
            val overlap = keys.count { it in target }
            if (overlap == 0) continue
            val score = overlap * 100 / maxOf(keys.size, target.size)
            if (score > bestScore) {
                bestScore = score
                best = habitat
            }
        }
        return if (bestScore >= 60) best else null
    }

    /** Every species that can come out of one habitat, with its entry. */
    fun speciesIn(habitatId: String): List<Pair<String, HabitatEntry>> {
        ensureLoaded()
        val out = mutableListOf<Pair<String, HabitatEntry>>()
        for ((speciesKey, entries) in habitatMap) {
            for (entry in entries) if (entry.habitatId == habitatId) out.add(speciesKey to entry)
        }
        return out.sortedWith(compareBy({ it.second.phases.firstOrNull() ?: 0 }, { -it.second.weight }))
    }

    /** Localised habitat name from its id alone (for tooltips built outside a row). */
    fun displayNameOf(habitatId: String): String {
        val entry = habitatMap.values.asSequence().flatten().firstOrNull { it.habitatId == habitatId }
        return if (entry != null) habitatDisplayName(entry) else prettify(habitatId.substringAfter(":"))
    }

    /** Localised habitat name; the JSON stores a translation key. */
    fun habitatDisplayName(entry: HabitatEntry): String {
        val raw = entry.habitatName
        if (raw.startsWith("cobblemon.")) {
            try {
                val translated = Text.translatable(raw).string
                if (translated != raw) return translated
            } catch (_: Exception) {}
            // Key without a translation: prettify the middle segment
            val slug = raw.removePrefix("cobblemon.habitat.").removeSuffix(".name")
            return prettify(slug)
        }
        return if (raw.contains(":")) prettify(raw.substringAfter(":")) else raw
    }

    private fun prettify(slug: String): String = slug.replace("_", " ").split(" ")
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /**
     * Ready-to-draw habitat lines for a species, collapsed by habitat so a pool that
     * lists several phases of the same Pokémon shows up once.
     */
    private val lineCache = HashMap<String, List<SpawnLine>>()
    private var lineCacheLang = ""

    fun habitatLines(pokemonName: String): List<SpawnLine> {
        val lang = Translations.effectiveNameLanguage() + "/" + Translations.isFrench()
        if (lang != lineCacheLang) {
            lineCache.clear()
            lineCacheLang = lang
        }
        val cacheKey = normalizeKey(pokemonName)
        lineCache[cacheKey]?.let { return it }
        val built = buildHabitatLines(pokemonName)
        lineCache[cacheKey] = built
        return built
    }

    private fun buildHabitatLines(pokemonName: String): List<SpawnLine> {
        val entries = getHabitats(pokemonName)
        if (entries.isEmpty()) return emptyList()

        val lines = mutableListOf<SpawnLine>()
        lines.add(SpawnLine("  ⚘ ${Translations.tr("Habitats:")}", 0xFF77CC99.toInt()))

        for (entry in entries.sortedBy { habitatDisplayName(it) }.take(12)) {
            val parts = mutableListOf<String>()
            parts.add(habitatDisplayName(entry))
            parts.add("Lv.${entry.lvMin}-${entry.lvMax}")
            parts.add(Translations.formatRarity(entry.bucket))
            if (entry.phases.isNotEmpty()) {
                val phaseLabel = Translations.tr("Phase")
                parts.add(if (entry.phases.size == 1) "$phaseLabel ${entry.phases.first()}"
                          else "$phaseLabel ${entry.phases.first()}-${entry.phases.last()}")
            }
            if (entry.time != "any") parts.add(Translations.formatTime(entry.time))
            lines.add(SpawnLine("    • ${parts.joinToString(" | ")}", 0xFF88BBAA.toInt(), entry.habitatId))
        }

        if (entries.size > 12) {
            lines.add(SpawnLine("    … +${entries.size - 12}", UiKit.TEXT_FAINT))
        }
        return lines
    }
}
