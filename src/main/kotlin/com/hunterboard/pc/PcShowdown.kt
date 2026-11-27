package com.hunterboard.pc

import com.cobblemon.mod.common.api.moves.Moves
import com.hunterboard.NameUtil

/**
 * A team pasted from Pokémon Showdown (the "Export" text, or a PokePaste), turned into a saved
 * team made of the player's own Pokémon.
 *
 * Each set takes one of the player's *trained* Pokémon of that species (and form) — one with
 * EVs; the set's EVs need not match (the player's wish), but the member keeps them and shows a
 * warning while its own differ ([evsDiffer]). Among several: EVs matching the set first, then
 * its nature, its ability, the closest EVs, shininess, the IVs closest to the set's (31
 * everywhere unless an "IVs:" line says otherwise), the level. None trained, none at all, or
 * the only one already taken: the place stays empty and [Import.holes] says which. The set's
 * moves become the member's moves for the team (Cobblemon names moves as Showdown does). The
 * set's item is left out: held items are the player's to give.
 */
internal object PcShowdown {

    class PokeSet(
        val species: String,
        val ability: String,
        val nature: String,
        val evs: IntArray?,
        /** The wanted IVs: 31 for the stats an "IVs:" line leaves out, as Showdown means it. */
        val ivs: IntArray,
        val shiny: Boolean,
        val moves: List<String>,
    )

    /** Why a place stayed empty: the set's [species] and a short [reason] for the place, [why] whole for its tooltip. */
    class Hole(val species: String, val reason: String, val why: String) {
        val label get() = "$species · $reason"
    }

    class Import(
        val name: String?, val team: PcTeam, val found: Int, val sets: Int,
        /** Everything worth telling, holes first, in set order. */
        val problems: List<String>,
        /** Per place (as the team's), the reason it is empty; null where filled or past the sets. */
        val holes: List<Hole?>,
        /** The rest: moves Cobblemon does not know. */
        val notes: List<String>,
    )

    private val STAT_KEYS = listOf("hp", "atk", "def", "spa", "spd", "spe")
    /** Forms that only exist in battle, or cosmetic ones: the base Pokémon is the one wanted. */
    private val BATTLE_FORMS = setOf("mega", "megax", "megay", "gmax", "tera", "terastal", "stellar", "primal")
    /** How far each EV may be from the set's: a few spare points spent elsewhere. */
    private const val EV_SLACK = 8

    fun parse(text: String): Pair<String?, List<PokeSet>> {
        var name: String? = null
        val sets = mutableListOf<PokeSet>()
        val blocks = text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            // a PokePaste/Showdown team header: "=== [gen9ou] My team ==="
            while (lines.isNotEmpty() && lines[0].startsWith("===")) {
                name = lines.removeAt(0).trim('=', ' ').replace(Regex("^\\[[^]]*]\\s*"), "").ifBlank { name }
            }
            val head = lines.firstOrNull() ?: continue
            if (head.startsWith("-") || head.contains(":")) continue
            var main = head.substringBefore(" @ ")   // the item: not part of the team
            main = main.replace(Regex("\\s*\\((M|F)\\)\\s*$"), "").trim()
            val species = Regex("^.*\\((.+)\\)$").find(main)?.groupValues?.get(1)?.trim() ?: main
            var ability = ""; var nature = ""; var evs: IntArray? = null; var ivs = IntArray(6) { 31 }; var shiny = false
            val moves = mutableListOf<String>()
            for (l in lines.drop(1)) {
                when {
                    l.startsWith("Ability:", true) -> ability = l.substringAfter(':').trim()
                    l.startsWith("EVs:", true) -> evs = stats(l.substringAfter(':'), 0)
                    l.startsWith("IVs:", true) -> ivs = stats(l.substringAfter(':'), 31)
                    l.startsWith("Shiny:", true) -> shiny = l.substringAfter(':').trim().equals("yes", true)
                    l.endsWith(" Nature", true) -> nature = l.removeSuffix(" Nature").trim()
                    // "- " alone is a move slot left blank in the builder
                    l.startsWith("-") -> l.removePrefix("-").trim().takeIf { it.isNotEmpty() }?.let { moves += it }
                }
            }
            sets += PokeSet(species, ability, nature, evs, ivs, shiny, moves.take(4))
        }
        return name to sets.take(6)
    }

    /** "252 Atk / 4 SpD / 252 Spe" → six values, the missing ones [rest]. */
    private fun stats(s: String, rest: Int): IntArray {
        val out = IntArray(6) { rest }
        for (part in s.split('/')) {
            val m = Regex("(\\d+)\\s*([A-Za-z]+)").find(part.trim()) ?: continue
            val k = STAT_KEYS.indexOf(m.groupValues[2].lowercase())
            if (k >= 0) out[k] = m.groupValues[1].toInt()
        }
        return out
    }

    /** "252 HP / 4 Def" in the game's language, the zero stats left out. */
    fun evText(evs: IntArray): String {
        val names = listOf(L("HP", "PV"), L("Atk", "Atq"), L("Def", "Déf"), L("SpA", "AtS"), L("SpD", "DfS"), L("Spe", "Vit"))
        return (0 until 6).filter { evs[it] > 0 }.joinToString(" / ") { "${evs[it]} ${names[it]}" }.ifEmpty { L("no EVs", "aucun EV") }
    }

    /** Cobblemon's id for a Showdown move name ("U-turn" → "uturn"), when Cobblemon knows it. */
    fun moveId(name: String): String? {
        val id = name.lowercase().replace(Regex("\\[.*]"), "").replace(Regex("[^a-z0-9]"), "")
        return id.takeIf { it.isNotEmpty() && Moves.getByName(it) != null }
    }

    /** The set's species and the form it asks for ("Ninetales-Alola" → ninetales, "alola"). */
    private fun speciesOf(name: String, ids: Set<String>): Pair<String, String>? {
        val parts = name.split('-')
        for (cut in parts.size downTo 1) {
            val id = NameUtil.normalize(parts.take(cut).joinToString(""))
            if (id in ids) return id to NameUtil.normalize(parts.drop(cut).joinToString(""))
        }
        return null
    }

    private fun evGap(e: PcEntry, evs: IntArray) = (0 until 6).sumOf { kotlin.math.abs(e.evs[it] - evs[it]) }

    /** True when a set asked for [wanted] EVs and [e]'s are not those (each within [EV_SLACK]). */
    fun evsDiffer(wanted: List<Int>, e: PcEntry): Boolean =
        wanted.size == 6 && (0 until 6).any { kotlin.math.abs(e.evs[it] - wanted[it]) > EV_SLACK }

    /** The warning for a member whose EVs are not its set's. */
    fun evWarning(wanted: List<Int>, e: PcEntry): String =
        L("EVs not the set's: it has ${evText(e.evs)}, the set asks ${evText(wanted.toIntArray())}.",
          "EV différents du set : il a ${evText(e.evs)}, le set demande ${evText(wanted.toIntArray())}.")

    fun build(text: String, entries: List<PcEntry>, fallbackName: String): Import {
        val (name, sets) = parse(text)
        val problems = mutableListOf<String>()
        val moveProblems = mutableListOf<String>()
        val offEvs = mutableListOf<String>()
        val holes = MutableList<Hole?>(6) { null }
        val used = HashSet<java.util.UUID>()
        val ids = entries.map { NameUtil.normalize(it.speciesId) }.toSet()
        var found = 0
        val slots = sets.mapIndexed { i, set ->
            val sp = speciesOf(set.species, ids)
            val ofSpecies = if (sp == null) emptyList() else entries.filter { e ->
                NameUtil.normalize(e.speciesId) == sp.first &&
                    (sp.second.isEmpty() || sp.second in BATTLE_FORMS || NameUtil.normalize(e.formLabel ?: "") == sp.second)
            }
            val candidates = ofSpecies.filter { it.uuid !in used }
            // a trained one (some EVs), whatever they are; a set without EVs takes any
            val trained = if (set.evs == null) candidates else candidates.filter { it.evSum > 0 }
            val wanted = set.evs
            val pick = trained.maxWithOrNull(compareBy<PcEntry>(
                { e -> if (wanted == null) e.evSum > 0 else !evsDiffer(wanted.toList(), e) },
                { NameUtil.normalize(it.natureId) == NameUtil.normalize(set.nature) },
                { NameUtil.normalize(it.abilityId) == NameUtil.normalize(set.ability) },
                { e -> if (wanted == null) 0 else -evGap(e, wanted) },
                { it.shiny == set.shiny },
                { e -> -(0 until 6).sumOf { kotlin.math.abs(e.ivs[it] - set.ivs[it]) } },
                { it.level },
            ))
            if (pick == null) {
                val hole = when {
                    ofSpecies.isEmpty() -> Hole(set.species, L("not in the PC", "absent du PC"),
                        L("No ${set.species} in your PC or party.", "Aucun ${set.species} dans ton PC ni ton équipe."))
                    candidates.isEmpty() -> Hole(set.species, L("already used", "déjà pris"),
                        L("Your ${set.species} already takes another place of this team.", "Ton ${set.species} occupe déjà une autre place de cette équipe."))
                    else -> Hole(set.species, L("not trained", "pas entraîné"),
                        L("Your ${set.species} have no EVs: the import only takes trained Pokémon.",
                          "Tes ${set.species} n'ont aucun EV : l'import ne prend que des Pokémon entraînés."))
                }
                if (i < 6) holes[i] = hole
                problems += hole.label
                return@mapIndexed null
            }
            used += pick.uuid
            found++
            if (wanted != null && evsDiffer(wanted.toList(), pick)) offEvs += set.species + L(" · other EVs", " · EV différents")
            val moves = set.moves.mapNotNull { n -> moveId(n) ?: run { moveProblems += L("Unknown move: ", "Capacité inconnue : ") + n; null } }
            PcTeamMember(pick.uuid.toString(), moves, wanted?.toList() ?: emptyList())
        }
        val team = PcTeam(name?.take(30)?.ifBlank { null } ?: fallbackName).withSlots(slots)
        return Import(name, team, found, sets.size, problems + offEvs + moveProblems, holes, moveProblems.distinct())
    }
}
