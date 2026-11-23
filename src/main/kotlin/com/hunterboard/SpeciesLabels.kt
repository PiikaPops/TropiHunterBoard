package com.hunterboard

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Species labels (legendary, mythical, ultra beast…) read from Cobblemon's own data.
 *
 * Cobblemon decides `isLegendary()` / `isMythical()` from species and form labels, but it
 * never sends labels to the client: on a server every species arrives with none, and those
 * calls always answer false. The legendaries were then offered as release candidates and
 * escaped the "legendary" filter. Cobblemon's jar carries the species files on the client
 * too, so the labels are read there once; whatever the client did receive is added on top
 * (a single-player world has them all).
 */
object SpeciesLabels {
    private val byKey: Map<String, Set<String>> by lazy { load() }

    private fun load(): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        try {
            val root = FabricLoader.getInstance().getModContainer("cobblemon").orElse(null)
                ?.findPath("data/cobblemon/species")?.orElse(null) ?: return out
            Files.walk(root).use { paths ->
                paths.filter { it.toString().endsWith(".json") }.forEach { p ->
                    runCatching {
                        val o = Files.newBufferedReader(p).use { JsonParser.parseReader(it) }.asJsonObject
                        val id = p.fileName.toString().removeSuffix(".json").lowercase()
                        val labels = o.getAsJsonArray("labels")?.map { it.asString.lowercase() }?.toMutableSet() ?: mutableSetOf()
                        out[id] = labels
                        o.getAsJsonArray("forms")?.forEach { f ->
                            val fo = f.asJsonObject
                            val name = fo.get("name")?.asString?.lowercase() ?: return@forEach
                            val fl = fo.getAsJsonArray("labels")?.map { it.asString.lowercase() } ?: emptyList()
                            out["$id|$name"] = (labels + fl).toMutableSet()
                        }
                    }
                }
            }
            HunterBoard.LOGGER.info("Species labels read from Cobblemon's data: ${out.size} species and forms")
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Could not read species labels: ${e.message}")
        }
        return out
    }

    fun of(pokemon: Pokemon): Set<String> {
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        val form = pokemon.form.name.lowercase()
        val fromData = byKey["$id|$form"] ?: byKey[id] ?: emptySet()
        val received = runCatching { pokemon.species.labels + pokemon.form.labels }.getOrDefault(emptySet())
        return if (received.isEmpty()) fromData else fromData + received.map { it.lowercase() }
    }

    fun isLegendary(p: Pokemon) = "legendary" in of(p)
    fun isMythical(p: Pokemon) = "mythical" in of(p)
    fun isUltraBeast(p: Pokemon) = "ultra_beast" in of(p)
    fun isParadox(p: Pokemon) = "paradox" in of(p)
}
