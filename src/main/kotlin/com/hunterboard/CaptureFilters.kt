package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.text.Text
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The "Filtre Auto Capture": per-species rules deciding which captures are kept.
 *
 * A filter is a list of groups. The conditions inside a group must all hold (AND), and
 * the filter holds when any group does (OR); each condition can be negated (NOT). That is
 * the whole AND / OR / NOT vocabulary the player asked for, in the one shape that can be
 * drawn without parentheses: boxes joined by "OR", lines inside a box joined by "AND".
 *
 * What a filter means is set by its [CaptureFilter.mode]:
 *  - "keep"    — keep only the Pokémon that match, release the others (the default);
 *  - "release" — release the Pokémon that match, keep the others.
 *
 * Nothing here releases anything; [AutoRelease] asks [verdict] and acts. A species with
 * no filter, a disabled filter, or one without a single condition is never touched.
 */
object CaptureFilters {

    // ── model ──────────────────────────────────────────────────────────────────────

    enum class Kind { NUMBER, BOOL, CHOICE }

    /** What a condition looks at. [id] is what the file stores, so it never changes. */
    enum class Field(val id: String, val kind: Kind, val min: Int = 0, val max: Int = 1,
                     val en: String, val fr: String, val stat: Stat? = null) {
        IV_TOTAL("iv_total", Kind.NUMBER, 0, 100, "Total IVs", "IV total"),
        HP("hp", Kind.NUMBER, 0, 31, "HP IV", "IV PV", Stats.HP),
        ATK("atk", Kind.NUMBER, 0, 31, "Attack IV", "IV Attaque", Stats.ATTACK),
        DEF("def", Kind.NUMBER, 0, 31, "Defense IV", "IV Défense", Stats.DEFENCE),
        SPA("spa", Kind.NUMBER, 0, 31, "Sp. Atk IV", "IV Atq. Spé.", Stats.SPECIAL_ATTACK),
        SPD("spd", Kind.NUMBER, 0, 31, "Sp. Def IV", "IV Déf. Spé.", Stats.SPECIAL_DEFENCE),
        SPE("spe", Kind.NUMBER, 0, 31, "Speed IV", "IV Vitesse", Stats.SPEED),
        SHINY("shiny", Kind.BOOL, en = "Shiny", fr = "Shiny"),
        ABILITY("ability", Kind.CHOICE, en = "Ability", fr = "Talent"),
        HIDDEN_ABILITY("hidden_ability", Kind.BOOL, en = "Hidden ability", fr = "Talent caché"),
        GENDER("gender", Kind.CHOICE, en = "Gender", fr = "Sexe");

        fun label(): String = if (Translations.isFrench()) fr else en

        companion object {
            fun ofOrNull(id: String): Field? = entries.firstOrNull { it.id == id }
            fun of(id: String): Field = ofOrNull(id) ?: IV_TOTAL
            val IVS = listOf(HP, ATK, DEF, SPA, SPD, SPE)
        }
    }

    /** Comparison operators, as stored. Choice and yes/no fields only use "=". */
    val OPS = listOf(">=", ">", "=", "!=", "<", "<=")

    fun opSymbol(op: String): String = when (op) {
        ">=" -> "≥"; "<=" -> "≤"; "!=" -> "≠"; else -> op
    }

    private fun negated(op: String): String = when (op) {
        ">=" -> "<"; ">" -> "<="; "=" -> "!="; "!=" -> "="; "<" -> ">="; "<=" -> ">"; else -> op
    }

    @Serializable
    class Condition(
        var field: String = Field.IV_TOTAL.id,
        var op: String = ">=",
        var value: Int = 0,
        /** The chosen ability or gender, for choice fields. */
        var text: String = "",
        var not: Boolean = false
    ) {
        fun copy() = Condition(field, op, value, text, not)

        /**
         * A line with nothing chosen yet — an ability left blank, or a field this
         * version does not know. It would compare as false, and in a "keep only if" filter
         * a false line releases: so a filter holding one does nothing until it is filled.
         */
        fun isComplete(): Boolean {
            val f = Field.ofOrNull(field) ?: return false
            if (f.kind == Kind.NUMBER && op !in OPS) return false
            return f.kind != Kind.CHOICE || text.isNotBlank()
        }
    }

    @Serializable
    class Group(var conditions: MutableList<Condition> = mutableListOf())

    @Serializable
    class CaptureFilter(
        var id: String = UUID.randomUUID().toString(),
        /** Cobblemon species path, e.g. "pikachu"; the first one when the filter has several. */
        var species: String = "",
        var enabled: Boolean = true,
        var mode: String = MODE_KEEP,
        var groups: MutableList<Group> = mutableListOf(),
        var created: Long = 0,
        /** The other species sharing this filter — typically the rest of an evolution line. */
        var more: MutableList<String> = mutableListOf(),
        /** Applies to every Pokémon ([species] and [more] are then ignored). */
        var all: Boolean = false
    ) {
        fun members(): List<String> = if (all) emptyList() else (listOf(species) + more).filter { it.isNotBlank() }
        fun covers(key: String): Boolean = all || members().any { speciesKey(it) == key }
        /** How narrow the filter is: the fewer Pokémon it names, the higher it ranks. */
        fun breadth(): Int = if (all) Int.MAX_VALUE else members().size

        /** Groups that hold at least one condition; an empty box decides nothing. */
        fun activeGroups(): List<Group> = groups.filter { it.conditions.isNotEmpty() }
        fun conditionCount(): Int = groups.sumOf { it.conditions.size }
        fun hasConditions(): Boolean = activeGroups().isNotEmpty()
        fun isComplete(): Boolean = groups.all { g -> g.conditions.all { it.isComplete() } }
        fun isActive(): Boolean = enabled && hasConditions() && isComplete()
    }

    const val MODE_KEEP = "keep"
    const val MODE_RELEASE = "release"

    @Serializable
    private class FileData(val version: Int = 1, val filters: List<CaptureFilter> = emptyList())

    // ── storage ────────────────────────────────────────────────────────────────────

    private val PATH: Path = FabricLoader.getInstance().configDir.resolve("hunterboard_capture_filters.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val filters = mutableListOf<CaptureFilter>()
    private var loaded = false

    /** Bumped on every change, so screens can tell their cached rows are stale. */
    var revision = 0
        private set

    fun all(): List<CaptureFilter> { ensureLoaded(); return filters }

    /**
     * The filter that decides for this species.
     *
     * Several can cover it — its own, its family's, the one for every Pokémon — and they
     * may disagree. The narrowest wins: one Pokémon over a group, a small group over a
     * larger one, any of them over "all Pokémon"; between two as narrow, the older one.
     * A filter that is switched off is out of the running, so the next one takes over. One
     * that is on but has no condition still wins, and keeps everything: that is how a
     * Pokémon is exempted from a wider filter.
     */
    fun forSpecies(species: String): CaptureFilter? {
        ensureLoaded()
        val key = speciesKey(species)
        return filters.filter { it.enabled && it.covers(key) }
            .minWithOrNull(compareBy<CaptureFilter> { it.breadth() }.thenBy { it.created })
    }

    /** Every enabled filter covering the species, the deciding one first. */
    fun ranked(species: String): List<CaptureFilter> {
        ensureLoaded()
        val key = speciesKey(species)
        return filters.filter { it.enabled && it.covers(key) }
            .sortedWith(compareBy<CaptureFilter> { it.breadth() }.thenBy { it.created })
    }

    /** A filter naming this species itself (not the one for all Pokémon). */
    fun names(species: String): Boolean {
        ensureLoaded()
        val key = speciesKey(species)
        return filters.any { !it.all && it.covers(key) }
    }

    fun global(): CaptureFilter? { ensureLoaded(); return filters.firstOrNull { it.all } }

    /** The filter for every Pokémon; created empty, so it does nothing until a rule is written. */
    fun createGlobal(): CaptureFilter {
        global()?.let { return it }
        val filter = CaptureFilter(all = true, created = System.currentTimeMillis(), groups = mutableListOf(Group()))
        filters.add(filter)
        changed()
        return filter
    }

    fun addSpecies(filter: CaptureFilter, path: String) {
        if (filter.all || filter.covers(speciesKey(path))) return
        if (filter.species.isBlank()) filter.species = path else filter.more.add(path)
        changed()
    }

    fun removeSpecies(filter: CaptureFilter, path: String) {
        val key = speciesKey(path)
        val left = filter.members().filter { speciesKey(it) != key }
        if (left.isEmpty()) return   // a filter keeps at least one Pokémon
        filter.species = left.first()
        filter.more = left.drop(1).toMutableList()
        changed()
    }

    /** The whole evolution line of a species, itself included, in evolution order. */
    fun family(species: Species): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: EvoNode) {
            val path = node.species?.resourceIdentifier?.path ?: return
            if (out.none { speciesKey(it) == speciesKey(path) }) out += path
            node.children.forEach { walk(it) }
        }
        try { EvolutionData.getEvolutionTree(species)?.let { walk(it) } } catch (_: Exception) {}
        if (out.isEmpty()) out += species.resourceIdentifier.path
        return out
    }

    /** "Évoli", "Évoli +8", or "Tous les Pokémon". */
    fun displayName(f: CaptureFilter): String {
        if (f.all) return t("All Pokémon", "Tous les Pokémon")
        val m = f.members()
        val first = speciesName(m.firstOrNull() ?: return "?")
        return if (m.size > 1) "$first +${m.size - 1}" else first
    }

    fun activeCount(): Int = all().count { it.isActive() }

    /** A new filter, pre-filled with the most common rule so the editor never opens empty. */
    fun create(species: Species): CaptureFilter {
        ensureLoaded()
        val key = speciesKey(species.resourceIdentifier.path)
        filters.firstOrNull { !it.all && it.members().size == 1 && it.covers(key) }?.let { return it }
        val filter = CaptureFilter(
            species = species.resourceIdentifier.path,
            created = System.currentTimeMillis(),
            groups = mutableListOf(Group(mutableListOf(Condition(Field.IV_TOTAL.id, ">=", 80))))
        )
        filters.add(filter)
        changed()
        return filter
    }

    fun delete(filter: CaptureFilter) {
        ensureLoaded()
        filters.removeAll { it.id == filter.id }
        changed()
    }

    /** Call after editing a filter in place. */
    fun changed() {
        revision++
        save()
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val file = PATH.toFile()
            if (!file.exists()) return
            val data = json.decodeFromString(FileData.serializer(), file.readText())
            filters.clear()
            for (f in data.filters) if (f.all || f.species.isNotBlank()) filters.add(f)
            // The nature was dropped: the capture card does not show it. Its lines go with it.
            var dropped = 0
            for (f in filters) for (g in f.groups) {
                dropped += g.conditions.count { it.field == "nature" }
                g.conditions.removeAll { it.field == "nature" }
            }
            if (dropped > 0) {
                for (f in filters) f.groups.removeAll { it.conditions.isEmpty() }
                HunterBoard.LOGGER.info("Capture filters: removed $dropped nature condition(s)")
                save()
            }
            HunterBoard.LOGGER.info("Capture filters loaded: ${filters.size} (${filters.count { it.isActive() }} active)")
        } catch (e: Exception) {
            // Same rule as the config: never let the next save overwrite what could not be read.
            HunterBoard.LOGGER.error("Could not read capture filters: ${e.message}")
            try {
                Files.move(PATH, PATH.resolveSibling("${PATH.fileName}.broken"), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {}
        }
    }

    private fun save() {
        try {
            PATH.parent.toFile().mkdirs()
            val tmp = PATH.resolveSibling("${PATH.fileName}.tmp")
            tmp.toFile().writeText(json.encodeToString(FileData.serializer(), FileData(1, filters.toList())))
            try {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Could not save capture filters: ${e.message}")
        }
    }

    // ── sharing ────────────────────────────────────────────────────────────────────

    /*
     * A share code is "HBF1:" followed by the filters in a short text form, deflated and
     * written in base64 (no "_" or "*": Discord's formatting leaves it alone). Species,
     * abilities and genders are Cobblemon ids, so a code means the same in any language.
     * Ids and creation dates are not shared: an imported filter is a new filter.
     *
     * One line per filter, three words:
     *  - flags: "k" keep / "r" release, then "a" for every Pokémon, "o" when switched off;
     *  - the species paths joined by ",", or "-" for every Pokémon;
     *  - the rule: groups joined by "/", conditions by ",", each "[!]field op value";
     *    "-" for a filter without a condition.
     */
    const val CODE_PREFIX = "HBF1:"
    /** What the chat accepts in one message. */
    const val CHAT_LIMIT = 256
    private const val CODE_VERSION = 1
    private const val MAX_SHARED_FILTERS = 2000
    private const val MAX_SHARED_TEXT = 1 shl 20
    private val CODE = Regex("""HBF(\d+):([A-Za-z0-9+/=]+)""")
    private val SHARED_CONDITION = Regex("""^(!?)([a-z0-9_]+)(>=|<=|!=|>|<|=)([^\s,/]+)$""")
    private val SHARED_SPECIES = Regex("""^[a-z0-9_.:-]+$""")

    /** Every filter, the oldest first: imported in that order, they keep their priority. */
    fun allForExport(): List<CaptureFilter> = all().sortedBy { it.created }

    fun exportCode(list: List<CaptureFilter>): String {
        val text = list.joinToString("\n") { f ->
            val flags = (if (f.mode == MODE_RELEASE) "r" else "k") + (if (f.all) "a" else "") + (if (!f.enabled) "o" else "")
            val species = if (f.all) "-" else f.members().joinToString(",").ifEmpty { "-" }
            val rule = f.activeGroups().joinToString("/") { g ->
                g.conditions.joinToString(",") { c ->
                    val choice = Field.ofOrNull(c.field)?.kind == Kind.CHOICE
                    val value = when {
                        choice -> c.text.ifBlank { "?" }
                        Field.ofOrNull(c.field) == null && c.text.isNotBlank() -> c.text
                        else -> c.value.toString()
                    }
                    (if (c.not) "!" else "") + c.field + c.op + value.filter { !it.isWhitespace() && it != ',' && it != '/' }
                }
            }.ifEmpty { "-" }
            "$flags $species $rule"
        }
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return CODE_PREFIX + java.util.Base64.getEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The code inside a pasted text ("voilà mon filtre : HBF1:…"), or null. */
    fun findCode(text: String): String? = CODE.find(text)?.value

    /** What a code holds: the filters, or why it cannot be read. */
    class Decoded(val filters: List<CaptureFilter>, val error: String? = null)

    fun decodeCode(raw: String): Decoded {
        fun fail(en: String, fr: String) = Decoded(emptyList(), t(en, fr))
        val m = CODE.find(raw) ?: return fail("This is not a HunterBoard filter code.", "Ce n'est pas un code de filtre HunterBoard.")
        val version = m.groupValues[1].toIntOrNull() ?: 0
        if (version > CODE_VERSION) return fail("This code comes from a newer HunterBoard: update the mod.",
            "Ce code vient d'une version plus récente de HunterBoard : mettez le mod à jour.")
        val text = try {
            val bytes = java.util.Base64.getDecoder().decode(m.groupValues[2])
            val inflater = java.util.zip.Inflater(true)
            inflater.setInput(bytes + 0.toByte())   // the extra byte raw inflation expects
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_SHARED_TEXT) break
            }
            val complete = inflater.finished()
            inflater.end()
            if (!complete) return fail("This code is cut off: copy it whole.", "Ce code est incomplet : copiez-le en entier.")
            out.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return fail("This code is damaged: copy it again.", "Ce code est abîmé : copiez-le à nouveau.")
        }
        val filters = mutableListOf<CaptureFilter>()
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (filters.size >= MAX_SHARED_FILTERS) break
            filters += parseShared(line)
                ?: return fail("This code is damaged: copy it again.", "Ce code est abîmé : copiez-le à nouveau.")
        }
        if (filters.isEmpty()) return fail("This code holds no filter.", "Ce code ne contient aucun filtre.")
        return Decoded(filters)
    }

    private fun parseShared(line: String): CaptureFilter? {
        val words = line.split(' ').filter { it.isNotEmpty() }
        if (words.size != 3) return null
        val flags = words[0]
        if (flags.isEmpty() || flags[0] !in "kr" || flags.drop(1).any { it !in "ao" }) return null
        val all = 'a' in flags
        val species = if (words[1] == "-") emptyList()
            else words[1].split(',').filter { it.isNotEmpty() }.distinctBy { speciesKey(it) }
        if (species.any { !SHARED_SPECIES.matches(it) }) return null
        if (!all && species.isEmpty()) return null
        val groups = mutableListOf<Group>()
        if (words[2] != "-") for (g in words[2].split('/')) {
            val conditions = g.split(',').filter { it.isNotEmpty() }.map { parseSharedCondition(it) ?: return null }
            if (conditions.isNotEmpty()) groups += Group(conditions.toMutableList())
        }
        if (groups.isEmpty()) groups += Group()
        return CaptureFilter(
            species = if (all) "" else species.first(),
            more = if (all) mutableListOf() else species.drop(1).toMutableList(),
            enabled = 'o' !in flags,
            mode = if (flags[0] == 'r') MODE_RELEASE else MODE_KEEP,
            groups = groups,
            all = all
        )
    }

    private fun parseSharedCondition(s: String): Condition? {
        val m = SHARED_CONDITION.matchEntire(s) ?: return null
        val (not, fieldId, op, value) = m.destructured
        val field = Field.ofOrNull(fieldId)
            // a field from a newer version: kept as it came, the line stays "incomplete"
            ?: return Condition(fieldId, op, value.toIntOrNull() ?: 0, if (value.toIntOrNull() == null) value else "", not == "!")
        return when (field.kind) {
            Kind.NUMBER -> Condition(field.id, op, (value.toIntOrNull() ?: return null).coerceIn(field.min, field.max), "", not == "!")
            Kind.BOOL -> Condition(field.id, "=", (value.toIntOrNull() ?: return null).coerceIn(0, 1), "", not == "!")
            Kind.CHOICE -> Condition(field.id, "=", 0, if (value == "?") "" else value, not == "!")
        }
    }

    /**
     * A chat line holding a filter code, followed by "[Import]": the chat cannot be copied
     * from, so this is how a code posted in game reaches the import window. A code the
     * chat cut short (too long) gets nothing.
     */
    @JvmStatic
    fun withImportLink(message: Text?): Text? {
        if (message == null) return null
        return try {
            val s = message.string
            if (!s.contains("HBF")) return message
            val code = findCode(s) ?: return message
            val decoded = decodeCode(code)
            if (decoded.error != null) return message
            val hover = Text.literal(t("Open in HunterBoard's import window (nothing changes before you confirm)",
                "Ouvrir dans la fenêtre d'import HunterBoard (rien ne change avant de confirmer)"))
                .formatted(net.minecraft.util.Formatting.GRAY)
            for (f in decoded.filters.take(5)) hover.append("\n").append(
                Text.literal(displayName(f)).formatted(net.minecraft.util.Formatting.WHITE)
                    .append(Text.literal(" — " + describe(f)).formatted(net.minecraft.util.Formatting.GRAY)))
            if (decoded.filters.size > 5) hover.append(Text.literal("\n… +${decoded.filters.size - 5}"))
            message.copy().append(" ").append(Text.literal(t("[Import]", "[Importer]")).styled {
                it.withColor(net.minecraft.util.Formatting.AQUA).withUnderline(true)
                    .withClickEvent(net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/hbfilter import $code"))
                    .withHoverEvent(net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, hover))
            })
        } catch (_: Exception) { message }
    }

    /** The filter already covering exactly the same Pokémon — the one an import replaces. */
    fun sameScope(f: CaptureFilter): CaptureFilter? {
        ensureLoaded()
        if (f.all) return global()
        val keys = f.members().map { speciesKey(it) }.toSet()
        return filters.firstOrNull { !it.all && it.members().map { m -> speciesKey(m) }.toSet() == keys }
    }

    /**
     * Adds the filters of a code. One covering exactly the same Pokémon as a filter already
     * here replaces that filter's rule (its place in the priority order is kept); the others
     * are added. Returns each resulting filter, and whether it replaced one.
     */
    fun importFilters(list: List<CaptureFilter>): List<Pair<CaptureFilter, Boolean>> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val out = mutableListOf<Pair<CaptureFilter, Boolean>>()
        for ((i, f) in list.withIndex()) {
            val existing = sameScope(f)
            if (existing != null) {
                existing.mode = f.mode
                existing.enabled = f.enabled
                existing.groups = f.groups
                out.removeAll { it.first.id == existing.id }
                out += existing to true
            } else {
                f.id = UUID.randomUUID().toString()
                f.created = now + i   // the order of the code is the priority order
                filters.add(f)
                out += f to false
            }
        }
        changed()
        return out
    }

    // ── what is known about a Pokémon ──────────────────────────────────────────────

    /**
     * The facts a filter is checked against, as Tropimon's capture card gives them. Any of
     * them can be unknown when the card leaves it out.
     */
    class Subject(
        val ivs: IntArray?,
        val ivPercent: Int?,
        val shiny: Boolean?,
        val ability: String?,
        val hiddenAbility: Boolean?,
        val gender: String?,
        /** Cobblemon species path, for abilities that change when it evolves. */
        val species: String? = null
    )

    /** Tropimon's own figure, so the filter and the capture card always show the same %. */
    fun ivPercent(sum: Int): Int = sum * 100 / 186

    /**
     * Tropimon's capture card offers "Release" only below this, and never for a shiny,
     * legendary or mythical (`PokemonCaptureNotification.releasable`). The card is the
     * only way HunterBoard releases, so those are always kept.
     */
    const val RELEASE_MAX_IV_PERCENT = 90

    fun cardOffersRelease(pokemon: Pokemon): Boolean = try {
        // isLegendary()/isMythical() are always false on a server client: labels are not synced
        !pokemon.shiny && !SpeciesLabels.isLegendary(pokemon) && !SpeciesLabels.isMythical(pokemon) &&
            ivPercent(ivsOf(pokemon).sum()) <= RELEASE_MAX_IV_PERCENT
    } catch (_: Exception) { false }

    fun ivsOf(pokemon: Pokemon): IntArray =
        Field.IVS.map { pokemon.ivs[it.stat!!] ?: 0 }.toIntArray()

    fun subjectOf(pokemon: Pokemon): Subject {
        val ivs = ivsOf(pokemon)
        return Subject(
            ivs = ivs,
            ivPercent = ivPercent(ivs.sum()),
            shiny = pokemon.shiny,
            ability = norm(pokemon.ability.name),
            hiddenAbility = isHiddenAbility(pokemon),
            gender = pokemon.gender.name.lowercase(),
            species = pokemon.species.resourceIdentifier.path
        )
    }

    /**
     * From the capture card alone: its data, plus the six IVs read from its stat cells.
     * The IVs are only trusted when there are six numbers that add up to the card's total.
     */
    fun subjectOf(data: Map<String, String>, cells: List<String> = emptyList()): Subject {
        val total = data["ivTotal"]?.toIntOrNull()
        val ivs = cells.map { it.trim().toIntOrNull() ?: -1 }.toIntArray()
            .takeIf { a -> a.size == 6 && a.all { it in 0..31 } && (total == null || a.sum() == total) }
        return Subject(
            ivs = ivs,
            ivPercent = (total ?: ivs?.sum())?.let { ivPercent(it) },
            shiny = data["shiny"]?.toBooleanStrictOrNull(),
            // a translation key, "cobblemon.ability.static"; anything else is not trusted
            ability = data["ability"]?.takeIf { '.' in it }?.let { norm(it.substringAfterLast('.')) },
            hiddenAbility = data["abilityHidden"]?.toBooleanStrictOrNull(),
            gender = data["gender"]?.lowercase()?.takeIf { it in GENDERS },
            species = data["species"]
        )
    }

    private fun isHiddenAbility(pokemon: Pokemon): Boolean? = try {
        val name = pokemon.ability.name
        pokemon.form.abilities.any { it is HiddenAbility && it.template.name == name }
    } catch (_: Exception) { null }

    val GENDERS = listOf("male", "female", "genderless")

    /** Lowercase letters and digits only: "Mr. Mime", "mrmime" and "cobblemon:mrmime" agree. */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun speciesKey(s: String): String = norm(s.substringAfter(':'))

    // ── evaluation ─────────────────────────────────────────────────────────────────

    enum class Verdict { KEEP, RELEASE, UNKNOWN }

    /**
     * null when the condition looks at something the [Subject] does not know. Given the
     * filter, an ability that becomes the wanted one when the Pokémon evolves into one of
     * the filter's Pokémon counts as the wanted one ([carriedAbilities]).
     */
    fun test(c: Condition, s: Subject, f: CaptureFilter? = null): Boolean? {
        val field = Field.of(c.field)
        val raw: Boolean? = when (field.kind) {
            Kind.NUMBER -> {
                val v: Int? = if (field == Field.IV_TOTAL) s.ivPercent
                              else s.ivs?.getOrNull(Field.IVS.indexOf(field))
                v?.let { compare(it, c.op, c.value) }
            }
            Kind.BOOL -> {
                val v = if (field == Field.SHINY) s.shiny else s.hiddenAbility
                v?.let { it == (c.value != 0) }
            }
            Kind.CHOICE -> {
                val v = if (field == Field.ABILITY) s.ability else s.gender
                v?.let {
                    norm(it) == norm(c.text) || (field == Field.ABILITY && f != null && s.species != null &&
                        norm(it) in carriedAbilities(f, s.species, c.text))
                }
            }
        }
        return raw?.let { if (c.not) !it else it }
    }

    private fun compare(v: Int, op: String, ref: Int): Boolean = when (op) {
        ">=" -> v >= ref; ">" -> v > ref; "=" -> v == ref
        "!=" -> v != ref; "<" -> v < ref; "<=" -> v <= ref
        else -> false
    }

    /** AND inside a group, OR between groups, with unknowns kept honest. */
    fun matches(f: CaptureFilter, s: Subject): Boolean? {
        var unknownGroup = false
        for (g in f.activeGroups()) {
            var unknown = false
            var failed = false
            for (c in g.conditions) {
                when (test(c, s, f)) {
                    false -> { failed = true; break }
                    null -> unknown = true
                    true -> {}
                }
            }
            if (failed) continue
            if (!unknown) return true
            unknownGroup = true
        }
        return if (unknownGroup) null else false
    }

    fun verdict(f: CaptureFilter, s: Subject): Verdict {
        if (!f.hasConditions() || !f.isComplete()) return Verdict.KEEP
        val m = matches(f, s) ?: return Verdict.UNKNOWN
        val release = if (f.mode == MODE_RELEASE) m else !m
        return if (release) Verdict.RELEASE else Verdict.KEEP
    }

    // ── abilities through evolution ────────────────────────────────────────────────

    /**
     * The abilities a Pokémon of [speciesPath] can have that turn into [wanted] when it
     * evolves into one of the filter's Pokémon, each with the Pokémon it does so in.
     *
     * Cobblemon keeps an ability's place through an evolution, not its name: the same rank
     * among the same kind (normal or hidden), and the one before when the evolution has
     * fewer (`Pokemon.attemptAbilityUpdate`). A Wingull with Hydration, its second, becomes
     * a Pelipper with Drizzle, Pelipper's second: a filter on the family asking for Drizzle
     * keeps it. Only the evolutions the filter names count, and never backwards.
     */
    fun carriedAbilities(f: CaptureFilter, speciesPath: String, wanted: String): Map<String, String> {
        if (f.all || wanted.isBlank()) return emptyMap()
        val from = species(speciesPath) ?: return emptyMap()
        val want = norm(wanted)
        val later = descendants(from).filter { f.covers(speciesKey(it.resourceIdentifier.path)) }
        if (later.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        try {
            for ((priority, list) in from.abilities.mapping) {
                for ((i, a) in list.withIndex()) {
                    val id = norm(a.template.name)
                    if (id == want || id in out) continue
                    for (d in later) {
                        val there = d.abilities.mapping[priority] ?: continue
                        val got = (minOf(i, there.size - 1) downTo 0).firstNotNullOfOrNull { there.getOrNull(it) } ?: continue
                        if (norm(got.template.name) == want) { out[id] = d.resourceIdentifier.path; break }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Every species this one can still evolve into, however many steps away. */
    private fun descendants(from: Species): List<Species> {
        val out = mutableListOf<Species>()
        fun collect(n: EvoNode) {
            for (c in n.children) {
                c.species?.let { s -> if (out.none { it.resourceIdentifier == s.resourceIdentifier }) out += s }
                collect(c)
            }
        }
        fun find(n: EvoNode): EvoNode? =
            if (n.species?.resourceIdentifier == from.resourceIdentifier) n else n.children.firstNotNullOfOrNull { find(it) }
        try { EvolutionData.getEvolutionTree(from)?.let { root -> find(root)?.let { collect(it) } } } catch (_: Exception) {}
        return out
    }

    /** One "this ability counts as the wanted one" case, for the filter screen. */
    class Carried(val species: String, val ability: String, val wanted: String, val into: String)

    private fun wantedAbilities(f: CaptureFilter): List<String> = f.activeGroups()
        .flatMap { it.conditions }.filter { it.field == Field.ABILITY.id && it.text.isNotBlank() }
        .map { it.text }.distinctBy { norm(it) }

    /** Every case among the filter's Pokémon where an ability counts through evolution. */
    fun carriedCases(f: CaptureFilter, wanted: String? = null): List<Carried> {
        if (f.all) return emptyList()
        val out = mutableListOf<Carried>()
        for (w in wanted?.let { listOf(it) } ?: wantedAbilities(f)) {
            for (m in f.members()) {
                for ((a, into) in carriedAbilities(f, m, w)) out += Carried(m, a, w, into)
            }
        }
        return out
    }

    // ── words ──────────────────────────────────────────────────────────────────────

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    fun species(path: String): Species? = try {
        PokemonSpecies.getByName(path.substringAfter(':'))
    } catch (_: Exception) { null }

    fun speciesName(path: String): String =
        species(path)?.translatedName?.string ?: path.replaceFirstChar { it.uppercase() }

    fun abilityName(id: String): String {
        if (id.isBlank()) return t("(choose)", "(choisir)")
        return try {
            val template = com.cobblemon.mod.common.api.abilities.Abilities.get(id)
            if (template != null) Text.translatable(template.displayName).string
            else id.replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { id.replaceFirstChar { it.uppercase() } }
    }

    fun statShort(stat: Stat): String = when (stat) {
        Stats.HP -> t("HP", "PV")
        Stats.ATTACK -> t("Atk", "Atq")
        Stats.DEFENCE -> t("Def", "Déf")
        Stats.SPECIAL_ATTACK -> t("SpA", "AtqS")
        Stats.SPECIAL_DEFENCE -> t("SpD", "DéfS")
        Stats.SPEED -> t("Spe", "Vit")
        else -> stat.toString()
    }

    fun genderName(id: String): String = when (id) {
        "male" -> t("Male ♂", "Mâle ♂")
        "female" -> t("Female ♀", "Femelle ♀")
        "genderless" -> t("Genderless", "Asexué")
        else -> t("(choose)", "(choisir)")
    }

    fun yesNo(v: Int) = if (v != 0) t("Yes", "Oui") else t("No", "Non")

    /** The value half of a condition, as the editor and the summary show it. */
    fun valueText(c: Condition): String {
        val field = Field.of(c.field)
        return when (field.kind) {
            Kind.NUMBER -> if (field == Field.IV_TOTAL) "${c.value} %" else "${c.value}"
            Kind.BOOL -> yesNo(c.value)
            Kind.CHOICE -> if (field == Field.ABILITY) abilityName(c.text) else genderName(c.text)
        }
    }

    /**
     * One condition in plain words. NOT is folded into the operator — "not ≥ 80" reads
     * "< 80", "not shiny" reads "Shiny = No" — so the summary never needs a "not (…)".
     */
    fun describe(c: Condition): String {
        val field = Field.of(c.field)
        return when (field.kind) {
            Kind.NUMBER -> "${field.label()} ${opSymbol(if (c.not) negated(c.op) else c.op)} ${valueText(c)}"
            Kind.BOOL -> "${field.label()} = ${yesNo(if (c.not) 1 - c.value.coerceIn(0, 1) else c.value)}"
            Kind.CHOICE -> "${field.label()} ${if (c.not) "≠" else "="} ${valueText(c)}"
        }
    }

    /** The whole rule as one sentence: "Keep if (Total IVs ≥ 80 % and Speed IV = 31) or Shiny = Yes." */
    fun describe(f: CaptureFilter): String {
        val groups = f.activeGroups()
        if (groups.isEmpty()) return t("No condition yet — this filter does nothing.",
            "Aucune condition — ce filtre n'agit pas encore.")
        val and = t(" and ", " et ")
        val or = t(" or ", " ou ")
        val parts = groups.map { g ->
            val inner = g.conditions.joinToString(and) { describe(it) }
            if (groups.size > 1 && g.conditions.size > 1) "($inner)" else inner
        }
        val rule = parts.joinToString(or)
        return if (f.mode == MODE_RELEASE) t("Release if $rule; keep the others.", "Relâcher si $rule ; garder les autres.")
               else t("Keep if $rule; release the others.", "Garder si $rule ; relâcher les autres.")
    }

    /** The six IVs and the ability from the capture card, for chat hovers. */
    fun details(s: Subject): String {
        val stats = s.ivs?.let { ivs ->
            Field.IVS.mapIndexed { i, f -> "${statShort(f.stat!!)} ${ivs[i]}" }.joinToString(" · ")
        }
        val ability = s.ability?.let { id ->
            "${t("Ability", "Talent")} : ${abilityName(id)}" + if (s.hiddenAbility == true) " (${t("hidden", "caché")})" else ""
        }
        return listOfNotNull(stats, ability).joinToString("\n")
    }
}
