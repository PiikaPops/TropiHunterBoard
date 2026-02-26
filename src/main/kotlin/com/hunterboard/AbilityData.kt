package com.hunterboard

import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility

data class AbilityLearner(
    val speciesName: String,
    val isHidden: Boolean
)

object AbilityData {

    private var reverseAbilityIndex: Map<String, List<AbilityLearner>>? = null
    private var allAbilityTemplates: List<AbilityTemplate>? = null
    @Volatile var reverseIndexReady = false
        private set

    fun buildReverseIndex() {
        if (reverseAbilityIndex != null) return
        try {
            // Use resourceIdentifier.path as key (safe for Identifier creation, no spaces)
            val dexNumbers = mutableMapOf<String, Int>()
            for (species in PokemonSpecies.species) {
                dexNumbers[species.resourceIdentifier.path] = species.nationalPokedexNumber
            }

            val index = mutableMapOf<String, MutableList<AbilityLearner>>()
            val templates = mutableMapOf<String, AbilityTemplate>()

            for (species in PokemonSpecies.implemented) {
                val speciesId = species.resourceIdentifier.path
                try {
                    for (ability in species.abilities) {
                        val pa = ability as? PotentialAbility ?: continue
                        val abilityName = pa.template.name.lowercase()
                        val isHidden = pa is HiddenAbility
                        index.getOrPut(abilityName) { mutableListOf() }
                            .add(AbilityLearner(speciesId, isHidden))
                        templates.putIfAbsent(abilityName, pa.template)
                    }
                } catch (_: Exception) {}
            }

            reverseAbilityIndex = index.mapValues { (_, learners) ->
                learners.distinctBy { "${it.speciesName}|${it.isHidden}" }
                    .sortedBy { dexNumbers[it.speciesName] ?: 9999 }
            }
            allAbilityTemplates = templates.values.toList()
            reverseIndexReady = true
            HunterBoard.LOGGER.info("Built ability reverse index: ${index.size} abilities")
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to build ability reverse index: ${e.message}")
            reverseAbilityIndex = emptyMap()
            reverseIndexReady = true
        }
    }

    fun getSpeciesWithAbility(abilityName: String): List<AbilityLearner> {
        return reverseAbilityIndex?.get(abilityName.lowercase()) ?: emptyList()
    }

    fun getAllAbilities(): List<AbilityTemplate> {
        return allAbilityTemplates ?: emptyList()
    }
}
