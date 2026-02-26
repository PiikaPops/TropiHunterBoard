package com.hunterboard

import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.util.Identifier

data class ItemDropper(
    val speciesName: String,
    val percentage: Float,
    val quantityMin: Int,
    val quantityMax: Int
)

object DropData {

    private var reverseDropIndex: Map<String, List<ItemDropper>>? = null
    private var allDroppableItems: List<Identifier>? = null
    @Volatile var reverseIndexReady = false
        private set

    fun buildReverseIndex() {
        if (reverseDropIndex != null) return
        try {
            val dexNumbers = mutableMapOf<String, Int>()
            for (species in PokemonSpecies.species) {
                dexNumbers[species.resourceIdentifier.path] = species.nationalPokedexNumber
            }

            val index = mutableMapOf<String, MutableList<ItemDropper>>()
            val itemIds = mutableSetOf<Identifier>()

            for (species in PokemonSpecies.implemented) {
                val speciesId = species.resourceIdentifier.path
                try {
                    for (entry in species.drops.entries) {
                        if (entry is ItemDropEntry) {
                            val itemId = entry.item.toString()
                            val qty = entry.quantityRange
                            val qMin = qty?.first ?: entry.quantity
                            val qMax = qty?.last ?: entry.quantity
                            index.getOrPut(itemId) { mutableListOf() }
                                .add(ItemDropper(speciesId, entry.percentage, qMin, qMax))
                            itemIds.add(entry.item)
                        }
                    }
                } catch (_: Exception) {}
            }

            reverseDropIndex = index.mapValues { (_, droppers) ->
                droppers.distinctBy { it.speciesName }
                    .sortedBy { dexNumbers[it.speciesName] ?: 9999 }
            }
            allDroppableItems = itemIds.toList().sortedBy { it.toString() }
            reverseIndexReady = true
            HunterBoard.LOGGER.info("Built drop reverse index: ${index.size} items")
        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Failed to build drop reverse index: ${e.message}")
            reverseDropIndex = emptyMap()
            allDroppableItems = emptyList()
            reverseIndexReady = true
        }
    }

    fun getSpeciesWithDrop(itemId: String): List<ItemDropper> {
        return reverseDropIndex?.get(itemId) ?: emptyList()
    }

    fun getAllDroppableItems(): List<Identifier> {
        return allDroppableItems ?: emptyList()
    }
}
