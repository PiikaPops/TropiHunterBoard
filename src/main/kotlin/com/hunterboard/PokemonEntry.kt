package com.hunterboard

import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species

sealed class PokemonEntry {
    abstract val species: Species

    data class Regular(override val species: Species) : PokemonEntry()
    data class Mega(override val species: Species, val form: FormData) : PokemonEntry()

    companion object {
        /** Human-readable label for a mega form name, localized. */
        fun megaLabel(formName: String): String {
            val key = when (formName.lowercase()) {
                "mega"   -> "Mega"
                "mega-x" -> "Mega X"
                "mega-y" -> "Mega Y"
                else     -> formName.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
            return Translations.tr(key)
        }
    }
}
