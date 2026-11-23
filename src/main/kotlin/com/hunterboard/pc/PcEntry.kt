package com.hunterboard.pc

import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.client.storage.ClientPC
import com.cobblemon.mod.common.client.storage.ClientParty
import com.cobblemon.mod.common.pokemon.OriginalTrainerType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import com.hunterboard.NameUtil
import com.hunterboard.Translations
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.UUID

internal fun L(en: String, fr: String): String = if (Translations.isFrench()) fr else en

/** The six stats in the order every table here uses: HP, Atk, Def, SpA, SpD, Spe. */
internal val STATS: List<Stat> = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)

internal fun statLabels(): List<String> =
    if (Translations.isFrench()) listOf("PV", "Atq", "Déf", "AtS", "DfS", "Vit") else listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")

/** Where a Pokémon sits: a PC box and slot, or the party ([box] = -1). */
data class PcPlace(val box: Int, val slot: Int) {
    val inParty: Boolean get() = box < 0
    companion object { fun party(slot: Int) = PcPlace(-1, slot) }
}

/**
 * Everything the screen shows or filters on, read once from a [Pokemon] when the storage
 * changes, so that filtering a full PC each frame never touches Cobblemon's objects.
 */
class PcEntry(val pokemon: Pokemon, val place: PcPlace) {
    val uuid: UUID = pokemon.uuid
    val speciesId: String = pokemon.species.resourceIdentifier.path
    /** Species + form: two regional forms are not duplicates of each other. */
    val lineKey: String = speciesId + "|" + (pokemon.form.name.lowercase())
    val speciesName: String = runCatching { pokemon.species.translatedName.string }.getOrDefault(pokemon.species.name)
    val nickname: String? = pokemon.nickname?.string?.takeIf { it.isNotBlank() && it != speciesName }
    val displayName: String get() = nickname ?: speciesName
    val formLabel: String? = pokemon.form.name.takeIf { !it.equals("normal", true) && it.isNotBlank() }
    val level: Int = pokemon.level
    val shiny: Boolean = pokemon.shiny
    val alpha: Boolean = runCatching { pokemon.isAlpha }.getOrDefault(false)
    // Cobblemon's isLegendary() is always false on a server client (labels are not synced)
    val legendary: Boolean = com.hunterboard.SpeciesLabels.isLegendary(pokemon)
    val mythical: Boolean = com.hunterboard.SpeciesLabels.isMythical(pokemon)
    val ultraBeast: Boolean = com.hunterboard.SpeciesLabels.isUltraBeast(pokemon)
    /** Legendary, mythical or ultra beast: what the "Legendary" filter and protection cover. */
    val special: Boolean = legendary || mythical || ultraBeast
    val gender: Int = when (pokemon.gender.name) { "MALE" -> 1; "FEMALE" -> 2; else -> 3 }
    val types: List<String> = pokemon.types.map { it.name.lowercase() }
    /** The types themselves, for Cobblemon's icons. */
    val elementalTypes: List<com.cobblemon.mod.common.api.types.ElementalType> = pokemon.types.toList()
    val typeNames: List<String> = pokemon.types.map { runCatching { it.displayName.string }.getOrDefault(it.name) }
    val ivs: IntArray = IntArray(6) { pokemon.ivs[STATS[it]] ?: 0 }
    val ivSum: Int = ivs.sum()
    val ivPct: Double = ivSum * 100.0 / 186.0
    val evs: IntArray = IntArray(6) { pokemon.evs[STATS[it]] ?: 0 }
    val evSum: Int = evs.sum()
    val natureName: String = runCatching { Text.translatable(pokemon.effectiveNature.displayName).string }.getOrDefault("")
    val natureUp: Int = STATS.indexOf(pokemon.effectiveNature.increasedStat)
    val natureDown: Int = STATS.indexOf(pokemon.effectiveNature.decreasedStat)
    val natureId: String = runCatching { pokemon.effectiveNature.name.path }.getOrDefault("")
    val abilityId: String = pokemon.ability.name.lowercase()
    val abilityName: String = runCatching { Text.translatable(pokemon.ability.displayName).string }.getOrDefault(pokemon.ability.name)
    val hiddenAbility: Boolean = runCatching {
        pokemon.form.abilities.any { it is HiddenAbility && it.template.name.equals(pokemon.ability.name, true) }
    }.getOrDefault(false)
    val heldItem = pokemon.heldItem()
    val hasItem: Boolean = !heldItem.isEmpty
    val itemName: String = if (hasItem) heldItem.name.string else ""
    val moves: List<String> = pokemon.moveSet.getMoves().map { runCatching { it.displayName.string }.getOrDefault(it.name) }
    val marked: Boolean = pokemon.marks.isNotEmpty()
    val markNames: List<String> = pokemon.marks.map { runCatching { Text.translatable(it.name).string }.getOrDefault(it.name) }.sorted()
    val size: String = runCatching { pokemon.getSizeCategory().name }.getOrDefault("")
    val dex: Int = pokemon.species.nationalPokedexNumber
    val ownOt: Boolean = runCatching {
        val me = MinecraftClient.getInstance().player?.uuid?.toString()
        pokemon.originalTrainerType == OriginalTrainerType.PLAYER && me != null && pokemon.originalTrainer.equals(me, true)
    }.getOrDefault(false)
    val otName: String = runCatching { pokemon.originalTrainerName ?: "" }.getOrDefault("")
    val ballName: String = runCatching { pokemon.caughtBall.item().name.string }.getOrDefault("")
    val friendship: Int = pokemon.friendship
    val stats: IntArray = runCatching {
        intArrayOf(pokemon.maxHealth, pokemon.attack, pokemon.defence, pokemon.specialAttack, pokemon.specialDefence, pokemon.speed)
    }.getOrDefault(IntArray(6))

    /** Lower-case, accent-free text the search box is matched against. */
    val search: String = NameUtil.normalize(listOfNotNull(
        speciesName, speciesId, nickname, formLabel, natureName, abilityName, itemName, otName,
        *moves.toTypedArray(), *typeNames.toTypedArray()
    ).joinToString(" ")).let { " $it " }

    /** Identity of what was planned against: a change here stops any batch working on it. */
    fun fingerprint(): String = "$uuid@${place.box}/${place.slot}"
}

/** Builds the entries of a PC and a party; [revision] changes whenever their content does. */
object PcIndex {
    fun build(pc: ClientPC?, party: ClientParty?): List<PcEntry> {
        val out = ArrayList<PcEntry>(1000)
        party?.slots?.forEachIndexed { i, p -> if (p != null) runCatching { out += PcEntry(p, PcPlace.party(i)) } }
        pc?.boxes?.forEachIndexed { b, box ->
            box.slots.forEachIndexed { s, p -> if (p != null) runCatching { out += PcEntry(p, PcPlace(b, s)) } }
        }
        return out
    }

    /**
     * A cheap summary of the storage: which object sits where. Cobblemon replaces the
     * Pokémon object on every update packet, so identity also catches level-ups, renames
     * and held-item changes, not only moves.
     */
    fun revision(pc: ClientPC?, party: ClientParty?): Long {
        var h = 1L
        // party Pokémon can change in place (level, health) while the PC is open
        party?.slots?.forEachIndexed { i, p -> h = h * 31 + i + 7L * System.identityHashCode(p) + (p?.level ?: 0) * 131L + (p?.experience ?: 0) }
        pc?.boxes?.forEachIndexed { b, box ->
            h = h * 17 + b + (box.name?.string ?: "").hashCode()
            box.slots.forEachIndexed { s, p -> h = h * 31 + s + 7L * System.identityHashCode(p) }
        }
        return h
    }
}
