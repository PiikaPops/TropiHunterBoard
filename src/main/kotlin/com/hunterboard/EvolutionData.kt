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
    val condition: String = "",
    val formAspect: String? = null  // e.g. "galarian" for Galarian Linoone
)

object EvolutionData {

    private val cache = mutableMapOf<String, EvoNode?>()

    // Fallback data from JSON (for servers)
    private var jsonEvolutionTargets: Map<String, List<EvoTarget>> = emptyMap()  // species -> list of evolution targets with conditions
    private var jsonPreEvolutions: Map<String, String> = emptyMap()      // species -> pre-evolution name
    private var jsonLoaded = false

    fun getEvolutionTree(species: Species, formName: String? = null): EvoNode? {
        val key = if (formName != null) "${species.name.lowercase()}_$formName" else species.name.lowercase()
        if (cache.containsKey(key)) return cache[key]

        val result: EvoNode?
        if (formName != null) {
            // Form-specific tree: start from species root with this form
            val formTargets = getEvolutionTargets(species, formName)
            result = if (formTargets.isNotEmpty()) {
                buildTree(species, species.name.lowercase(), mutableSetOf(), "", formName)
            } else {
                // No form-specific evolutions — fall back to standard tree
                val base = findBaseSpecies(species)
                val tree = buildTree(base, species.name.lowercase(), mutableSetOf())
                if (tree.children.isNotEmpty() || (tree.isCurrent && hasPreEvolution(species))) tree else null
            }
        } else {
            val base = findBaseSpecies(species)
            val tree = buildTree(base, species.name.lowercase(), mutableSetOf())
            result = if (tree.children.isNotEmpty() || (tree.isCurrent && hasPreEvolution(species))) tree else null
        }

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

    // Maps adjective aspects ("galarian") to their form name in species JSON ("galar")
    private val ASPECT_TO_FORM = mapOf(
        "galarian" to "galar",
        "alolan"   to "alola",
        "hisuian"  to "hisui",
        "paldean"  to "paldea"
    )

    /** Convert a Cobblemon aspect ("galarian") to the matching form name ("galar"). */
    fun aspectToFormName(aspect: String): String = ASPECT_TO_FORM[aspect] ?: aspect

    data class EvoTarget(val name: String, val condition: String, val formAspect: String? = null)

    private fun getEvolutionTargets(species: Species, formName: String? = null): List<EvoTarget> {
        ensureJsonLoaded()

        // Try form-specific JSON key first (e.g., "zigzagoon_galar")
        if (formName != null) {
            val formKey = "${species.name.lowercase()}_$formName"
            val formTargets = jsonEvolutionTargets[formKey]
            if (!formTargets.isNullOrEmpty()) return formTargets
        }

        // Standard JSON key
        val jsonTargets = jsonEvolutionTargets[species.name.lowercase()]
        if (jsonTargets != null && jsonTargets.isNotEmpty()) return jsonTargets

        // Fallback: Cobblemon API
        try {
            val formData = if (formName != null)
                species.forms.find { it.name.lowercase() == formName }
            else null
            val evos = formData?.evolutions?.takeIf { it.isNotEmpty() } ?: species.evolutions
            if (evos.isNotEmpty()) {
                return evos.mapNotNull { evo ->
                    try {
                        val speciesName = evo.result.species?.lowercase() ?: return@mapNotNull null
                        EvoTarget(speciesName, "")
                    } catch (_: Exception) { null }
                }.distinctBy { it.name }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    private fun buildTree(
        species: Species, highlightName: String, visited: MutableSet<String>,
        condition: String = "", formName: String? = null, formAspect: String? = null
    ): EvoNode {
        val name = species.name.lowercase()
        val visitKey = if (formName != null) "$name:$formName" else name
        visited.add(visitKey)

        val targets = getEvolutionTargets(species, formName)
        val children = targets.mapNotNull { target ->
            val targetFormName = target.formAspect?.let { (ASPECT_TO_FORM[it] ?: it).lowercase() }
            val childVisitKey = if (targetFormName != null) "${target.name}:$targetFormName" else target.name
            if (childVisitKey in visited) return@mapNotNull null
            val childSpecies = PokemonSpecies.getByName(target.name) ?: return@mapNotNull null
            buildTree(childSpecies, highlightName, visited, target.condition, targetFormName, target.formAspect)
        }

        return EvoNode(
            speciesName = name,
            species = species,
            children = children,
            isCurrent = name == highlightName,
            condition = condition,
            formAspect = formAspect
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

        // Standard form evolutions
        val evoArray = root.getAsJsonArray("evolutions")
        if (evoArray != null) {
            val targets = mutableListOf<EvoTarget>()
            for (evoEl in evoArray) {
                val evoObj = evoEl.asJsonObject
                val result = evoObj.get("result")?.asString ?: continue
                val parts = result.split(" ")
                val resultSpecies = parts.first().lowercase()
                if (resultSpecies.isEmpty()) continue
                val condition = parseCondition(evoObj)
                targets.add(EvoTarget(resultSpecies, condition, resultAspect(parts)))
                preEvos[resultSpecies] = name
            }
            if (targets.isNotEmpty()) evos[name] = mergeAlternatives(targets).toMutableList()
        }

        // Form-specific evolutions (e.g., Galar form of Zigzagoon → Galarian Linoone)
        val forms = root.getAsJsonArray("forms") ?: return
        for (formEl in forms) {
            val formObj = formEl.asJsonObject
            val formName = formObj.get("name")?.asString?.lowercase() ?: continue
            val formEvoArray = formObj.getAsJsonArray("evolutions") ?: continue
            val formKey = "${name}_$formName"
            val formTargets = mutableListOf<EvoTarget>()
            for (evoEl in formEvoArray) {
                val evoObj = evoEl.asJsonObject
                val result = evoObj.get("result")?.asString ?: continue
                val parts = result.split(" ")
                val resultSpecies = parts.first().lowercase()
                if (resultSpecies.isEmpty()) continue
                val condition = parseCondition(evoObj)
                formTargets.add(EvoTarget(resultSpecies, condition, resultAspect(parts)))
                preEvos[resultSpecies] = name
            }
            if (formTargets.isNotEmpty()) evos[formKey] = mergeAlternatives(formTargets).toMutableList()
        }
    }

    /**
     * The form an evolution leads to, from its result ("raichu alolan",
     * "lycanroc wolf_form=midnight", "darmanitan blazing_mode=standard galarian"): a plain
     * aspect, or the value of a "feature=value" when the species has a form of that name.
     * Anything else is cosmetic (Gholdengo's netherite coating) and names no form.
     */
    private fun resultAspect(words: List<String>): String? {
        val extras = words.drop(1).map { it.lowercase() }.filter { it.isNotBlank() }
        extras.firstOrNull { '=' !in it }?.let { return it }
        val species = try { PokemonSpecies.getByName(words.first().lowercase()) } catch (_: Exception) { null } ?: return null
        fun flat(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        for (value in extras.map { it.substringAfter('=') }) {
            species.forms.firstOrNull { flat(it.name) == flat(value) }?.let { return it.name }
        }
        return null
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
                try { requirementPart(reqEl.asJsonObject)?.let { parts.add(it) } } catch (_: Exception) {}
            }
        }

        return parts.joinToString("+")
    }

    /** One requirement as a condition token ("Lv. 16", "biome:#tag", "not:…"); null = not worth showing. */
    private fun requirementPart(req: com.google.gson.JsonObject): String? {
        val parts = mutableListOf<String>()
        val variant = req.get("variant")?.asString ?: return null
        when (variant) {
            // Cobblemon's NOT: the requirement it wraps must fail ("anywhere but these biomes")
            "negate" -> {
                val inner = req.getAsJsonObject("inner") ?: return null
                return requirementPart(inner)?.let { "not:$it" }
            }
            "level" -> {
                val lvl = req.get("minLevel")?.asInt
                if (lvl != null && lvl > 1) parts.add("Lv. $lvl")   // "level 0" only means "on level-up"
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
            // "anywhere but these" is an anticondition here before Cobblemon 1.8.4, a negate since
            "biome" -> {
                val wanted = req.get("biomeCondition")?.asString
                val avoided = req.get("biomeAnticondition")?.asString
                parts.add(if (wanted == null && avoided != null) "not:biome:$avoided" else "biome:${wanted ?: ""}")
            }
            "move_learned", "has_move" -> {
                val move = req.get("move")?.asString ?: ""
                parts.add("move:$move")
            }
            "has_move_type" -> {
                val type = req.get("type")?.asString ?: ""
                parts.add("move_type:$type")
            }
            "weather" -> {
                val weather = req.get("weather")?.asString ?: when {
                    req.get("isThundering")?.asBoolean == true -> "thunder"
                    req.get("isRaining")?.asBoolean == true -> "rain"
                    req.get("isRaining")?.asBoolean == false -> "clear"
                    else -> return null
                }
                parts.add("weather:$weather")
            }
            "moon_phase" -> {
                val raw = req.get("moonPhase") ?: return null
                val phase = try { raw.asInt } catch (_: Exception) { MOON_PHASES.indexOf(raw.asString.uppercase()) }
                if (phase >= 0) parts.add("moon:$phase")
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
            "has_pokemon_in_party", "party_member" -> {
                val pokemon = req.get("target")?.asString ?: ""
                parts.add("party_pokemon:$pokemon")
            }
            "structure" -> {
                val struct = req.get("structureCondition")?.asString
                    ?: req.get("structureAnticondition")?.asString ?: ""
                val isAnti = req.has("structureAnticondition")
                parts.add("structure:${if (isAnti) "!" else ""}$struct")
            }
            "blocks_traveled" -> {
                val amount = req.get("amount")?.asInt
                if (amount != null) parts.add("walked:$amount")
            }
            "stat_compare" -> {
                val high = req.get("highStat")?.asString
                val low = req.get("lowStat")?.asString
                if (high != null && low != null) parts.add("stat_compare:$high:greater_than:$low")
                else {
                    val statA = req.get("stat1")?.asString ?: req.get("statA")?.asString ?: return null
                    val statB = req.get("stat2")?.asString ?: req.get("statB")?.asString ?: return null
                    val comp = req.get("comparison")?.asString ?: ""
                    parts.add("stat_compare:$statA:$comp:$statB")
                }
            }
            "stat_equal" -> {
                val statA = (req.get("statOne") ?: req.get("stat1") ?: req.get("statA"))?.asString ?: return null
                val statB = (req.get("statTwo") ?: req.get("stat2") ?: req.get("statB"))?.asString ?: return null
                parts.add("stat_equal:$statA:$statB")
            }
            "battle_critical_hits" -> req.get("amount")?.asInt?.let { parts.add("crits:$it") }
            "advancement" -> req.get("requiredAdvancement")?.asString?.let { parts.add("advancement:$it") }
            "recoil_damage", "recoil" -> {
                val damage = (req.get("damage") ?: req.get("amount"))?.asInt
                if (damage != null) parts.add("recoil:$damage")
            }
            "damage_taken" -> {
                val damage = (req.get("damage") ?: req.get("amount"))?.asInt
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
                parts.add("defeat:${count ?: ""}:$target")   // the target last: it may hold ":"
            }
            "walked_steps" -> {
                val steps = req.get("amount")?.asInt
                if (steps != null) parts.add("steps:$steps")
            }
            // "gender=male", "nature=adamant", "nickname=Grumm"… the ones a player can act on
            "properties" -> {
                val target = req.get("target")?.asString ?: return null
                val tokens = target.split(' ').filter { '=' in it }.mapNotNull { pair ->
                    val (key, value) = pair.split('=', limit = 2)
                    when (key.lowercase()) {
                        "gender" -> "gender:${value.lowercase()}"
                        "nature" -> "nature:${value.lowercase()}"
                        "nickname" -> "nickname:$value"
                        "gimmighoul_coins" -> "coins:$value"
                        "cocoon_species" -> "individual"   // drawn when the Pokémon appears
                        else -> null
                    }
                }
                return tokens.joinToString("+").ifEmpty { null }
            }
            "property_range" -> {} // internal, skip
            else -> parts.add(variant)
        }
        return parts.firstOrNull()
    }

    /** Minecraft's order of the moon phases. */
    private val MOON_PHASES = listOf("FULL_MOON", "WANING_GIBBOUS", "THIRD_QUARTER", "WANING_CRESCENT",
        "NEW_MOON", "WAXING_CRESCENT", "FIRST_QUARTER", "WAXING_GIBBOUS")

    /**
     * Several ways to the same Pokémon that differ by one value only — Toxel's thirteen
     * natures, Inkay's two nicknames — become one, the values joined: the tree shows one
     * branch per Pokémon and would otherwise keep the first way and drop the rest.
     */
    private fun mergeAlternatives(targets: List<EvoTarget>): List<EvoTarget> {
        val out = mutableListOf<EvoTarget>()
        for (t in targets) {
            val i = out.indexOfFirst { it.name == t.name && it.formAspect == t.formAspect }
            if (i < 0) { out += t; continue }
            val a = out[i].condition.split("+")
            val b = t.condition.split("+")
            val onlyA = a - b.toSet()
            val onlyB = b - a.toSet()
            val key = onlyA.singleOrNull()?.substringBefore(':')
            if (key != null && ':' in onlyA.single() && onlyB.singleOrNull()?.substringBefore(':') == key) {
                val merged = onlyA.single() + "/" + onlyB.single().substringAfter(':')
                out[i] = out[i].copy(condition = a.joinToString("+") { if (it == onlyA.single()) merged else it })
            } else if (onlyA.isNotEmpty() || onlyB.isNotEmpty()) {
                out += t   // a really different way: kept, though the tree shows the first one
            }
        }
        return out
    }

    /** An advancement's title as the game shows it, read from the mod that defines it. */
    fun advancementTitle(id: String): String? {
        val ns = id.substringBefore(':', "minecraft")
        val path = id.substringAfter(':')
        return try {
            val mod = FabricLoader.getInstance().getModContainer(ns).orElse(null) ?: return null
            val file = mod.findPath("data/$ns/advancement/$path.json").orElse(null) ?: return null
            val title = JsonParser.parseString(Files.readString(file)).asJsonObject
                .getAsJsonObject("display")?.getAsJsonObject("title") ?: return null
            // Cobblemon's French file has some titles under "catch_" where the advancement says "collect_"
            title.get("translate")?.asString?.let { key ->
                val lang = net.minecraft.util.Language.getInstance()
                val found = listOf(key.replace(".collect_", ".catch_"), key).firstOrNull { lang.hasTranslation(it) } ?: key
                net.minecraft.text.Text.translatable(found).string
            } ?: title.get("text")?.asString
        } catch (_: Exception) { null }
    }

    private val tagValues = mutableMapOf<String, List<String>>()

    /**
     * What a biome tag lists, as written in the mod that defines it ("#cobblemon:is_forest",
     * "minecraft:beach"). Evolution tags such as `evolution/regional/pikachu_kantobiome` only
     * gather other tags, whose names say more than theirs.
     */
    fun biomeTagValues(tagId: String): List<String> = tagValues.getOrPut(tagId) {
        val id = tagId.removePrefix("#")
        val ns = id.substringBefore(':', "minecraft")
        val path = id.substringAfter(':')
        try {
            val mods = FabricLoader.getInstance().getModContainer(ns).map { listOf(it) }
                .orElse(FabricLoader.getInstance().allMods.toList())
            for (mod in mods) {
                val file = mod.findPath("data/$ns/tags/worldgen/biome/$path.json").orElse(null) ?: continue
                val values = JsonParser.parseString(Files.readString(file)).asJsonObject.getAsJsonArray("values")
                return@getOrPut values.mapNotNull { v ->
                    if (v.isJsonObject) v.asJsonObject.get("id")?.asString else v.asString
                }
            }
        } catch (_: Exception) {}
        emptyList()
    }
}
