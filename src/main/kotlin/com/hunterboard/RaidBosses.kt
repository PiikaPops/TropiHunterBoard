package com.hunterboard

import com.cobblemon.mod.common.pokemon.FormData

/**
 * Tropimon's raid bosses wear a boss or mega-boss variant: a form, or an aspect, named
 * after it. Not all of them do (a Noivern boss wore none), so [RaidCard] also goes by where
 * the boss stands; this is only the way to tell one anywhere else.
 */
object RaidBosses {

    val pattern = Regex("boss|raid")

    fun isBossForm(form: FormData): Boolean =
        pattern.containsMatchIn(form.name.lowercase()) || form.aspects.any { pattern.containsMatchIn(it.lowercase()) }
}
