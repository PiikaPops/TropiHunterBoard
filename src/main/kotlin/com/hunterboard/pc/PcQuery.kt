package com.hunterboard.pc

import com.hunterboard.NameUtil
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Everything a search can ask for. A plain value so it can be saved as a named search and
 * restored as is. Tri-state fields: 0 = either, 1 = only, 2 = excluded.
 */
@Serializable
data class PcFilterState(
    val query: String = "",
    val levelMin: Int = 1,
    val levelMax: Int = 100,
    val ivMin: Int = 0,
    /** Arrived within the last N hours (0 = no limit); only Pokémon you caught yourself. */
    val ageHours: Int = 0,
    val shiny: Int = 0,
    val alpha: Int = 0,
    val legendary: Int = 0,
    val favorite: Int = 0,
    val held: Int = 0,
    val marked: Int = 0,
    val hiddenAbility: Int = 0,
    val duplicates: Boolean = false,
    /** 0 any, 1 male, 2 female, 3 genderless. */
    val gender: Int = 0,
    val type: String = "",
    /** -1 every box, -2 the party, else a box index. */
    val box: Int = -1,
    val ability: String = "",
    val size: String = "",
    /** "" any, "-" untagged, else a tag id. */
    val tag: String = "",
    val sort: String = SORT_PLACE,
    val reverse: Boolean = false,
    /** Wanted IV per stat for the "closest IVs" sort, -1 = ignored. */
    val ivTarget: List<Int> = listOf(31, 31, 31, 31, 31, 31),
) {
    /** True when nothing narrows the list (the sort does not count). */
    val isEmpty: Boolean get() = copy(sort = SORT_PLACE, reverse = false, ivTarget = DEFAULT_TARGET) == PcFilterState()

    /** How many filters narrow the list, the search text and the sort aside. */
    val activeFilters: Int get() = listOf(
        levelMin != 1 || levelMax != 100, ivMin != 0, ageHours != 0, shiny != 0, alpha != 0, legendary != 0,
        favorite != 0, held != 0, marked != 0, hiddenAbility != 0, duplicates, gender != 0, type.isNotEmpty(),
        box != -1, ability.isNotEmpty(), size.isNotEmpty(), tag.isNotEmpty(),
    ).count { it }

    companion object {
        const val SORT_PLACE = "place"
        const val SORT_RECENT = "recent"
        const val SORT_LEVEL = "level"
        const val SORT_IV = "iv"
        const val SORT_DEX = "dex"
        const val SORT_NAME = "name"
        const val SORT_TYPE = "type"
        const val SORT_TARGET = "target"
        val SORTS = listOf(SORT_PLACE, SORT_RECENT, SORT_LEVEL, SORT_IV, SORT_DEX, SORT_NAME, SORT_TYPE, SORT_TARGET)
        val DEFAULT_TARGET = listOf(31, 31, 31, 31, 31, 31)
        val SIZES = listOf("XS", "S", "M", "L", "XL")

        fun sortLabel(s: String): String = when (s) {
            SORT_RECENT -> L("Most recent", "Plus récents")
            SORT_LEVEL -> L("Level", "Niveau")
            SORT_IV -> "IV %"
            SORT_DEX -> L("Pokédex no.", "N° Pokédex")
            SORT_NAME -> L("Name", "Nom")
            SORT_TYPE -> L("Type", "Type")
            SORT_TARGET -> L("Closest IVs", "IV cibles")
            else -> L("PC order", "Ordre du PC")
        }
    }
}

/** Filtering, sorting and the release-candidate pick, over a snapshot of entries. */
object PcQuery {

    /** How far an entry's IVs are from the wanted ones (lower is closer); ignored stats skipped. */
    fun targetDistance(e: PcEntry, target: List<Int>): Int {
        var d = 0
        for (i in 0 until 6) { val t = target.getOrElse(i) { -1 }; if (t >= 0) d += kotlin.math.abs(e.ivs[i] - t) }
        return d
    }

    fun filter(all: List<PcEntry>, f: PcFilterState, prefs: PcPrefs, now: Long): List<PcEntry> {
        val words = NameUtil.normalize(f.query).split(' ').filter { it.isNotBlank() }
        val counts = if (f.duplicates) all.groupingBy { it.lineKey }.eachCount() else emptyMap()
        val ageMs = f.ageHours * 3_600_000L
        return all.filter { e ->
            if (f.box == -2 && !e.place.inParty) return@filter false
            if (f.box >= 0 && e.place.box != f.box) return@filter false
            if (e.level < f.levelMin || e.level > f.levelMax) return@filter false
            if (e.ivPct + 1e-6 < f.ivMin) return@filter false
            if (!tri(f.shiny, e.shiny) || !tri(f.alpha, e.alpha) || !tri(f.legendary, e.special)) return@filter false
            if (!tri(f.favorite, prefs.isFavorite(e.uuid)) || !tri(f.held, e.hasItem)) return@filter false
            if (!tri(f.marked, e.marked) || !tri(f.hiddenAbility, e.hiddenAbility)) return@filter false
            if (f.duplicates && (counts[e.lineKey] ?: 0) < 2) return@filter false
            if (f.gender != 0 && e.gender != f.gender) return@filter false
            if (f.type.isNotEmpty() && f.type !in e.types) return@filter false
            if (f.ability.isNotEmpty() && e.abilityId != f.ability) return@filter false
            if (f.size.isNotEmpty() && !e.size.equals(f.size, true)) return@filter false
            when (f.tag) {
                "" -> {}
                "-" -> if (prefs.tagsOf(e.uuid).isNotEmpty()) return@filter false
                else -> if (f.tag !in prefs.tagsOf(e.uuid)) return@filter false
            }
            if (ageMs > 0) {
                val seen = prefs.firstSeen(e.uuid)
                if (!e.ownOt || seen <= 0L || now - seen > ageMs) return@filter false
            }
            words.all { e.search.contains(it) }
        }
    }

    private fun tri(mode: Int, value: Boolean) = when (mode) { 1 -> value; 2 -> !value; else -> true }

    /**
     * What [sort] orders by, without the place it falls back on: two Pokémon with the same
     * rank may come in either order. Arranging uses it to leave equal Pokémon where they are.
     */
    fun rank(f: PcFilterState, prefs: PcPrefs): (PcEntry) -> Any = when (f.sort) {
        PcFilterState.SORT_RECENT -> { e -> prefs.firstSeen(e.uuid) }
        PcFilterState.SORT_LEVEL -> { e -> listOf(e.level, e.dex) }
        PcFilterState.SORT_IV -> { e -> listOf(e.ivSum, e.dex) }
        PcFilterState.SORT_DEX -> { e -> listOf(e.dex, e.formLabel ?: "", e.ivSum) }
        PcFilterState.SORT_NAME -> { e -> NameUtil.normalize(e.displayName) }
        PcFilterState.SORT_TYPE -> { e -> listOf(e.types.firstOrNull() ?: "", e.types.getOrNull(1) ?: "", e.dex) }
        PcFilterState.SORT_TARGET -> { e -> listOf(targetDistance(e, f.ivTarget), e.ivSum) }
        else -> { e -> e.uuid }
    }

    fun sort(list: List<PcEntry>, f: PcFilterState, prefs: PcPrefs): List<PcEntry> {
        val place = compareBy<PcEntry>({ if (it.place.inParty) -1 else it.place.box }, { it.place.slot })
        val cmp: Comparator<PcEntry> = when (f.sort) {
            PcFilterState.SORT_RECENT -> compareByDescending<PcEntry> { prefs.firstSeen(it.uuid) }.then(place)
            PcFilterState.SORT_LEVEL -> compareByDescending<PcEntry> { it.level }.thenBy { it.dex }.then(place)
            PcFilterState.SORT_IV -> compareByDescending<PcEntry> { it.ivSum }.thenBy { it.dex }.then(place)
            PcFilterState.SORT_DEX -> compareBy<PcEntry>({ it.dex }, { it.formLabel ?: "" }).thenByDescending { it.ivSum }.then(place)
            PcFilterState.SORT_NAME -> compareBy<PcEntry> { NameUtil.normalize(it.displayName) }.then(place)
            PcFilterState.SORT_TYPE -> compareBy<PcEntry>({ it.types.firstOrNull() ?: "" }, { it.types.getOrNull(1) ?: "" }, { it.dex }).then(place)
            PcFilterState.SORT_TARGET -> compareBy<PcEntry> { targetDistance(it, f.ivTarget) }.thenByDescending { it.ivSum }.then(place)
            else -> place
        }
        val sorted = list.sortedWith(cmp)
        return if (f.reverse) sorted.reversed() else sorted
    }

    /** Why an entry may never be part of a batch release, or null when it may. */
    fun protection(e: PcEntry, prefs: PcPrefs, opts: ReleaseGuards): String? = when {
        e.place.inParty -> L("in the party", "dans l'équipe")
        prefs.isFavorite(e.uuid) -> L("favourite", "favori")
        opts.shiny && e.shiny -> "shiny"
        opts.legendary && e.special -> L("legendary", "légendaire")
        opts.alpha && e.alpha -> "alpha"
        opts.marked && e.marked -> L("has a mark", "marqué")
        opts.held && e.hasItem -> L("holds an item", "porte un objet")
        opts.tagged && prefs.tagsOf(e.uuid).isNotEmpty() -> L("tagged", "tagué")
        else -> null
    }

    /**
     * True when [e] has what the release rules call worth keeping: IV total at least
     * [PcPrefs.protectIvPct] (0: not asked), the wanted IVs (all of them, as one quality), a
     * hidden ability, the ability, nature or gender asked for. At least one of those set, or
     * every one of them ([ReleaseRules.matchAll]); none set: false.
     */
    fun keeps(e: PcEntry, prefs: PcPrefs): Boolean {
        val r = prefs.release
        val checks = ArrayList<Boolean>(6)
        if (prefs.protectIvPct > 0) checks += e.ivPct + 1e-6 >= prefs.protectIvPct
        val wanted = r.ivs.withIndex().filter { it.index < 6 && it.value >= 0 }
        if (wanted.isNotEmpty()) checks += wanted.all { (k, v) -> if (v == 0) e.ivs[k] == 0 else e.ivs[k] >= v }
        if (prefs.protectHidden) checks += e.hiddenAbility
        if (r.ability.isNotEmpty()) checks += e.abilityId == r.ability
        if (r.nature.isNotEmpty()) checks += e.natureId == r.nature
        if (r.gender != 0) checks += e.gender == r.gender
        if (checks.isEmpty()) return false
        return if (r.matchAll) checks.all { it } else checks.any { it }
    }

    /** The Pokémon a release scope looks at; null for the whole PC. */
    fun scopeOf(scope: String, all: List<PcEntry>, selection: Set<UUID>, results: List<PcEntry>): Set<UUID>? = when {
        scope == "selection" -> selection.toHashSet()
        scope == "search" -> results.mapTo(HashSet()) { it.uuid }
        scope.startsWith("species:") -> scope.removePrefix("species:").let { k -> all.filter { it.lineKey == k }.mapTo(HashSet()) { it.uuid } }
        scope.startsWith("type:") -> scope.removePrefix("type:").let { t -> all.filter { t in it.types }.mapTo(HashSet()) { it.uuid } }
        else -> null
    }

    /**
     * Release candidates among [scope] (null: the whole PC): for every species (form
     * included), the copies beyond the [PcPrefs.keepPerSpecies] best ones, never a protected
     * one nor one with what [keeps] asks for. The best copies are picked among *all* copies
     * in the PC, protected and out of scope ones included, so a shiny keeps its role of "the
     * one kept" and a plain copy next to it can go, and narrowing the scope never releases
     * the last copies of a species.
     */
    fun candidates(all: List<PcEntry>, prefs: PcPrefs, scope: Set<UUID>? = null): List<PcEntry> {
        val guards = prefs.release.guards
        val kept = HashMap<UUID, Boolean>()
        fun keep(e: PcEntry) = kept.getOrPut(e.uuid) { keeps(e, prefs) }
        val quality = compareByDescending<PcEntry> { keep(it) }
            .thenByDescending { it.shiny }
            .thenByDescending { it.alpha }
            .thenByDescending { prefs.isFavorite(it.uuid) }
            .thenByDescending { it.ivSum }
            .thenByDescending { it.level }
        val lines = all.filter { !it.place.inParty && (scope == null || it.uuid in scope) }.mapTo(HashSet()) { it.lineKey }
        val out = mutableListOf<PcEntry>()
        for ((line, copies) in all.filter { !it.place.inParty && it.lineKey in lines }.groupBy { it.lineKey }) {
            // party copies count as kept ones: the team is what you play with
            val inParty = all.count { it.place.inParty && it.lineKey == line }
            val n = (prefs.keepPerSpecies - inParty).coerceAtLeast(0)
            for (e in copies.sortedWith(quality).drop(n)) {
                if (scope != null && e.uuid !in scope) continue
                if (protection(e, prefs, guards) != null) continue
                if (keep(e)) continue
                out += e
            }
        }
        return out.sortedWith(compareBy({ it.dex }, { it.place.box }, { it.place.slot }))
    }
}

/**
 * Box labels: a rule given to a box (or several) says which Pokémon belong there. Arranging
 * the PC sends the matching ones into those boxes first; the others go to unlabelled boxes.
 */
object BoxRules {
    val BASIC = listOf("shiny", "special", "alpha", "favorite")

    fun label(rule: String, prefs: PcPrefs, typeName: (String) -> String): String = when {
        rule == "shiny" -> "Shiny"
        rule == "special" -> L("Legendary", "Légendaires")
        rule == "alpha" -> "Alpha"
        rule == "favorite" -> L("Favourites", "Favoris")
        rule.startsWith("tag:") -> prefs.tagName(rule.removePrefix("tag:"))
        rule.startsWith("type:") -> typeName(rule.removePrefix("type:"))
        else -> rule
    }

    fun color(rule: String): Int = when {
        rule == "shiny" -> SHINY_COLOR
        rule == "special" -> LEGEND_COLOR
        rule == "alpha" -> ALPHA_COLOR
        rule == "favorite" -> FAV_COLOR
        else -> 0xFF7FD4FF.toInt()
    }

    fun matches(rule: String, e: PcEntry, prefs: PcPrefs): Boolean = when {
        rule == "shiny" -> e.shiny
        rule == "special" -> e.special
        rule == "alpha" -> e.alpha
        rule == "favorite" -> prefs.isFavorite(e.uuid)
        rule.startsWith("tag:") -> rule.removePrefix("tag:") in prefs.tagsOf(e.uuid)
        rule.startsWith("type:") -> rule.removePrefix("type:") in e.types
        else -> false
    }

    /**
     * The rule [e] belongs to among the labelled boxes, or null. A personal tag wins over
     * everything else (a shiny tagged "PvP" goes to the PvP box, not the shiny one); then the
     * first in box order.
     */
    fun ruleFor(e: PcEntry, prefs: PcPrefs): String? {
        val rules = prefs.boxRules().entries.sortedBy { it.key }.map { it.value }.distinct()
        return rules.firstOrNull { it.startsWith("tag:") && matches(it, e, prefs) } ?: rules.firstOrNull { matches(it, e, prefs) }
    }

    /** The boxes [e]'s label rule sends it to (box order), empty when none applies. */
    fun boxesFor(e: PcEntry, prefs: PcPrefs, boxCount: Int): List<Int> {
        val rule = ruleFor(e, prefs) ?: return emptyList()
        return prefs.boxRules().filter { it.value == rule && it.key < boxCount }.keys.sorted()
    }
}

/** Which kinds of Pokémon a batch release leaves alone; favourites and the party always are. */
@Serializable
data class ReleaseGuards(
    val shiny: Boolean = true,
    val legendary: Boolean = true,
    // alpha and marked Pokémon are no longer protected by default (asked for): ticked by hand if wanted
    val alpha: Boolean = false,
    val marked: Boolean = false,
    val held: Boolean = true,
    val tagged: Boolean = true,
    val hiddenAbility: Boolean = false,
) {
    companion object { val DEFAULT = ReleaseGuards() }
}
