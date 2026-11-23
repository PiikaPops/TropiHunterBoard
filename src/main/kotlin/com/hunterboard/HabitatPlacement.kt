package com.hunterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files

/** How the world places one habitat, as far as the data and the code can say. */
data class HabitatPlacementInfo(
    val kind: Kind,
    /** "#cobblemon:is_lush", "minecraft:is_nether"… as written in the data. */
    val biomeSpecs: List<String>,
    /** overworld / nether / end, worked out from the biome condition. */
    val dimension: String?,
    val step: String?,
    // ---- jigsaw structures ----
    val spacing: Int? = null,
    val separation: Int? = null,
    val spreadType: String? = null,
    val setWeight: Int? = null,
    val setTotalWeight: Int? = null,
    val terrainAdaptation: String? = null,
    val startHeight: String? = null,
    val heightmap: String? = null,
    // ---- worldgen features ----
    val chance: Int? = null,
    val count: Int? = null,
    val heightRange: String? = null,
    val scan: String? = null,
    val verticalOffset: Int? = null,
    // ---- pieces injected into someone else's structure ----
    /** e.g. "village/plains/houses" — the template pools this habitat was added to. */
    val hostPools: List<String> = emptyList(),
    val hostWeight: Int? = null,
    val hostTotalWeight: Int? = null
) {
    enum class Kind { STRUCTURE, FEATURE, INJECTED }
}

/**
 * Where habitats come from in world generation.
 *
 * Cobblemon places them two different ways, and the wiki could previously say nothing
 * about either. Thirty-two are **jigsaw structures** listed in one structure set, spread
 * on a grid; seventeen are **worldgen features** rolled per chunk against a rarity filter.
 * Everything here is read from the data files at runtime, so a datapack that retunes a
 * spacing or a rarity is followed — with one exception, [FEATURE_BIOMES].
 */
object HabitatPlacement {

    /**
     * Biome tag and generation step for the feature-placed habitats.
     *
     * These seventeen are the only ones whose biome is **not** in any data file: Fabric
     * attaches a feature to biomes in code, through `BiomeModifications.addFeature`, and
     * Cobblemon calls it from `CobblemonPlacedFeatures.register()`. Nothing retains the
     * mapping afterwards and the client's synced biomes have their generation settings
     * stripped, so there is nothing to read at runtime — the table was extracted from the
     * bytecode of Cobblemon 1.8.1 (`javap -c` on that method, resolving the tag fields
     * against `CobblemonBiomeTags` and vanilla `BiomeTags`). Re-extract it if Cobblemon
     * changes where these generate.
     */
    private val FEATURE_BIOMES: Map<String, Pair<String, String>> = mapOf(
        //  feature file name           biome tag                         generation step
        "treasure_hoard" to ("minecraft:is_overworld" to "LOCAL_MODIFICATIONS"),
        "abandoned_fortress" to ("minecraft:is_nether" to "UNDERGROUND_DECORATION"),
        "ancient_wellsprings" to ("cobblemon:is_lush" to "LOCAL_MODIFICATIONS"),
        "chorus_briars" to ("cobblemon:is_end" to "LOCAL_MODIFICATIONS"),
        "deep_roots" to ("cobblemon:is_lush" to "LOCAL_MODIFICATIONS"),
        "earthen_hives" to ("cobblemon:is_dripstone" to "LOCAL_MODIFICATIONS"),
        "exposed_geodes" to ("minecraft:is_overworld" to "LOCAL_MODIFICATIONS"),
        "lost_ruins" to ("minecraft:is_overworld" to "UNDERGROUND_DECORATION"),
        "quartz_spikes" to ("cobblemon:nether/is_wasteland" to "LOCAL_MODIFICATIONS"),
        "rocky_tidepools" to ("cobblemon:is_stony_beach" to "LOCAL_MODIFICATIONS"),
        "root_nurseries" to ("cobblemon:is_swamp" to "LOCAL_MODIFICATIONS"),
        "sandy_tidepools" to ("cobblemon:is_beach" to "LOCAL_MODIFICATIONS"),
        "skeletal_fall" to ("cobblemon:nether/is_soul_sand" to "LOCAL_MODIFICATIONS"),
        "strange_crimson_fungal_dwellings" to ("cobblemon:nether/is_wasteland" to "LOCAL_MODIFICATIONS"),
        "strange_warped_fungal_dwellings" to ("cobblemon:nether/is_wasteland" to "LOCAL_MODIFICATIONS"),
        "volcanic_plumes" to ("minecraft:is_nether" to "LOCAL_MODIFICATIONS"),
        "rocky_outcrops" to ("cobblemon:is_mountain" to "UNDERGROUND_DECORATION")
    )

    /**
     * Pools whose normalised name does not reach their feature file.
     *
     * Kept apart from `HabitatStructures.ALIASES`, which is tuned for the .nbt preview
     * pieces and verified against them; the worldgen file names differ again.
     */
    private val PLACEMENT_ALIASES: Map<String, List<String>> = mapOf(
        "rootnursery" to listOf("rootnurserie"),
        "crimsonfungaldwelling" to listOf("strangecrimsonfungaldwelling"),
        "warpedfungaldwelling" to listOf("strangewarpedfungaldwelling"),
        // the "strange" pool is shared by both nether variants; they place identically
        "strangefungaldwelling" to listOf("strangecrimsonfungaldwelling", "strangewarpedfungaldwelling")
    )

    private class Index(
        val structures: Map<String, JsonObject>,
        val features: Map<String, JsonObject>,
        /** structure file name -> its weight inside the set */
        val setWeights: Map<String, Int>,
        val setTotalWeight: Int,
        val setPlacement: JsonObject?
    )

    /** One template pool that hosts pieces belonging to some habitat. */
    private class HostPool(val id: String, val total: Int, val weights: Map<String, Int>)

    private var index: Index? = null
    private val cache = HashMap<String, HabitatPlacementInfo?>()

    fun invalidate() {
        index = null
        cache.clear()
    }

    /** Same normalisation as the structure matching, so the existing aliases still apply. */
    private fun normalize(id: String): String = id.substringAfter(":")
        .substringAfterLast("/")
        .trimEnd { it.isDigit() }
        .replace("_", "")
        .removeSuffix("s")

    private fun keysFor(habitatId: String): List<String> {
        val key = normalize(habitatId)
        return (listOf(key) + HabitatStructures.fileKeysFor(habitatId) + (PLACEMENT_ALIASES[key] ?: emptyList()))
            .distinct()
    }

    private fun buildIndex(): Index {
        val structures = mutableMapOf<String, JsonObject>()
        val features = mutableMapOf<String, JsonObject>()
        val setWeights = mutableMapOf<String, Int>()
        var setPlacement: JsonObject? = null

        try {
            CobblemonResources.forEachJson("worldgen/structure") { file ->
                if (!file.toString().replace('\\', '/').contains("/habitats/")) return@forEachJson
                val name = file.fileName.toString().removeSuffix(".json")
                structures[normalize(name)] = JsonParser.parseString(Files.readString(file)).asJsonObject
            }
            CobblemonResources.forEachJson("worldgen/placed_feature") { file ->
                if (!file.toString().replace('\\', '/').contains("/habitats/")) return@forEachJson
                val name = file.fileName.toString().removeSuffix(".json")
                features[normalize(name)] = JsonParser.parseString(Files.readString(file)).asJsonObject
            }
            CobblemonResources.forEachJson("worldgen/structure_set") { file ->
                if (!file.toString().replace('\\', '/').contains("/habitats/")) return@forEachJson
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                setPlacement = root.getAsJsonObject("placement") ?: setPlacement
                root.getAsJsonArray("structures")?.forEach { element ->
                    try {
                        val entry = element.asJsonObject
                        val id = entry.get("structure")?.asString ?: return@forEach
                        val weight = entry.get("weight")?.asInt ?: 1
                        setWeights[normalize(id)] = weight
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Could not index habitat placement: ${e.message}")
        }

        return Index(structures, features, setWeights, setWeights.values.sum(), setPlacement)
    }

    fun forHabitat(habitatId: String): HabitatPlacementInfo? {
        cache[habitatId]?.let { return it }
        if (cache.containsKey(habitatId)) return null
        val result = compute(habitatId)
        cache[habitatId] = result
        return result
    }

    private fun compute(habitatId: String): HabitatPlacementInfo? {
        val idx = index ?: buildIndex().also { index = it }
        val keys = keysFor(habitatId)

        // a structure, if one carries this name
        for (key in keys) {
            val structure = idx.structures[key] ?: continue
            val specs = biomeSpecsOf(structure)
            val weight = idx.setWeights[key]
            return HabitatPlacementInfo(
                kind = HabitatPlacementInfo.Kind.STRUCTURE,
                biomeSpecs = specs,
                dimension = dimensionOf(specs),
                step = structure.get("step")?.asString?.uppercase(),
                spacing = idx.setPlacement?.get("spacing")?.asInt,
                separation = idx.setPlacement?.get("separation")?.asInt,
                spreadType = idx.setPlacement?.get("spread_type")?.asString,
                setWeight = weight,
                setTotalWeight = if (weight != null) idx.setTotalWeight else null,
                terrainAdaptation = structure.get("terrain_adaptation")?.asString,
                startHeight = heightLabel(structure.get("start_height")),
                heightmap = structure.get("project_start_to_heightmap")?.asString
            )
        }

        // otherwise a feature
        for (key in keys) {
            val feature = idx.features[key] ?: continue
            val fileName = FEATURE_BIOMES.keys.firstOrNull { normalize(it) == key }
            val coded = fileName?.let { FEATURE_BIOMES[it] }
            val specs = coded?.first?.let { listOf("#$it") } ?: emptyList()
            val placement = feature.getAsJsonArray("placement")

            var chance: Int? = null
            var count: Int? = null
            var heightRange: String? = null
            var scan: String? = null
            var offset: Int? = null

            placement?.forEach { element ->
                try {
                    val modifier = element.asJsonObject
                    when (modifier.get("type")?.asString?.substringAfter(":")) {
                        "rarity_filter" -> chance = modifier.get("chance")?.asInt
                        "count" -> count = modifier.get("count")?.asInt
                        "height_range" -> heightRange = heightRangeLabel(modifier.getAsJsonObject("height"))
                        "environment_scan" -> scan = scanLabel(modifier)
                        "random_offset" -> offset = modifier.get("y_spread")?.asInt
                    }
                } catch (_: Exception) {}
            }

            return HabitatPlacementInfo(
                kind = HabitatPlacementInfo.Kind.FEATURE,
                biomeSpecs = specs,
                dimension = dimensionOf(specs),
                step = coded?.second,
                chance = chance,
                count = count,
                heightRange = heightRange,
                scan = scan,
                verticalOffset = offset
            )
        }

        // Finally: pieces Cobblemon injects into someone else's structure. The abandoned
        // village house is not placed on its own at all — Cobblemon overrides vanilla's
        // village/<variant>/houses template pools and adds its pieces to them, so the
        // habitat is found by looking for its pieces inside other pools.
        val hosts = hostPoolIndex ?: buildHostPools().also { hostPoolIndex = it }
        val matched = hosts.filter { host -> keys.any { it in host.weights } }
        if (matched.isNotEmpty()) {
            val weight = matched.sumOf { host -> keys.sumOf { host.weights[it] ?: 0 } }
            return HabitatPlacementInfo(
                kind = HabitatPlacementInfo.Kind.INJECTED,
                biomeSpecs = emptyList(),
                dimension = "overworld",
                step = null,
                hostPools = matched.map { it.id }.sorted(),
                hostWeight = weight,
                hostTotalWeight = matched.sumOf { it.total }
            )
        }

        return null
    }

    /**
     * Template pools that contain habitat pieces, with the weights involved.
     *
     * Built lazily: only one habitat ships this way, so walking every template pool is
     * not worth doing until a habitat actually misses both other mechanisms.
     */
    private var hostPoolIndex: List<HostPool>? = null

    private fun buildHostPools(): List<HostPool> {
        val out = mutableListOf<HostPool>()
        try {
            CobblemonResources.forEachJson("worldgen/template_pool") { file ->
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                val elements = root.getAsJsonArray("elements") ?: return@forEachJson
                var total = 0
                val weights = mutableMapOf<String, Int>()
                for (element in elements) {
                    try {
                        val entry = element.asJsonObject
                        val weight = entry.get("weight")?.asInt ?: 1
                        total += weight
                        val location = entry.getAsJsonObject("element")?.get("location")?.asString
                            ?: continue
                        // only pieces living under a habitats/ path belong to a habitat;
                        // this is what keeps the Poké Center entries out of the count
                        if (!location.contains("habitats/")) continue
                        val key = normalize(location)
                        weights[key] = (weights[key] ?: 0) + weight
                    } catch (_: Exception) {}
                }
                if (weights.isNotEmpty()) {
                    val path = file.toString().replace('\\', '/')
                    val id = path.substringAfter("/worldgen/template_pool/").removeSuffix(".json")
                    out.add(HostPool(id, total, weights))
                }
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Could not index host template pools: ${e.message}")
        }
        return out
    }

    // ── reading the shapes the data uses ─────────────────────────────────────────

    /** `biomes` is either a tag string or a list of biome ids. */
    private fun biomeSpecsOf(structure: JsonObject): List<String> {
        val element = structure.get("biomes") ?: return emptyList()
        return try {
            when {
                element.isJsonPrimitive -> listOf(element.asString)
                element.isJsonArray -> element.asJsonArray.mapNotNull {
                    try { it.asString } catch (_: Exception) { null }
                }
                else -> emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Which dimension the biome condition confines a habitat to.
     *
     * The data never names a dimension: a structure set applies everywhere and it is the
     * biome condition that decides. Cobblemon's nether tags live under a `nether/` path
     * and the end ones say so, which is enough to tell the three apart.
     */
    private fun dimensionOf(specs: List<String>): String? {
        if (specs.isEmpty()) return null
        val joined = specs.joinToString(" ").lowercase()
        return when {
            joined.contains("nether") -> "nether"
            joined.contains("is_end") || joined.contains("the_end") || joined.contains("chorus") -> "end"
            else -> "overworld"
        }
    }

    /** `{"absolute": 0}`, `{"above_bottom": 8}`… */
    private fun heightLabel(element: com.google.gson.JsonElement?): String? {
        val obj = try { element?.asJsonObject } catch (_: Exception) { null } ?: return null
        for (key in listOf("absolute", "above_bottom", "below_top")) {
            val value = obj.get(key) ?: continue
            val n = try { value.asInt } catch (_: Exception) { continue }
            return when (key) {
                "absolute" -> "Y $n"
                "above_bottom" -> "Y min +$n"
                else -> "Y max -$n"
            }
        }
        return null
    }

    /** A uniform height provider: same bound twice means a fixed Y. */
    private fun heightRangeLabel(height: JsonObject?): String? {
        if (height == null) return null
        val lo = heightLabel(height.get("min_inclusive"))
        val hi = heightLabel(height.get("max_inclusive"))
        return when {
            lo == null && hi == null -> null
            lo == hi -> lo
            lo == null -> hi
            hi == null -> lo
            else -> "$lo → ${hi.removePrefix("Y ")}"
        }
    }

    /** "up until air, through lava, 7 steps" — compacted for one line. */
    private fun scanLabel(modifier: JsonObject): String? {
        val direction = modifier.get("direction_of_search")?.asString ?: return null
        val target = firstBlock(modifier.getAsJsonObject("target_condition"))
        val allowed = firstBlock(modifier.getAsJsonObject("allowed_search_condition"))
        val steps = modifier.get("max_steps")?.asInt
        return buildString {
            append(if (direction.equals("up", true)) "↑" else "↓")
            if (target != null) append(" ").append(Translations.blockName(target))
            if (allowed != null) append(" / ").append(Translations.blockName(allowed))
            if (steps != null) append(" (").append(steps).append(")")
        }
    }

    private fun firstBlock(condition: JsonObject?): String? = try {
        condition?.getAsJsonArray("blocks")?.firstOrNull()?.asString
    } catch (_: Exception) { null }
}
